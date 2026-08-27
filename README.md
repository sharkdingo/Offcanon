# Offcanon

Offcanon is an experiment-first coding agent. An agent never receives a writable handle to the canonical project. It works in an isolated experiment workspace, seals an immutable result, and can only be promoted after trusted verification against the current canonical snapshot.

The repository is intentionally being built as a modular monolith with a Java/Spring backend and a Vue/TypeScript workbench. See `docs/architecture.md` for the execution model and invariants.

## Local development

Backend (Java 21 target; Java 22 is used by the current workstation):

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

The real model path uses an OpenAI-compatible `/chat/completions` endpoint. Export the three required values before starting the backend (the process does not automatically load `.env` files):

```powershell
$env:OFFCANON_MODEL_BASE_URL = 'https://api.example.com/v1'
$env:OFFCANON_MODEL_API_KEY = '<local-secret>'
$env:OFFCANON_MODEL_NAME = '<provider-model-id>'
```

See `.env.example` for the optional durable profiles. Keep the real values in the process environment or another untracked local configuration; never commit credentials.

Run the checks with:

```powershell
cd backend
mvn test
cd ..\frontend
npm run build
```

The current vertical slice is intentionally explicit: the agent edits an isolated experiment, seals its result snapshot, runs trusted verification in a disposable workspace, then re-verifies a promotion candidate before changing canonical. Agent shell output is recorded as observation, never presented as trusted verification. A local run can be demonstrated with the built-in scripted tests; a real model run requires `OFFCANON_MODEL_API_KEY`, `OFFCANON_MODEL_BASE_URL`, and `OFFCANON_MODEL_NAME`.

For durable local deployment, activate the optional `mysql` and `redis` profiles and provide their connection settings through environment variables. MySQL uses a bounded Hikari connection pool; Redis coordinates per-session runs and project promotion locks. The default profile is intentionally self-contained and uses in-memory repositories. Keep `OFFCANON_DATA_ROOT` outside every registered repository.

Offcanon isolates application workspaces and Git objects, but it is not an OS sandbox. Agent commands run with the current user's operating-system permissions; use a container or worker sandbox when prompts or repositories are hostile.
