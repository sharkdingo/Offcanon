package com.pico.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pico.agent.domain.ModelMessage;
import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ToolDefinition;
import com.pico.shared.domain.DomainException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        adapter = new OpenAiCompatibleModelAdapter(mapper, HttpClient.newHttpClient(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "contract-model", Duration.ofSeconds(5), () -> "local-test-key");
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
    void classifiesRateLimitAndServerFailuresAsTransient() {
        for (int status : List.of(429, 503)) {
            respond(status, "{\"error\":\"temporarily unavailable\"}");

            DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

            assertEquals("MODEL_TRANSIENT_FAILURE", error.code());
            assertTrue(error.getMessage().contains(Integer.toString(status)));
        }
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
    void rejectsOversizedSuccessfulResponse() {
        response.set(new StubResponse(200, new byte[2_000_001]));

        DomainException error = assertThrows(DomainException.class, () -> adapter.complete(request()));

        assertEquals("MODEL_RESPONSE_TOO_LARGE", error.code());
    }

    private ModelRequest request() {
        ToolDefinition tool = new ToolDefinition("write_file", "Write a file", Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")));
        return new ModelRequest(List.of(ModelMessage.user("fix the test")), List.of(tool), Duration.ofSeconds(5));
    }

    private void respond(int status, String body) {
        response.set(new StubResponse(status, body.getBytes(StandardCharsets.UTF_8)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        request.set(new CapturedRequest(exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        StubResponse current = response.get();
        if (current == null) throw new AssertionError("Test response was not configured");
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(current.status(), current.body().length);
        try (var output = exchange.getResponseBody()) {
            output.write(current.body());
        }
    }

    private record StubResponse(int status, byte[] body) {
    }

    private record CapturedRequest(String path, String authorization, String body) {
    }
}
