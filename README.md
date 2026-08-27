# PICO

PICO is an experiment-first coding agent. An agent never receives a writable handle to the canonical project. It works in an isolated experiment workspace, seals an immutable result, and can only be promoted after trusted verification against the current canonical snapshot.

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

The model key is read from the environment only. Never commit credentials.

Run the checks with:

```powershell
cd backend
mvn test
cd ..\frontend
npm run build
```

The current vertical slice is intentionally explicit: the agent edits an isolated experiment, seals its result snapshot, runs trusted verification in a disposable workspace, then re-verifies a promotion candidate before changing canonical. Agent shell output is recorded as observation, never presented as trusted verification. A local run can be demonstrated with the built-in scripted tests; a real model run requires `PICO_MODEL_API_KEY`, `PICO_MODEL_BASE_URL`, and `PICO_MODEL_NAME`.

For durable local deployment, activate the optional `mysql` and `redis` profiles and provide their connection settings through environment variables. The default profile is intentionally self-contained and uses in-memory repositories. Keep `PICO_DATA_ROOT` outside every registered repository.
