package com.offcanon.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offcanon.agent.domain.ModelMessage;
import com.offcanon.agent.domain.ModelRequest;
import com.offcanon.agent.domain.ModelTransientException;
import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.shared.domain.DomainException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleModelAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<StubResponse> response = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> request = new AtomicReference<>();
    private HttpServer server;
    private OpenAiCompatibleModelAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
        adapter = new OpenAiCompatibleModelAdapter(
                mapper, HttpClient.newHttpClient(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesToolCalls() throws Exception {
        respond(200, """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"write_file","arguments":"{\\"path\\":\\"src/App.java\\",\\"content\\":\\"ok\\"}"}}
                ]},"finish_reason":"tool_calls"}]}
                """);

        var result = adapter.complete(request());

        assertEquals("", result.text());
        assertEquals("tool_calls", result.finishReason());
        assertEquals(1, result.toolCalls().size());
        assertEquals("call-1", result.toolCalls().get(0).id());
        assertEquals("write_file", result.toolCalls().get(0).name());
        assertEquals("src/App.java", result.toolCalls().get(0).arguments().get("path"));
        assertEquals("ok", result.toolCalls().get(0).arguments().get("content"));

        CapturedRequest captured = request.get();
        assertEquals("/v1/chat/completions", captured.path());
        assertEquals("Bearer local-test-key", captured.authorization());
        JsonNode body = mapper.readTree(captured.body());
        assertEquals("contract-model", body.path("model").asText());
        assertEquals("user", body.path("messages").path(0).path("role").asText());
        assertEquals("fix the test", body.path("messages").path(0).path("content").asText());
        assertEquals("write_file", body.path("tools").path(0).path("function").path("name").asText());
    }

    @Test
    void usesProviderConfigurationCarriedByTheRequest() throws Exception {
        respond(200, """
                {"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}
                """);
        adapter.complete(new ModelRequest(List.of(ModelMessage.user("runtime config")), List.of(),
                Duration.ofSeconds(5), endpoint(), "runtime-model", "runtime-key"));

        CapturedRequest captured = request.get();
        assertEquals("/v1/chat/completions", captured.path());
        assertEquals("runtime-model", mapper.readTree(captured.body()).path("model").asText());
        assertEquals("Bearer runtime-key", captured.authorization());
    }

    @Test
    void rejectsInvalidPerRequestEndpointBeforeSendingTheGlobalApiKey() {
        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(
                request("http://127.0.0.1:" + server.getAddress().getPort()
                        + "/v1?api-version=2024", "runtime-model", "local-test-key")));

        assertEquals("MODEL_ENDPOINT_INVALID", error.code());
        assertTrue(request.get() == null, "an invalid endpoint must be rejected before an HTTP request");

        DomainException outOfRangePort = assertThrows(DomainException.class, () -> adapter.complete(
                request("http://127.0.0.1:65536/v1", "runtime-model", "local-test-key")));
        assertEquals("MODEL_ENDPOINT_INVALID", outOfRangePort.code());
        assertTrue(request.get() == null, "an out-of-range port must be rejected before an HTTP request");
    }

    @Test
    void classifiesRateLimitAndServerFailuresAsTransient() {
        for (int status : List.of(429, 503)) {
            respond(status, "{\"error\":\"temporarily unavailable\"}");

            DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

            assertEquals("MODEL_TRANSIENT_FAILURE", error.code());
            assertTrue(error.getMessage().contains(Integer.toString(status)));
        }
    }

    @Test
    void carriesProviderRetryAfterIntoTransientFailure() {
        respond(429, "{\"error\":\"rate limited\"}", Map.of("Retry-After", "7"));

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        ModelTransientException transientFailure = assertInstanceOf(ModelTransientException.class, error);
        assertEquals(Duration.ofSeconds(7), transientFailure.retryAfter().orElseThrow());
    }

    @Test
    void classifiesOtherClientFailuresAsPermanent() {
        respond(401, "{\"error\":\"invalid key\"}");

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        assertEquals("MODEL_REQUEST_FAILED", error.code());
        assertTrue(error.getMessage().contains("401"));
    }

    @Test
    void rejectsMalformedSuccessfulResponse() {
        respond(200, "not-json");

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        assertEquals("MODEL_RESPONSE_INVALID", error.code());
    }

    @Test
    void rejectsMalformedToolCallShapesAndTrailingJson() {
        List<String> malformed = List.of(
                """
                        {"choices":[{"message":{"content":null,"tool_calls":[
                          {"id":"call-1","type":"custom","function":{"name":"write_file","arguments":"{}"}}
                        ]},"finish_reason":"tool_calls"}]}
                        """,
                """
                        {"choices":[{"message":{"content":null,"tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"write_file","arguments":{"path":"x"}}}
                        ]},"finish_reason":"tool_calls"}]}
                        """,
                """
                        {"choices":[{"message":{"content":null,"tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"write_file"}}
                        ]},"finish_reason":"tool_calls"}]}
                        """,
                """
                        {"choices":[{"message":{"content":null,"tool_calls":[
                          {"id":"call-1","type":"function","function":{"name":"write_file","arguments":"[]"}}
                        ]},"finish_reason":"tool_calls"}]}
                        """,
                """
                        {"choices":[{"message":{"content":7},"finish_reason":"stop"}]}
                        """,
                """
                        {"choices":[{"message":{"content":"done","tool_calls":{}},"finish_reason":"stop"}]}
                        """,
                "{\"choices\":[{\"message\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]} {\"extra\":true}"
        );

        for (String body : malformed) {
            respond(200, body);
            DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));
            assertEquals("MODEL_RESPONSE_INVALID", error.code(), body);
        }
    }

    @Test
    void rejectsTrailingJsonInsideToolArguments() {
        respond(200, """
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"write_file","arguments":"{} {}"}}
                ]},"finish_reason":"tool_calls"}]}
                """);

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        assertEquals("MODEL_RESPONSE_INVALID", error.code());
    }

    @Test
    void rejectsOversizedSuccessfulResponse() {
        response.set(new StubResponse(200, new byte[2_000_001]));

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        assertEquals("MODEL_RESPONSE_TOO_LARGE", error.code());
    }

    @Test
    void preservesAssistantTextAlongsideToolCallsOnTheNextRequest() throws Exception {
        respond(200, """
                {"choices":[{"message":{"content":"done"},"finish_reason":"stop"}]}
                """);
        ToolCall call = new ToolCall("call-1", "write_file", Map.of(
                "path", "src/App.java", "content", "ok"));
        ModelRequest modelRequest = new ModelRequest(List.of(
                ModelMessage.user("fix the test"),
                ModelMessage.assistant("I inspected the failing test.", List.of(call)),
                ModelMessage.tool(call.id(), call.name(), "Wrote src/App.java")),
                request().tools(), Duration.ofSeconds(5), endpoint(), "contract-model", "local-test-key");

        adapter.complete(modelRequest);

        JsonNode messages = mapper.readTree(request.get().body()).path("messages");
        assertEquals("I inspected the failing test.", messages.path(1).path("content").asText());
        assertEquals("call-1", messages.path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("call-1", messages.path(2).path("tool_call_id").asText());
    }

    @Test
    void timesOutWhenResponseBodyStallsAfterHeaders() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        response.set(new StubResponse(200, "{".getBytes(StandardCharsets.UTF_8), 1_024,
                bodyStarted, releaseBody));
        adapter = new OpenAiCompatibleModelAdapter(
                mapper, HttpClient.newHttpClient(), Duration.ofMillis(150));
        long started = System.nanoTime();

        try {
            DomainException error = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThrows(DomainException.class, () -> adapter.complete(request())));

            assertEquals("MODEL_TRANSIENT_FAILURE", error.code());
            assertTrue(bodyStarted.await(1, TimeUnit.SECONDS));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        } finally {
            releaseBody.countDown();
        }
    }

    private ModelRequest request() {
        ToolDefinition tool = new ToolDefinition("write_file", "Write a file", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")));
        return new ModelRequest(List.of(ModelMessage.user("fix the test")), List.of(tool), Duration.ofSeconds(5),
                endpoint(), "contract-model", "local-test-key");
    }

    private ModelRequest request(String endpoint, String model, String apiKey) {
        return new ModelRequest(List.of(ModelMessage.user("fix the test")), List.of(), Duration.ofSeconds(5),
                endpoint, model, apiKey);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private void respond(int status, String body) {
        response.set(new StubResponse(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    private void respond(int status, String body, Map<String, String> headers) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        response.set(new StubResponse(status, bytes, bytes.length, null, null, headers));
    }

    private void handle(HttpExchange exchange) throws IOException {
        request.set(new CapturedRequest(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        StubResponse current = response.get();
        if (current == null) throw new AssertionError("Test response was not configured");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        current.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(current.status(), current.declaredLength());
        try (var output = exchange.getResponseBody()) {
            output.write(current.body());
            output.flush();
            if (current.releaseBody() != null) {
                current.bodyStarted().countDown();
                try {
                    current.releaseBody().await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record StubResponse(int status,
                                byte[] body,
                                int declaredLength,
                                CountDownLatch bodyStarted,
                                CountDownLatch releaseBody,
                                Map<String, String> headers) {
        private StubResponse(int status, byte[] body) {
            this(status, body, body.length, null, null, Map.of());
        }

        private StubResponse(int status,
                             byte[] body,
                             int declaredLength,
                             CountDownLatch bodyStarted,
                             CountDownLatch releaseBody) {
            this(status, body, declaredLength, bodyStarted, releaseBody, Map.of());
        }
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
