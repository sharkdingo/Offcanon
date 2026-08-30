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

The real model path uses an OpenAI-compatible `/chat/completions` endpoint. Export the server-side API key before starting the backend (the process does not automatically load `.env` files). Authenticated users can edit the endpoint and model name in Settings, and can test the connection from there; a non-empty Settings endpoint must be present in the server allowlist:

```powershell
# Optional deployment defaults (Settings can override these values).
$env:OFFCANON_MODEL_BASE_URL = 'https://api.example.com/v1'
$env:OFFCANON_MODEL_API_KEY = '<local-secret>'
$env:OFFCANON_MODEL_NAME = '<provider-model-id>'
# Optional: explicitly allow additional Settings endpoint values.  These
# endpoints receive the same server-side API key.
$env:OFFCANON_MODEL_ALLOWED_BASE_URLS = 'https://second-provider.example/v1'
```

The Settings API intentionally never accepts or returns `OFFCANON_MODEL_API_KEY`. The browser only sees a configured/not-configured status; the backend reads the key from its process environment when it sends a model request. Keep the real values in the process environment or another untracked local configuration; never commit credentials.

Settings follow the ownership boundary used by the product: account Settings contain appearance, language, model endpoint/name references, and bounded run defaults; a Project contains its canonical repository identity and project acceptance commands; an Experiment records the task, snapshots, evidence, and run lifecycle. Deployment secrets, endpoint allowlists, data roots, command/model timeouts, retry/retention settings, and OS/container policy are server-owned and are not editable in the browser. Settings edits are drafts until saved, and a model connection test is explicitly against the current draft. Project acceptance commands cannot change while an experiment or promotion is active, so evidence remains attributable to one policy.

Run the checks with:

```powershell
cd backend
mvn test
cd ..\frontend
npm run build
```

The current vertical slice is intentionally explicit: the agent edits an isolated experiment, seals its result snapshot, runs trusted verification in a disposable workspace, then re-verifies a promotion candidate before changing canonical. Agent shell output is recorded as observation, never presented as trusted verification. Session continuation uses an append-only typed memory ledger: agent notes remain proposals, while verified facts carry a source snapshot fingerprint and trusted evidence. A local run can be demonstrated with the built-in scripted tests; a real model run requires `OFFCANON_MODEL_API_KEY` plus either deployment defaults or per-user Settings for the endpoint and model name.

For durable local deployment, activate the optional `mysql` and `redis` profiles and provide their connection settings through environment variables. MySQL uses a bounded Hikari connection pool; Redis coordinates per-session runs and project promotion locks. The default profile is intentionally self-contained and uses in-memory repositories. Keep `OFFCANON_DATA_ROOT` outside every registered repository.

Offcanon isolates application workspaces and Git objects, but it is not an OS sandbox. Agent commands run with the current user's operating-system permissions; use a container or worker sandbox when prompts or repositories are hostile.
