CREATE TABLE users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE COLLATE BINARY,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    version INTEGER NOT NULL
);

CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    name TEXT NOT NULL,
    canonical_path TEXT NOT NULL,
    canonical_path_key TEXT NOT NULL UNIQUE,
    verification_commands TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    version INTEGER NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES users(id)
);
CREATE INDEX idx_projects_owner ON projects(owner_id);

CREATE TABLE auth_sessions (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions(expires_at);

CREATE TABLE user_settings (
    user_id TEXT PRIMARY KEY,
    theme TEXT NOT NULL,
    locale TEXT NOT NULL,
    model_endpoint TEXT NOT NULL,
    model_name TEXT NOT NULL,
    model_api_key_ciphertext TEXT NOT NULL DEFAULT '',
    agent_max_steps INTEGER NOT NULL,
    agent_run_timeout_seconds INTEGER NOT NULL,
    context_limit_chars INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    version INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE sessions (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    title TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    version INTEGER NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_sessions_project ON sessions(project_id);

CREATE TABLE snapshots (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    materialized_path TEXT NOT NULL,
    captured_at INTEGER NOT NULL,
    included_files TEXT NOT NULL,
    excluded_files TEXT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_snapshots_project ON snapshots(project_id);

CREATE TABLE experiments (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    continued_from_experiment_id TEXT,
    task TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL,
    base_snapshot_id TEXT,
    result_snapshot_id TEXT,
    workspace_path TEXT,
    agent_summary TEXT,
    failure_reason TEXT,
    verification_passed INTEGER CHECK (verification_passed IS NULL OR verification_passed IN (0, 1)),
    version INTEGER NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (session_id) REFERENCES sessions(id),
    FOREIGN KEY (continued_from_experiment_id) REFERENCES experiments(id),
    FOREIGN KEY (base_snapshot_id) REFERENCES snapshots(id),
    FOREIGN KEY (result_snapshot_id) REFERENCES snapshots(id)
);
CREATE INDEX idx_experiments_project ON experiments(project_id);
CREATE INDEX idx_experiments_session_status ON experiments(session_id, status);
CREATE INDEX idx_experiments_continued_from ON experiments(continued_from_experiment_id);

CREATE TABLE evidence (
    id TEXT PRIMARY KEY,
    experiment_id TEXT NOT NULL,
    snapshot_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    command TEXT NOT NULL,
    cwd TEXT NOT NULL,
    exit_code INTEGER NOT NULL,
    stdout TEXT NOT NULL,
    stderr TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    completed_at INTEGER NOT NULL,
    duration_millis INTEGER NOT NULL,
    timed_out INTEGER NOT NULL CHECK (timed_out IN (0, 1)),
    trusted INTEGER NOT NULL CHECK (trusted IN (0, 1)),
    environment_profile TEXT NOT NULL DEFAULT 'unknown',
    cancelled INTEGER NOT NULL DEFAULT 0 CHECK (cancelled IN (0, 1)),
    FOREIGN KEY (experiment_id) REFERENCES experiments(id),
    FOREIGN KEY (snapshot_id) REFERENCES snapshots(id)
);
CREATE INDEX idx_evidence_experiment ON evidence(experiment_id, started_at);

CREATE TABLE run_events (
    event_id TEXT NOT NULL UNIQUE,
    experiment_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    type TEXT NOT NULL,
    event_timestamp INTEGER NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY (experiment_id, sequence),
    FOREIGN KEY (experiment_id) REFERENCES experiments(id)
);
CREATE INDEX idx_run_events_experiment ON run_events(experiment_id, sequence);

CREATE TABLE task_memory_revisions (
    id TEXT PRIMARY KEY,
    project_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    source_experiment_id TEXT NOT NULL,
    source_snapshot_id TEXT NOT NULL,
    source_fingerprint TEXT NOT NULL,
    memory_kind TEXT NOT NULL,
    content TEXT NOT NULL,
    source_evidence_ids TEXT NOT NULL,
    origin TEXT NOT NULL,
    trust TEXT NOT NULL,
    status TEXT NOT NULL,
    supersedes_ids TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    sequence INTEGER NOT NULL,
    UNIQUE(session_id, sequence),
    FOREIGN KEY (project_id) REFERENCES projects(id),
    FOREIGN KEY (session_id) REFERENCES sessions(id),
    FOREIGN KEY (source_experiment_id) REFERENCES experiments(id),
    FOREIGN KEY (source_snapshot_id) REFERENCES snapshots(id)
);
CREATE INDEX idx_task_memory_session ON task_memory_revisions(session_id, sequence);
CREATE INDEX idx_task_memory_project ON task_memory_revisions(project_id, created_at);
CREATE INDEX idx_task_memory_experiment ON task_memory_revisions(source_experiment_id);
CREATE INDEX idx_task_memory_snapshot ON task_memory_revisions(source_snapshot_id);

CREATE TABLE promotion_journal (
    promotion_id TEXT PRIMARY KEY,
    experiment_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    base_fingerprint TEXT NOT NULL,
    candidate_fingerprint TEXT NOT NULL,
    candidate_path TEXT NOT NULL,
    touched_files TEXT NOT NULL,
    preimage_hashes TEXT NOT NULL,
    postimage_hashes TEXT NOT NULL,
    phase TEXT NOT NULL,
    owner_id TEXT NOT NULL,
    lease_until INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    resulting_fingerprint TEXT,
    failure_reason TEXT,
    version INTEGER NOT NULL,
    FOREIGN KEY (experiment_id) REFERENCES experiments(id),
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_promotion_journal_experiment ON promotion_journal(experiment_id);
CREATE INDEX idx_promotion_journal_open ON promotion_journal(phase, lease_until);
CREATE INDEX idx_promotion_journal_project_phase ON promotion_journal(project_id, phase, created_at);
