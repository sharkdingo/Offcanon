package com.offcanon.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offcanon.agent.domain.ModelMessage;
import com.offcanon.agent.domain.ModelRequest;
import com.offcanon.agent.domain.ModelResponse;
import com.offcanon.agent.domain.ModelTransientException;
import com.offcanon.agent.domain.ToolCall;
import com.offcanon.agent.domain.ToolDefinition;
import com.offcanon.port.ModelPort;
import com.offcanon.shared.domain.DomainException;
import com.offcanon.shared.domain.ModelEndpointPolicy;
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
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OpenAiCompatibleModelAdapter implements ModelPort {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Duration requestTimeout;
    private static final int MAX_RESPONSE_BYTES = 2_000_000;

    @Autowired
    public OpenAiCompatibleModelAdapter(ObjectMapper mapper,
                                        @Value("${offcanon.model.request-timeout-seconds:120}") long requestTimeoutSeconds) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Duration.ofSeconds(Math.max(5, requestTimeoutSeconds)));
    }

    OpenAiCompatibleModelAdapter(ObjectMapper mapper, HttpClient http, Duration requestTimeout) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.http = Objects.requireNonNull(http, "http");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        if (request == null) {
            throw new DomainException("MODEL_REQUEST_INVALID", "Model request must not be null");
        }
        String apiKey = firstNonBlank(request.modelApiKey());
        if (apiKey == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set a model API key in Settings before starting an agent run");
        }
        String requestedEndpoint = firstNonBlank(request.modelEndpoint());
        if (requestedEndpoint != null) {
            if (!ModelEndpointPolicy.isValid(requestedEndpoint)) {
                throw new DomainException("MODEL_ENDPOINT_INVALID",
                        "The requested model endpoint must be a valid HTTP(S) base URL without credentials, query or fragment");
            }
        }
        String baseUrl = requestedEndpoint;
        String model = firstNonBlank(request.modelName());
        if (baseUrl == null || model == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set a model endpoint and model name in Settings before starting an agent run");
        }
        if (!ModelEndpointPolicy.isValid(baseUrl)) {
            throw new DomainException("MODEL_ENDPOINT_INVALID",
                    "The configured model endpoint must be a valid HTTP(S) base URL without credentials, query or fragment");
        }
        final String requestBody;
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", serializeMessages(request.messages()));
            body.set("tools", serializeTools(request.tools()));
            body.put("temperature", 0.1);
            requestBody = mapper.writeValueAsString(body);
        } catch (IOException | IllegalArgumentException error) {
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
                Duration retryAfter = retryAfter(response);
                response.body().close();
                if (response.statusCode() == 408 || response.statusCode() == 429 || response.statusCode() >= 500) {
                    throw new ModelTransientException(
                            "Model request temporarily failed with HTTP " + response.statusCode(), retryAfter);
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
            throw new ModelTransientException("Model request failed before a response was received");
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
                throw new ModelTransientException("Model response body could not be read");
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
        return new ModelTransientException("Model response body timed out");
    }

    private Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(this::parseRetryAfter)
                .orElse(null);
    }

    private Duration parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    private URI endpoint(String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return URI.create(trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions");
    }

    private ArrayNode serializeMessages(List<ModelMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ModelMessage message : messages) {
            ObjectNode item = array.addObject();
            // Protocol tokens are ASCII and must not depend on the host
            // default locale (for example, Turkish upper/lower-casing).
            item.put("role", message.role().name().toLowerCase(Locale.ROOT));
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
        JsonNode root = strictTree(raw);
        if (root == null || !root.isObject()) {
            throw invalidResponse("Model response root must be an object");
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new DomainException("MODEL_RESPONSE_INVALID", "Model response contains no choices");
        }
        JsonNode choice = choices.get(0);
        if (choice == null || !choice.isObject()) {
            throw invalidResponse("Model response choice must be an object");
        }
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw invalidResponse("Model response message must be an object");
        }
        JsonNode content = message.path("content");
        String text;
        if (content.isMissingNode() || content.isNull()) {
            text = "";
        } else if (content.isTextual()) {
            text = content.textValue();
        } else {
            throw invalidResponse("Model response content must be text or null");
        }
        JsonNode toolCalls = message.path("tool_calls");
        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray()) {
            throw invalidResponse("Model response tool_calls must be an array");
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : toolCalls) {
            if (!call.isObject()) throw invalidResponse("Model tool call must be an object");
            JsonNode idNode = call.path("id");
            JsonNode typeNode = call.path("type");
            JsonNode function = call.path("function");
            if (!idNode.isTextual() || idNode.textValue().isBlank()) {
                throw invalidResponse("Model tool call id must be a non-blank string");
            }
            if (!typeNode.isTextual() || !"function".equals(typeNode.textValue())) {
                throw invalidResponse("Model tool call type must be function");
            }
            if (!function.isObject()) throw invalidResponse("Model tool call function must be an object");
            JsonNode nameNode = function.path("name");
            JsonNode argumentsNode = function.path("arguments");
            if (!nameNode.isTextual() || nameNode.textValue().isBlank()) {
                throw invalidResponse("Model tool function name must be a non-blank string");
            }
            if (!argumentsNode.isTextual()) {
                throw invalidResponse("Model tool function arguments must be a JSON string");
            }
            String argumentText = argumentsNode.textValue().isBlank() ? "{}" : argumentsNode.textValue();
            JsonNode parsedArguments;
            try {
                parsedArguments = strictTree(argumentText);
            } catch (IOException error) {
                throw invalidResponse("Model tool function arguments are not valid JSON");
            }
            if (parsedArguments == null || !parsedArguments.isObject()) {
                throw invalidResponse("Model tool function arguments must encode a JSON object");
            }
            try {
                Map<String, Object> arguments = mapper.convertValue(parsedArguments,
                        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
                calls.add(new ToolCall(idNode.textValue(), nameNode.textValue(), arguments));
            } catch (IllegalArgumentException error) {
                throw invalidResponse("Model tool function arguments could not be represented as an object");
            }
        }
        JsonNode finishReason = choice.path("finish_reason");
        if (!finishReason.isMissingNode() && !finishReason.isNull() && !finishReason.isTextual()) {
            throw invalidResponse("Model finish_reason must be text");
        }
        return new ModelResponse(text, calls,
                finishReason.isTextual() ? finishReason.textValue() : "unknown");
    }

    private DomainException invalidResponse(String detail) {
        return new DomainException("MODEL_RESPONSE_INVALID", detail);
    }

    private JsonNode strictTree(String raw) throws IOException {
        return mapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(raw);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

}
