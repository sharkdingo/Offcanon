CREATE TABLE IF NOT EXISTS projects (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    canonical_path TEXT NOT NULL,
    canonical_path_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    verification_commands JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_projects_canonical_path_key (canonical_path_key)
);

CREATE TABLE IF NOT EXISTS users (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(512) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_users_username (username)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
    token_hash CHAR(43) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    INDEX idx_auth_sessions_user (user_id),
    INDEX idx_auth_sessions_expiry (expires_at)
);

CREATE TABLE IF NOT EXISTS user_settings (
    user_id CHAR(36) PRIMARY KEY,
    theme VARCHAR(16) NOT NULL,
    locale VARCHAR(32) NOT NULL,
    model_endpoint VARCHAR(2048) NOT NULL,
    model_name VARCHAR(200) NOT NULL,
    agent_max_steps INT NOT NULL,
    agent_run_timeout_seconds BIGINT NOT NULL,
    context_limit_chars INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    id CHAR(36) PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    INDEX idx_sessions_project (project_id)
);

CREATE TABLE IF NOT EXISTS snapshots (
    id CHAR(36) PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    fingerprint VARCHAR(255) NOT NULL,
    materialized_path TEXT NOT NULL,
    captured_at TIMESTAMP(6) NOT NULL,
    included_files JSON NOT NULL,
    excluded_files JSON NOT NULL,
    INDEX idx_snapshots_project (project_id)
);

CREATE TABLE IF NOT EXISTS experiments (
    id CHAR(36) PRIMARY KEY,
    project_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    task TEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(48) NOT NULL,
    base_snapshot_id CHAR(36) NULL,
    result_snapshot_id CHAR(36) NULL,
    workspace_path TEXT NULL,
    agent_summary TEXT NULL,
    failure_reason TEXT NULL,
    verification_passed BOOLEAN NULL,
    version BIGINT NOT NULL,
    INDEX idx_experiments_project (project_id),
    INDEX idx_experiments_session_status (session_id, status)
);

CREATE TABLE IF NOT EXISTS evidence (
    id CHAR(36) PRIMARY KEY,
    experiment_id CHAR(36) NOT NULL,
    snapshot_id CHAR(36) NOT NULL,
    kind VARCHAR(64) NOT NULL,
    command TEXT NOT NULL,
    cwd TEXT NOT NULL,
    exit_code INT NOT NULL,
    stdout MEDIUMTEXT NOT NULL,
    stderr MEDIUMTEXT NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NOT NULL,
    duration_millis BIGINT NOT NULL,
    timed_out BOOLEAN NOT NULL,
    trusted BOOLEAN NOT NULL,
    environment_profile VARCHAR(64) NOT NULL DEFAULT 'unknown',
    cancelled BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX idx_evidence_experiment (experiment_id, started_at)
);

CREATE TABLE IF NOT EXISTS run_events (
    event_id CHAR(36) NOT NULL UNIQUE,
    experiment_id CHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    type VARCHAR(96) NOT NULL,
    event_timestamp TIMESTAMP(6) NOT NULL,
    payload JSON NOT NULL,
    PRIMARY KEY (experiment_id, sequence),
    INDEX idx_run_events_experiment (experiment_id, sequence)
);

CREATE TABLE IF NOT EXISTS promotion_journal (
    promotion_id CHAR(36) PRIMARY KEY,
    experiment_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    base_fingerprint VARCHAR(255) NOT NULL,
    candidate_fingerprint VARCHAR(255) NOT NULL,
    candidate_path TEXT NOT NULL,
    touched_files JSON NOT NULL,
    preimage_hashes JSON NOT NULL,
    postimage_hashes JSON NOT NULL,
    phase VARCHAR(32) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    lease_until TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    resulting_fingerprint VARCHAR(255) NULL,
    failure_reason TEXT NULL,
    version BIGINT NOT NULL,
    INDEX idx_promotion_journal_experiment (experiment_id),
    INDEX idx_promotion_journal_open (phase, lease_until),
    INDEX idx_promotion_journal_project_phase (project_id, phase, created_at)
);
