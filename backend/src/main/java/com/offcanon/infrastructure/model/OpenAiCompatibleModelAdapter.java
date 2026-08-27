package com.offcanon.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offcanon.agent.domain.ModelMessage;
import com.offcanon.agent.domain.ModelRequest;
import com.offcanon.agent.domain.ModelResponse;
import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.port.ModelPort;
import com.offcanon.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class OpenAiCompatibleModelAdapter implements ModelPort {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String configuredBaseUrl;
    private final String configuredModel;
    private final Duration requestTimeout;
    private final Supplier<String> apiKeySupplier;
    private static final int MAX_RESPONSE_BYTES = 2_000_000;

    @Autowired
    public OpenAiCompatibleModelAdapter(ObjectMapper mapper,
                                        @Value("${offcanon.model.base-url:}") String configuredBaseUrl,
                                        @Value("${offcanon.model.name:}") String configuredModel,
                                        @Value("${offcanon.model.request-timeout-seconds:120}") long requestTimeoutSeconds) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                configuredBaseUrl, configuredModel, Duration.ofSeconds(Math.max(5, requestTimeoutSeconds)),
                OpenAiCompatibleModelAdapter::environmentApiKey);
    }

    OpenAiCompatibleModelAdapter(ObjectMapper mapper,
                                 HttpClient http,
                                 String configuredBaseUrl,
                                 String configuredModel,
                                 Duration requestTimeout,
                                 Supplier<String> apiKeySupplier) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
        this.configuredBaseUrl = configuredBaseUrl;
        this.configuredModel = configuredModel;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.apiKeySupplier = Objects.requireNonNull(apiKeySupplier, "apiKeySupplier");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        String apiKey = firstNonBlank(apiKeySupplier.get());
        if (apiKey == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set OFFCANON_MODEL_API_KEY before starting an agent run");
        }
        String baseUrl = firstNonBlank(request.modelEndpoint(), configuredBaseUrl, System.getenv("OFFCANON_MODEL_BASE_URL"), System.getenv("OPENAI_BASE_URL"));
        String model = firstNonBlank(request.modelName(), configuredModel, System.getenv("OFFCANON_MODEL_NAME"), System.getenv("OPENAI_MODEL"));
        if (baseUrl == null || model == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set OFFCANON_MODEL_BASE_URL and OFFCANON_MODEL_NAME before starting an agent run");
        }
        final String requestBody;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", serializeMessages(request.messages()));
            body.set("tools", serializeTools(request.tools()));
            body.put("temperature", 0.1);
            requestBody = mapper.writeValueAsString(body);
        } catch (IOException error) {
            throw new DomainException("MODEL_REQUEST_INVALID", "Unable to encode model request");
        }
        try {
            Duration effectiveTimeout = request.timeout().compareTo(requestTimeout) < 0
                    ? request.timeout() : requestTimeout;
            long deadline = System.nanoTime() + effectiveTimeout.toNanos();
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(baseUrl))
                    .timeout(effectiveTimeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                response.body().close();
                if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500) {
                    throw new DomainException("MODEL_TRANSIENT_FAILURE",
                            "Model request temporarily failed with HTTP " + response.statusCode());
                }
                throw new DomainException("MODEL_REQUEST_FAILED", "Model request failed with HTTP " + response.statusCode());
            }
            byte[] bytes;
            bytes = readBodyWithDeadline(response.body(), deadline);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new DomainException("MODEL_RESPONSE_TOO_LARGE", "Model response exceeded the configured safety limit");
            }
            try {
                return parseResponse(new String(bytes, StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new DomainException("MODEL_RESPONSE_INVALID", "Unable to decode model response");
            }
        } catch (DomainException error) {
            throw error;
        } catch (IOException e) {
            throw new DomainException("MODEL_TRANSIENT_FAILURE", "Model request failed before a response was received");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("MODEL_INTERRUPTED", "Model request was interrupted");
        }
    }

    private byte[] readBodyWithDeadline(InputStream body, long deadline) {
        FutureTask<byte[]> task = new FutureTask<>(() -> {
            try (body) {
                return body.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
        });
        Thread worker = Thread.ofVirtual().name("offcanon-model-response-body").start(task);
        try {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                closeQuietly(body);
                cancelWorker(task, worker);
                throw modelReadTimeout();
            }
            return task.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            closeQuietly(body);
            cancelWorker(task, worker);
            throw modelReadTimeout();
        } catch (InterruptedException error) {
            closeQuietly(body);
            cancelWorker(task, worker);
            Thread.currentThread().interrupt();
            throw new DomainException("MODEL_INTERRUPTED", "Model response body read was interrupted");
        } catch (CancellationException error) {
            closeQuietly(body);
            cancelWorker(task, worker);
            throw new DomainException("MODEL_INTERRUPTED", "Model response body read was cancelled");
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof DomainException domain) throw domain;
            if (cause instanceof IOException) {
                throw new DomainException("MODEL_TRANSIENT_FAILURE", "Model response body could not be read");
            }
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error fatal) throw fatal;
            throw new IllegalStateException("Model response body read failed", cause);
        }
    }

    private void cancelWorker(FutureTask<?> task, Thread worker) {
        task.cancel(true);
        worker.interrupt();
    }

    private void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // Closing is best effort after cancellation; the caller must still return promptly.
        }
    }

    private DomainException modelReadTimeout() {
        return new DomainException("MODEL_TRANSIENT_FAILURE", "Model response body timed out");
    }

    private URI endpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions");
    }

    private ArrayNode serializeMessages(List<ModelMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ModelMessage message : messages) {
            ObjectNode item = array.addObject();
            item.put("role", message.role().name().toLowerCase());
            if (message.role() == ModelMessage.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
                if (message.content().isBlank()) item.putNull("content");
                else item.put("content", message.content());
            } else {
                item.put("content", message.content());
            }
            if (message.role() == ModelMessage.Role.TOOL) {
                item.put("tool_call_id", message.toolCallId());
                item.put("name", message.toolName());
            }
            if (message.role() == ModelMessage.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
                ArrayNode calls = item.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    try {
                        function.put("arguments", mapper.writeValueAsString(call.arguments()));
                    } catch (IOException e) {
                        throw new DomainException("MODEL_REQUEST_INVALID", "Unable to serialize tool arguments");
                    }
                }
            }
        }
        return array;
    }

    private ArrayNode serializeTools(List<ToolDefinition> definitions) {
        ArrayNode array = mapper.createArrayNode();
        for (ToolDefinition definition : definitions) {
            ObjectNode item = array.addObject();
            item.put("type", "function");
            ObjectNode function = item.putObject("function");
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.set("parameters", mapper.valueToTree(definition.parameters()));
        }
        return array;
    }

    private ModelResponse parseResponse(String raw) throws IOException {
        JsonNode root = mapper.readTree(raw);
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) {
            throw new DomainException("MODEL_RESPONSE_INVALID", "Model response contains no choices");
        }
        JsonNode message = choice.path("message");
        String text = message.path("content").isNull() ? "" : message.path("content").asText("");
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            String id = call.path("id").asText("");
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            String argumentText = function.path("arguments").asText("{}");
            if (id.isBlank() || name.isBlank()) {
                throw new DomainException("MODEL_RESPONSE_INVALID", "Model returned an invalid tool call");
            }
            Map<String, Object> arguments = mapper.readValue(argumentText, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            calls.add(new ToolCall(id, name, arguments));
        }
        return new ModelResponse(text, calls, choice.path("finish_reason").asText("unknown"));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String environmentApiKey() {
        String offcanonKey = System.getenv("OFFCANON_MODEL_API_KEY");
        return offcanonKey == null || offcanonKey.isBlank() ? System.getenv("OPENAI_API_KEY") : offcanonKey;
    }
}
