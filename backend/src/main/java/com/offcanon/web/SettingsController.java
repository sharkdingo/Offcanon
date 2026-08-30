package com.offcanon.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.identity.web.IdentityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final AuthApplicationService auth;
    private final IdentityContext identity;

    public SettingsController(AuthApplicationService auth, IdentityContext identity) {
        this.auth = auth;
        this.identity = identity;
    }

    @GetMapping
    public SettingsResponse get(HttpServletRequest request) {
        return response(auth.getSettings(identity.requireUser(request)));
    }

    /**
     * Exposes only non-secret account model state for the settings UI. In
     * particular, the API key is reduced to a boolean and is never serialized.
     */
    @GetMapping("/model-status")
    public ModelStatusResponse modelStatus(HttpServletRequest request) {
        AuthApplicationService.ModelConfigurationStatus status =
                auth.modelConfigurationStatus(identity.requireUser(request));
        return new ModelStatusResponse(status.apiKeyConfigured(), status.endpointConfigured(),
                status.modelConfigured(), status.endpointValid(), status.endpoint(), status.model());
    }

    /** Application safety limits are safe to show because they contain no secrets. */
    @GetMapping("/runtime-policy")
    public RuntimePolicyResponse runtimePolicy(HttpServletRequest request) {
        identity.requireUser(request);
        var policy = auth.runtimePolicy();
        return new RuntimePolicyResponse(policy.defaultMaxSteps(), policy.defaultRunTimeoutSeconds(),
                policy.defaultContextLimitChars(), policy.maxStepsCeiling(),
                policy.runTimeoutSecondsCeiling(), policy.contextLimitCharsCeiling());
    }

    @PutMapping
    public SettingsResponse update(@Valid @RequestBody UpdateSettingsRequest body,
                                   HttpServletRequest request) {
        return response(auth.updateSettings(identity.requireUser(request), body.theme(), body.locale(), body.modelEndpoint(),
                body.modelName(), body.modelApiKey(), body.agentMaxSteps(), body.agentRunTimeoutSeconds(),
                body.contextLimitChars()));
    }

    @DeleteMapping("/model-credential")
    public SettingsResponse clearModelCredential(HttpServletRequest request) {
        return response(auth.clearModelApiKey(identity.requireUser(request)));
    }

    private SettingsResponse response(UserSettings value) {
        return new SettingsResponse(value.userId(), value.theme(), value.locale(), value.modelEndpoint(), value.modelName(),
                value.modelApiKey() != null && !value.modelApiKey().isBlank(), value.agentMaxSteps(),
                value.agentRunTimeoutSeconds(), value.contextLimitChars(), value.updatedAt(), value.version());
    }

    public record UpdateSettingsRequest(
            @NotBlank @Size(max = 16) String theme,
            @NotBlank @Size(max = 32) String locale,
            @Size(max = 2048) String modelEndpoint,
            @Size(max = 200) String modelName,
            @Size(max = 4096) String modelApiKey,
            @Min(1) @Max(100) int agentMaxSteps,
            @Min(10) @Max(86400) long agentRunTimeoutSeconds,
            @Min(8000) @Max(1000000) int contextLimitChars) {
        public UpdateSettingsRequest {
            modelEndpoint = modelEndpoint == null ? "" : modelEndpoint;
            modelName = modelName == null ? "" : modelName;
            modelApiKey = modelApiKey == null ? "" : modelApiKey;
        }
    }

    public record SettingsResponse(java.util.UUID userId,
                                   String theme,
                                   String locale,
                                   String modelEndpoint,
                                   String modelName,
                                   boolean modelApiKeyConfigured,
                                   int agentMaxSteps,
                                   long agentRunTimeoutSeconds,
                                   int contextLimitChars,
                                   java.time.Instant updatedAt,
                                    long version) {
    }

    public record ModelStatusResponse(boolean apiKeyConfigured,
                                      boolean endpointConfigured,
                                      boolean modelConfigured,
                                      boolean endpointValid,
                                      String endpoint,
                                      String model) {
    }

    public record RuntimePolicyResponse(int defaultMaxSteps,
                                        long defaultRunTimeoutSeconds,
                                        int defaultContextLimitChars,
                                        int maxStepsCeiling,
                                        long runTimeoutSecondsCeiling,
                                        int contextLimitCharsCeiling) {
    }
}
