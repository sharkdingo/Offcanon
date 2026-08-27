package com.pico.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pico.agent.domain.ModelMessage;
import com.pico.agent.domain.ModelRequest;
import com.pico.agent.domain.ModelResponse;
import com.pico.agent.domain.ToolCall;
import com.pico.agent.domain.ToolDefinition;
import com.pico.port.ModelPort;
import com.pico.shared.domain.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleModelAdapter implements ModelPort {
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String configuredBaseUrl;
    private final String configuredModel;

    public OpenAiCompatibleModelAdapter(ObjectMapper mapper,
                                        @Value("${pico.model.base-url:}") String configuredBaseUrl,
                                        @Value("${pico.model.name:}") String configuredModel) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.configuredBaseUrl = configuredBaseUrl;
        this.configuredModel = configuredModel;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        String apiKey = firstNonBlank(System.getenv("PICO_MODEL_API_KEY"), System.getenv("OPENAI_API_KEY"));
        if (apiKey == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set PICO_MODEL_API_KEY before starting an agent run");
        }
        String baseUrl = firstNonBlank(configuredBaseUrl, System.getenv("PICO_MODEL_BASE_URL"), System.getenv("OPENAI_BASE_URL"));
        String model = firstNonBlank(configuredModel, System.getenv("PICO_MODEL_NAME"), System.getenv("OPENAI_MODEL"));
        if (baseUrl == null || model == null) {
            throw new DomainException("MODEL_NOT_CONFIGURED", "Set PICO_MODEL_BASE_URL and PICO_MODEL_NAME before starting an agent run");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", serializeMessages(request.messages()));
            body.set("tools", serializeTools(request.tools()));
            body.put("temperature", 0.1);
            HttpRequest httpRequest = HttpRequest.newBuilder(endpoint(baseUrl))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new DomainException("MODEL_REQUEST_FAILED", "Model request failed with HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (IOException e) {
            throw new DomainException("MODEL_RESPONSE_INVALID", "Unable to encode or decode model response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException("MODEL_INTERRUPTED", "Model request was interrupted");
        }
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
                item.putNull("content");
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
}
