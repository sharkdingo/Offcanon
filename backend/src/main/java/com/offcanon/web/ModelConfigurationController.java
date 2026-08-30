package com.offcanon.web;

import com.offcanon.agent.domain.ModelMessage;
import com.offcanon.agent.domain.ModelRequest;
import com.offcanon.agent.domain.ModelResponse;
import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.port.ModelPort;
import com.offcanon.shared.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Credential-safe model configuration checks used by the Settings screen. */
@RestController
@RequestMapping("/api/settings")
public class ModelConfigurationController {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    private final AuthApplicationService auth;
    private final IdentityContext identity;
    private final ModelPort model;

    public ModelConfigurationController(AuthApplicationService auth,
                                        IdentityContext identity,
                                        ModelPort model) {
        this.auth = auth;
        this.identity = identity;
        this.model = model;
    }

    /**
     * Sends one no-tools request through the server-side adapter. The endpoint
     * and model are never persisted by this operation, and the API key is read
     * only by the backend adapter.
     */
    @PostMapping("/model-test")
    public ModelTestResponse test(@Valid @RequestBody(required = false) ModelTestRequest body,
                                  HttpServletRequest request) {
        var user = identity.requireUser(request);
        String endpoint = body == null || body.modelEndpoint() == null ? "" : body.modelEndpoint().trim();
        String modelName = body == null || body.modelName() == null ? "" : body.modelName().trim();
        try {
            auth.validateModelEndpoint(user, endpoint);
            ModelRequest modelRequest = new ModelRequest(
                    List.of(ModelMessage.user("Reply with OK only.")), List.of(), TEST_TIMEOUT)
                    .withProvider(endpoint, modelName);
            ModelResponse response = model.complete(modelRequest);
            return new ModelTestResponse(true, "MODEL_CONNECTION_OK",
                    response == null ? "Model responded" : "Model responded successfully");
        } catch (DomainException error) {
            return new ModelTestResponse(false, error.code(), safeMessage(error.getMessage()));
        } catch (RuntimeException error) {
            return new ModelTestResponse(false, "MODEL_CONNECTION_FAILED",
                    "The model connection test failed before a usable response was received");
        }
    }

    private String safeMessage(String value) {
        if (value == null || value.isBlank()) return "The model connection test failed";
        // Do not echo arbitrary provider responses or request URLs into the UI.
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    public record ModelTestRequest(@Size(max = 2_048) String modelEndpoint,
                                   @Size(max = 200) String modelName) {
    }

    public record ModelTestResponse(boolean reachable, String code, String detail) {
    }
}
