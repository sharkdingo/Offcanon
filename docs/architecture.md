# PICO architecture

## Thesis

**Agents do not edit the project. They run experiments against it.**

The canonical workspace is the user's current project state. An experiment is created from an immutable snapshot, receives an independent workspace, and is allowed to mutate only that workspace. A result is not trusted because the model says `done`; the application runs the configured verification policy and records evidence before a promotion can be prepared.

```text
canonical workspace
        |
        v
temporary Git index + PICO object store -> immutable snapshot -> isolated experiment workspace
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
5. Agent completion seals a result snapshot. Verification and promotion candidate creation use that immutable result, so later changes to the live experiment workspace cannot silently enter canonical.
6. Promotion candidates are independently verified, then re-check the canonical fingerprint immediately before apply. A stale or failed candidate leaves canonical unchanged.
7. A canonical Git root has one project identity. Real-path normalization plus a repository uniqueness constraint prevents aliases from acquiring different promotion locks for the same directory.
8. Session continuity carries only the previous user intent and agent summary, with the prior experiment and snapshot as provenance. Previous filesystem observations and tool results are excluded because they are stale under the new base snapshot.
9. In the MySQL profile, lifecycle events, evidence and promotion journals survive process restart. Startup immediately audits every open journal. Active leases are reported and keep the project blocked; expired work is claimed under the project lock. `APPLYING` plus candidate fingerprint can restore `PROMOTED`, base fingerprint restores `VERIFIED`, and ambiguous state becomes `RECOVERY_REQUIRED`. A manual reconcile can close that state only after the canonical fingerprint exactly matches the recorded base or candidate.
10. The local workspace implementation is application-level isolation, not an OS sandbox. External side effects remain an explicit limitation.
11. Snapshot staging writes blobs and trees to `PICO_DATA_ROOT/git-objects`; canonical Git objects are alternate read-only inputs. Capturing an experiment cannot add unreachable objects to the user's repository.

## Evidence and context

Every shell command produces an observation snapshot and evidence containing its command, cwd, exit status, timeout/cancellation state, timestamps and environment profile. Evidence IDs are insert-only: an exact replay is idempotent and conflicting content is rejected. Agent command evidence is deliberately untrusted. Verification commands are buffered until PICO fingerprints the disposable verification workspace; evidence is trusted only when promotion-relevant source files still match the sealed result. A mutating check is retained as invalidated evidence and fails the lifecycle.

The hand-written agent loop enforces a step limit, an overall model/tool deadline, bounded transient-model retries, sequential tool execution, per-response tool-call limits, unique call IDs and repeated-failure termination. It validates provider finish reasons before dispatching side effects. The latest tool turn is compacted within a fixed budget before older complete turns are evicted; it is never silently dropped. Durable context events include a base snapshot ID and the hash of the same compacted context sent to the provider. On a later experiment in the same session, PICO carries forward only intent and summary; it labels the summary as stale reasoning that must be checked again.

## Promotion protocol

Promotion is candidate-first. PICO materializes the sealed result, verifies it, records a plan containing touched files and their expected preimage/postimage hashes, then enters the short project-lock critical section. Under the lock it rejects unresolved older journals, rechecks candidate and canonical fingerprints, validates the plan again, applies each guarded file change, captures canonical, and commits the lifecycle plus journal state.

The database state transitions are paired in short transactions in the MySQL profile, but the filesystem apply sits between those transactions. PICO therefore does not claim fully atomic multi-file promotion. Its journal and fingerprint reconciliation make completed, unapplied and ambiguous outcomes explicit after failure.

The Experiment aggregate persists the verification outcome needed for lifecycle decisions. Full per-command provenance is authoritative in the Evidence table and is queried separately; a JDBC-rehydrated Experiment does not duplicate that command list.

PICO v1 intentionally has no three-way merge. If current canonical differs from the experiment base, promotion becomes `STALE` even when a textual merge might be possible. This conservative rule is less convenient, but it guarantees that PICO will not overwrite a human edit it cannot attribute. Three-way merge plus post-merge semantic verification is future work, not a hidden fallback.

## Modules

The backend is a modular monolith organized by business capability. Domain objects do not depend on Spring, persistence DTOs, model-provider DTOs or Git implementation details. Ports describe change boundaries; adapters implement them.

The default profile uses in-memory repositories so the execution model can be tested without external services. The `mysql` profile supplies pooled JDBC repositories and initializes `schema-mysql.sql`; the `redis` profile supplies leased, token-checked session and promotion locks. A persistent active-session check backs the Redis admission rule, so a lost key cannot authorize a second run. Both adapters sit behind the same ports and can be enabled independently of the domain rules:

```powershell
$env:SPRING_PROFILES_ACTIVE = "mysql,redis"
$env:PICO_MYSQL_URL = "jdbc:mysql://localhost:3306/pico?createDatabaseIfNotExist=true&serverTimezone=UTC"
$env:PICO_MYSQL_USERNAME = "pico"
$env:PICO_MYSQL_PASSWORD = "..."
$env:PICO_REDIS_HOST = "localhost"
```

The local workspace remains application-level isolation rather than an OS security sandbox. Shell policy rejects absolute paths, parent traversal, environment expansion, token-reconstruction metacharacters, nested interpreters and network utilities; child processes inherit only an explicit build-runtime environment allowlist. A deployment that needs hostile-code guarantees must add a container or OS sandbox around the worker. MySQL/Redis provide durable storage and renewable advisory coordination, but a multi-instance deployment still needs a shared data root and distributed cancellation. An active journal lease is detected immediately after restart but is not force-claimed until expiry, because ownership loss cannot otherwise be proven safely.
