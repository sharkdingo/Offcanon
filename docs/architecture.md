# PICO architecture

## Thesis

**Agents do not edit the project. They run experiments against it.**

The canonical workspace is the user's current project state. An experiment is created from an immutable snapshot, receives an independent workspace, and is allowed to mutate only that workspace. A result is not trusted because the model says `done`; the application runs the configured verification policy and records evidence before a promotion can be prepared.

```text
canonical workspace
        |
        v
temporary Git index -> immutable snapshot -> isolated experiment workspace
                                                   |
                                                   v
                                         model / tools / evidence
                                                   |
                                                   v
                                      trusted verification -> promotion candidate
                                                   |
                                                   v
                                      current-snapshot check -> canonical apply
```

## Invariants

1. Agent write-capable tools are scoped to an experiment workspace. They never receive the canonical path as a writable capability.
2. A snapshot has immutable identity. Its base cannot silently change while an experiment runs.
3. A verification result is tied to experiment, snapshot, command, cwd, timestamps and exit code.
4. `AGENT_COMPLETED` and `VERIFIED` are different states.
5. Promotion is prepared outside the critical section, then re-checks the canonical fingerprint immediately before apply. A stale or failed candidate leaves canonical unchanged.
6. The local workspace implementation is application-level isolation, not an OS sandbox. External side effects remain an explicit limitation.

## Modules

The backend is a modular monolith organized by business capability. Domain objects do not depend on Spring, persistence DTOs, model-provider DTOs or Git implementation details. Ports describe change boundaries; adapters implement them.

The first implementation uses in-memory repositories so the execution model can be tested without external services. MySQL durability and Redis promotion coordination will be added behind the same ports; they are infrastructure choices, not domain rules.
