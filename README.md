# Offcanon

Offcanon is a local, experiment-first coding agent. It never gives an agent a writable handle to the canonical repository. Work happens in an isolated experiment workspace, the result is sealed, trusted checks run against that result, and only then can the user promote it to the canonical project.

## Storage

Offcanon is a single-user desktop service with one application-owned SQLite database. On first start it creates:

```text
%USERPROFILE%\.offcanon\
  offcanon.sqlite       durable metadata and audit history
  secret.key            local AES-GCM key for account model credentials
  experiments\          disposable experiment workspaces
  snapshots\            immutable snapshot materializations
  git-objects\          isolated Git object storage
  verification-workspaces\
  promotion-candidates\
```

SQLite is the only persistence engine; there are no storage profiles, external database services, `.env` files, or user-facing environment-variable configuration. SQLite is opened with WAL mode, foreign-key enforcement, and a busy timeout. Flyway owns the schema version; a new schema change is an explicit migration under `backend/src/main/resources/db/migration`.

The database stores users, sessions, account settings, projects, experiments, evidence, run events, task-memory revisions, snapshots and promotion journals. Snapshot/workspace bytes remain on the local filesystem because they are large, disposable artifacts rather than relational data. The canonical repository remains in the directory selected by the user.

## User Configuration

Everything a normal user needs is available in the Settings screen:

- model Endpoint and model name;
- model API key, encrypted at rest and never returned to the browser;
- theme, language, and bounded run defaults.
 - password rotation, non-secret history export, and cleanup of rebuildable runtime files.

The server validates the Endpoint as an HTTP(S) base URL for OpenAI-compatible Chat Completions and appends `/chat/completions`; it sends the account's key only to that saved Endpoint. Data-directory and database-engine details are application-owned so a user does not need to install or administer infrastructure.

## Run Locally

Backend (Java 21 target; Java 22 is supported by the current workstation):

```powershell
$env:JAVA_HOME = 'D:\jdk\jdk-22'
cd backend
mvn spring-boot:run
```

Frontend:

```powershell
cd frontend
npm install
npm run dev
```

Run checks:

```powershell
cd backend
mvn test
cd ..\frontend
npm run build
```

The current vertical slice is intentionally explicit: the agent edits an isolated experiment, seals an immutable result, runs trusted verification in a disposable workspace, then re-verifies a promotion candidate before changing canonical. Agent shell output is recorded as observation, never presented as trusted verification. Session continuation uses an append-only typed memory ledger: agent notes remain proposals, while verified facts carry source snapshot and evidence provenance.

Offcanon provides application-level isolation, not an operating-system sandbox. Agent commands run with the current user's permissions; use a container or worker sandbox when prompts or repositories are hostile.
