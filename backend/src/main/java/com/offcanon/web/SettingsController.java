package com.offcanon.web;

import com.offcanon.identity.application.AuthApplicationService;
import com.offcanon.identity.domain.UserSettings;
import com.offcanon.identity.web.IdentityContext;
import com.offcanon.infrastructure.workspace.RuntimeRetentionService;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.springframework.web.bind.annotation.PostMapping;
import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private final AuthApplicationService auth;
    private final IdentityContext identity;
    private final JdbcTemplate jdbc;
    private final RuntimeRetentionService retention;

    public SettingsController(AuthApplicationService auth, IdentityContext identity, JdbcTemplate jdbc, RuntimeRetentionService retention) {
        this.auth = auth;
        this.identity = identity;
        this.jdbc = jdbc;
        this.retention = retention;
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

    @GetMapping("/storage")
    public StorageSummaryResponse storage(HttpServletRequest request) {
        var user = identity.requireUser(request);
        String owner = user.id().toString();
        long projects = count("SELECT COUNT(*) FROM projects WHERE owner_id=?", owner);
        long sessions = count("SELECT COUNT(*) FROM sessions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)", owner);
        long experiments = count("SELECT COUNT(*) FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)", owner);
        long evidence = count("SELECT COUNT(*) FROM evidence WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))", owner);
        long events = count("SELECT COUNT(*) FROM run_events WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))", owner);
        long memories = count("SELECT COUNT(*) FROM task_memory_revisions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)", owner);
        long snapshots = count("SELECT COUNT(*) FROM snapshots WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)", owner);
        return new StorageSummaryResponse(projects, sessions, experiments, evidence, events, memories, snapshots);
    }

    @PostMapping("/storage/runtime")
    public CleanupResponse cleanupRuntime(HttpServletRequest request) {
        identity.requireUser(request);
        var report = retention.cleanup(java.time.Instant.now());
        return new CleanupResponse(report.total());
    }

    @GetMapping("/storage/export")
    public java.util.Map<String, Object> export(HttpServletRequest request) {
        var user = identity.requireUser(request);
        String owner = user.id().toString();
        var result = new LinkedHashMap<String, Object>();
        result.put("format", "offcanon-export-v1");
        result.put("exportedAt", java.time.Instant.now());
        result.put("user", java.util.Map.of("id", user.id(), "username", user.username(), "createdAt", user.createdAt()));
        result.put("settings", response(auth.getSettings(user)));
        result.put("projects", jdbc.queryForList("SELECT id,name,canonical_path,verification_commands,created_at,version FROM projects WHERE owner_id=? ORDER BY created_at", owner));
        result.put("sessions", jdbc.queryForList("SELECT id,project_id,title,created_at,version FROM sessions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at", owner));
        result.put("experiments", jdbc.queryForList("SELECT id,project_id,session_id,continued_from_experiment_id,task,created_at,status,base_snapshot_id,result_snapshot_id,agent_summary,failure_reason,verification_passed,version FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at", owner));
        result.put("evidence", jdbc.queryForList("SELECT id,experiment_id,snapshot_id,kind,command,cwd,exit_code,stdout,stderr,started_at,completed_at,duration_millis,timed_out,trusted,environment_profile,cancelled FROM evidence WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)) ORDER BY started_at", owner));
        result.put("events", jdbc.queryForList("SELECT event_id,experiment_id,sequence,type,event_timestamp,payload FROM run_events WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)) ORDER BY experiment_id,sequence", owner));
        result.put("memoryRevisions", jdbc.queryForList("SELECT id,project_id,session_id,source_experiment_id,source_snapshot_id,source_fingerprint,memory_kind,content,source_evidence_ids,origin,trust,status,supersedes_ids,created_at,sequence FROM task_memory_revisions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at,sequence", owner));
        return result;
    }

    private long count(String sql, String owner) {
        Long value = jdbc.queryForObject(sql, Long.class, owner);
        return value == null ? 0 : value;
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

    public record StorageSummaryResponse(long projects, long sessions, long experiments, long evidence,
                                         long events, long memoryRevisions, long snapshots) {}

    public record CleanupResponse(int removed) {}
}
