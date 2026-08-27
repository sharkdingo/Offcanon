# PICO

PICO is an experiment-first coding agent. An agent never receives a writable handle to the canonical project. It works in an isolated experiment workspace, produces observable evidence, and can only be promoted after trusted verification against the current canonical snapshot.

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

For durable local deployment, activate the optional `mysql` and `redis` profiles and provide their connection settings through environment variables. The default profile is intentionally self-contained and uses in-memory repositories.
