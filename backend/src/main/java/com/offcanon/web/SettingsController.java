package com.offcanon.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {
    private static final int MAX_EXPORT_ROWS_PER_COLLECTION = 10_000;
    private static final long MAX_EXPORT_TEXT_CHARS_PER_COLLECTION = 25_000_000L;
    private static final long MAX_EXPORT_TEXT_CHARS_TOTAL = 50_000_000L;
    private static final Pattern EXPORT_SECRET_FIELD = Pattern.compile(
            "(?i).*(api[_ -]?key|authorization|password|secret|token|credential|private[_ -]?key|ciphertext).*");
    private static final Pattern EXPORT_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\\"']?\\b(?:api[_ -]?key|authorization|bearer|password|secret|credential|private[_ -]?key|ciphertext|token)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?\\s*(?:bearer\\s+)?)[^\\s,;\\\"'}]+",
            Pattern.UNICODE_CASE);
    private static final Pattern EXPORT_SECRET_QUERY = Pattern.compile(
            "(?i)([?&](?:api[_-]?key|authorization|bearer|password|secret|credential|private[_-]?key|ciphertext|token)=)([^&#\\s\\\"'}`]+)",
            Pattern.UNICODE_CASE);
    private static final int MAX_EXPORT_VALUE_CHARS = 20_000;
    private static final String EXPORT_TRUNCATION_MARKER = "\n...[value truncated]...";
    private static final int MAX_JSON_REDACTION_DEPTH = 32;
    private static final int MAX_JSON_REDACTION_INPUT_CHARS = 2_000_000;
    private final AuthApplicationService auth;
    private final IdentityContext identity;
    private final JdbcTemplate jdbc;
    private final RuntimeRetentionService retention;
    private final ObjectMapper mapper;

    /** Constructor used by the Spring application. */
    @org.springframework.beans.factory.annotation.Autowired
    public SettingsController(AuthApplicationService auth,
                               IdentityContext identity,
                               JdbcTemplate jdbc,
                               RuntimeRetentionService retention,
                               ObjectMapper mapper) {
        this.auth = auth;
        this.identity = identity;
        this.jdbc = jdbc;
        this.retention = retention;
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
    }

    /** Package/test convenience constructor retaining the original boundary. */
    public SettingsController(AuthApplicationService auth, IdentityContext identity, JdbcTemplate jdbc, RuntimeRetentionService retention) {
        this(auth, identity, jdbc, retention, new ObjectMapper());
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
        var user = identity.requireUser(request);
        // Interactive cleanup is account-scoped. The scheduled retention pass
        // remains global, but a normal account must not be able to remove or
        // infer another account's disposable runtime materializations.
        var report = retention.cleanupForOwner(java.time.Instant.now(), user.id());
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
        ExportBudget budget = new ExportBudget();
        result.put("projects", exportRows("projects",
                "SELECT id,name,canonical_path,verification_commands,created_at,version FROM projects WHERE owner_id=? ORDER BY created_at",
                "SELECT COUNT(*) FROM projects WHERE owner_id=?",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(name),0)+COALESCE(LENGTH(canonical_path),0)+COALESCE(LENGTH(verification_commands),0)),0) FROM projects WHERE owner_id=?",
                owner, budget));
        result.put("sessions", exportRows("sessions",
                "SELECT id,project_id,title,created_at,version FROM sessions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at",
                "SELECT COUNT(*) FROM sessions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(title),0)),0) FROM sessions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                owner, budget));
        result.put("experiments", exportRows("experiments",
                "SELECT id,project_id,session_id,continued_from_experiment_id,task,created_at,status,base_snapshot_id,result_snapshot_id,agent_summary,failure_reason,verification_passed,version FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at",
                "SELECT COUNT(*) FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(task),0)+COALESCE(LENGTH(agent_summary),0)+COALESCE(LENGTH(failure_reason),0)),0) FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                owner, budget));
        result.put("snapshots", exportRows("snapshots",
                "SELECT id,project_id,fingerprint,materialized_path,captured_at,included_files,excluded_files FROM snapshots WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY captured_at",
                "SELECT COUNT(*) FROM snapshots WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(fingerprint),0)+COALESCE(LENGTH(materialized_path),0)+COALESCE(LENGTH(included_files),0)+COALESCE(LENGTH(excluded_files),0)),0) FROM snapshots WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                owner, budget));
        result.put("evidence", exportRows("evidence",
                "SELECT id,experiment_id,snapshot_id,kind,command,cwd,exit_code,stdout,stderr,started_at,completed_at,duration_millis,timed_out,trusted,environment_profile,cancelled FROM evidence WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)) ORDER BY started_at",
                "SELECT COUNT(*) FROM evidence WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(command),0)+COALESCE(LENGTH(cwd),0)+COALESCE(LENGTH(stdout),0)+COALESCE(LENGTH(stderr),0)+COALESCE(LENGTH(environment_profile),0)),0) FROM evidence WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))",
                owner, budget));
        result.put("events", exportRows("events",
                "SELECT event_id,experiment_id,sequence,type,event_timestamp,payload FROM run_events WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)) ORDER BY experiment_id,sequence",
                "SELECT COUNT(*) FROM run_events WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(type),0)+COALESCE(LENGTH(payload),0)),0) FROM run_events WHERE experiment_id IN (SELECT id FROM experiments WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?))",
                owner, budget));
        result.put("memoryRevisions", exportRows("memory revisions",
                "SELECT id,project_id,session_id,source_experiment_id,source_snapshot_id,source_fingerprint,memory_kind,content,source_evidence_ids,origin,trust,status,supersedes_ids,created_at,sequence FROM task_memory_revisions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at,sequence",
                "SELECT COUNT(*) FROM task_memory_revisions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(source_fingerprint),0)+COALESCE(LENGTH(memory_kind),0)+COALESCE(LENGTH(content),0)+COALESCE(LENGTH(source_evidence_ids),0)+COALESCE(LENGTH(origin),0)+COALESCE(LENGTH(trust),0)+COALESCE(LENGTH(status),0)+COALESCE(LENGTH(supersedes_ids),0)),0) FROM task_memory_revisions WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                owner, budget));
        result.put("promotionJournals", exportRows("promotion journals",
                "SELECT promotion_id,experiment_id,project_id,base_fingerprint,candidate_fingerprint,candidate_path,touched_files,preimage_hashes,postimage_hashes,phase,lease_until,created_at,updated_at,resulting_fingerprint,failure_reason,version FROM promotion_journal WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?) ORDER BY created_at",
                "SELECT COUNT(*) FROM promotion_journal WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                "SELECT COALESCE(SUM(COALESCE(LENGTH(base_fingerprint),0)+COALESCE(LENGTH(candidate_fingerprint),0)+COALESCE(LENGTH(candidate_path),0)+COALESCE(LENGTH(touched_files),0)+COALESCE(LENGTH(preimage_hashes),0)+COALESCE(LENGTH(postimage_hashes),0)+COALESCE(LENGTH(phase),0)+COALESCE(LENGTH(resulting_fingerprint),0)+COALESCE(LENGTH(failure_reason),0)),0) FROM promotion_journal WHERE project_id IN (SELECT id FROM projects WHERE owner_id=?)",
                owner, budget));
        result.put("redaction", "Secret-looking fields and diagnostic values are redacted; session credentials are never exported.");
        return result;
    }

    private List<Map<String, Object>> exportRows(String label,
                                                   String sql,
                                                   String countSql,
                                                   String textSizeSql,
                                                   String owner,
                                                   ExportBudget budget) {
        long rowCount = count(countSql, owner);
        long textChars = count(textSizeSql, owner);
        if (rowCount > MAX_EXPORT_ROWS_PER_COLLECTION
                || textChars > MAX_EXPORT_TEXT_CHARS_PER_COLLECTION) {
            throw new com.offcanon.shared.domain.DomainException("EXPORT_TOO_LARGE",
                    "The " + label + " collection is too large to export in one request");
        }
        budget.add(textChars, label);
        List<Map<String, Object>> rows = jdbc.queryForList(sql + " LIMIT " + (MAX_EXPORT_ROWS_PER_COLLECTION + 1), owner);
        if (rows.size() > MAX_EXPORT_ROWS_PER_COLLECTION) {
            throw new com.offcanon.shared.domain.DomainException("EXPORT_TOO_LARGE",
                    "The " + label + " collection is too large to export in one request");
        }
        return rows.stream().map(this::sanitizeExportRow).toList();
    }

    private Map<String, Object> sanitizeExportRow(Map<String, Object> row) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        row.forEach((key, value) -> sanitized.put(key, sanitizeExportValue(key, value)));
        return sanitized;
    }

    private Object sanitizeExportValue(String key, Object value) {
        if (value == null) return null;
        if (key != null && EXPORT_SECRET_FIELD.matcher(key).matches()) return "[REDACTED]";
        if (value instanceof String text) return sanitizeExportText(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> nested.put(String.valueOf(nestedKey),
                    sanitizeExportValue(String.valueOf(nestedKey), nestedValue)));
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> nested = new ArrayList<>();
            for (Object item : collection) nested.add(sanitizeExportValue(key, item));
            return nested;
        }
        return value;
    }

    private String sanitizeExportText(String value) {
        boolean truncated = value.length() > MAX_EXPORT_VALUE_CHARS;
        // Only the bounded prefix can be returned to the client. Avoid
        // parsing or copying an unbounded diagnostic value before truncation.
        String bounded = truncated ? value.substring(0, MAX_EXPORT_VALUE_CHARS) : value;
        String sanitized = sanitizeStructuredJson(bounded, 0);
        sanitized = EXPORT_SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1[REDACTED]");
        sanitized = EXPORT_SECRET_QUERY.matcher(sanitized).replaceAll("$1[REDACTED]");
        if (truncated || sanitized.length() > MAX_EXPORT_VALUE_CHARS) {
            int prefixLimit = Math.max(0, MAX_EXPORT_VALUE_CHARS - EXPORT_TRUNCATION_MARKER.length());
            return sanitized.substring(0, Math.min(sanitized.length(), prefixLimit))
                    + EXPORT_TRUNCATION_MARKER;
        }
        return sanitized;
    }

    /**
     * Event payloads and command output are stored as strings, so key-based
     * redaction alone cannot see a nested JSON field such as
     * {@code {"api_key":"..."}}. Parse bounded JSON values and redact keys
     * recursively before falling back to the assignment matcher for plain
     * text diagnostics.
     */
    private String sanitizeStructuredJson(String value, int depth) {
        if (depth >= MAX_JSON_REDACTION_DEPTH) return value;
        if (value.length() > MAX_JSON_REDACTION_INPUT_CHARS) return value;
        if (value.length() > MAX_EXPORT_VALUE_CHARS) return value;
        String trimmed = value.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return value;
        try {
            JsonNode parsed = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
            if (parsed == null || !(parsed.isObject() || parsed.isArray())) return value;
            JsonNode sanitized = sanitizeJsonNode(parsed, depth + 1);
            if (sanitized.equals(parsed)) return value;
            return mapper.writeValueAsString(sanitized);
        } catch (Exception ignored) {
            // Keep the plain-text fallback below for malformed or oversized
            // diagnostics; a parser failure must never expose a secret by
            // skipping all redaction.
            return value;
        }
    }

    private JsonNode sanitizeJsonNode(JsonNode node, int depth) {
        if (node == null) return null;
        if (depth >= MAX_JSON_REDACTION_DEPTH) {
            return node.isContainerNode() ? TextNode.valueOf("[REDACTED: nesting limit]") : node;
        }
        if (node.isObject()) {
            ObjectNode sanitized = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String field = entry.getKey();
                if (EXPORT_SECRET_FIELD.matcher(field).matches()) {
                    sanitized.put(field, "[REDACTED]");
                } else {
                    sanitized.set(field, sanitizeJsonNode(entry.getValue(), depth + 1));
                }
            });
            return sanitized;
        }
        if (node.isArray()) {
            ArrayNode sanitized = mapper.createArrayNode();
            node.forEach(item -> sanitized.add(sanitizeJsonNode(item, depth + 1)));
            return sanitized;
        }
        if (node.isTextual()) {
            String text = EXPORT_SECRET_ASSIGNMENT.matcher(node.textValue()).replaceAll("$1[REDACTED]");
            text = EXPORT_SECRET_QUERY.matcher(text).replaceAll("$1[REDACTED]");
            String nested = sanitizeStructuredJson(text, depth + 1);
            if (!nested.equals(text)) text = nested;
            return text.equals(node.textValue()) ? node : TextNode.valueOf(text);
        }
        return node;
    }

    private long count(String sql, String owner) {
        Long value = jdbc.queryForObject(sql, Long.class, owner);
        return value == null ? 0 : value;
    }

    private static final class ExportBudget {
        private long textChars;

        private void add(long next, String label) {
            if (next < 0 || textChars > MAX_EXPORT_TEXT_CHARS_TOTAL - next) {
                throw new com.offcanon.shared.domain.DomainException("EXPORT_TOO_LARGE",
                        "The export is too large to produce in one request (" + label + ")");
            }
            textChars += next;
        }
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
