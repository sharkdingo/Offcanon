CREATE TABLE IF NOT EXISTS projects (
    id CHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    canonical_path TEXT NOT NULL,
    verification_commands JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
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
    INDEX idx_evidence_experiment (experiment_id, started_at)
);
