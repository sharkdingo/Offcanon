# Offcanon architecture

## Thesis

**Agents do not edit the project. They run experiments against it.**

The canonical workspace is the user's current project state. An experiment is created from an immutable snapshot, receives an independent workspace, and is allowed to mutate only that workspace. A result is not trusted because the model says `done`; the application runs the configured verification policy and records evidence before a promotion can be prepared.

```text
canonical workspace
        |
        v
temporary Git index + Offcanon object store -> immutable snapshot -> isolated experiment workspace
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
8. Session continuity is an explicit, bounded chain of user intents and run outcomes. A continuation creates a successor Experiment instead of reviving a terminal run; previous filesystem observations and tool results are excluded because they are stale under the new base snapshot.
9. The application-owned SQLite database persists lifecycle events, evidence and promotion journals across restarts. Startup audits open journals and settles interrupted work only after the local execution lease is available. `APPLYING` plus candidate fingerprint can restore `PROMOTED`, a matching base fingerprint restores `VERIFIED`, and ambiguous state becomes `RECOVERY_REQUIRED` until the user reconciles it.
10. The local workspace implementation is application-level isolation, not an OS sandbox. Shell command checks are defense-in-depth guardrails and do not provide complete network, process or filesystem isolation; external side effects remain an explicit limitation. Deployments handling hostile prompts or repositories need an OS/container sandbox and, where required, an egress policy.
11. Snapshot staging writes blobs and trees to the application data directory's `git-objects`; canonical Git objects are alternate read-only inputs. Capturing an experiment cannot add unreachable objects to the user's repository.
12. Local accounts are intentionally small and server-owned. Passwords are stored as versioned PBKDF2-HMAC-SHA256 hashes with per-user salts; bearer tokens are persisted only as SHA-256 digests. Settings persist the user's model endpoint, model name, encrypted API key and bounded run defaults. The API key is never returned by the HTTP API; a connection test accepts a draft key for one request and does not save it.
13. Projects are owned by the authenticated local user. Project reads, session creation/listing and experiment creation/reads all verify ownership through the project boundary before touching mutable state. Flyway owns the SQLite schema history and every schema change is an explicit versioned migration.
14. Task memory is an append-only, typed ledger scoped to a project and Session. Every revision carries its source Experiment, Snapshot and fingerprint; code-related revisions become stale when the canonical fingerprint changes, while the original row remains auditable. Agent-authored revisions are proposals only, and a `VERIFIED_FACT` requires a passing trusted verification result plus trusted Evidence. Memory projection is deterministic and never changes tool permissions or system policy. Each run emits a `RUN_CONFIGURATION_RESOLVED` audit event containing the selected non-secret runtime values and acceptance policy, so later account/project edits do not obscure what the Agent boundary received.

## Workspace Git view

Experiment and disposable verification workspaces are materialized from the selected snapshot and initialized as independent, single-commit synthetic Git repositories. Their `HEAD` exists only to give the agent and project-owned checks a clean baseline for commands such as `git status`, `git diff` and `git show`. It is not a branch or worktree of canonical, and it does not inherit canonical history.

The snapshot ID, sealed base/result bindings and Git-tree fingerprints remain the promotion provenance. Git tracks only the regular-file executable bit (`100644` or `100755`); other local POSIX permission bits are not promotion state. POSIX snapshot staging enables Git's mode comparison even when the repository disables `core.filemode`; filesystems without POSIX attributes cannot author local mode-only changes, so materialized files are necessarily regular at that boundary while the Git-tree fingerprint remains authoritative. Workspace `.git` directories are excluded from snapshots and promotion candidates, so this observation baseline can never become part of canonical.

## Evidence and context

Every shell command produces an observation snapshot and evidence containing its command, cwd, exit status, timeout/cancellation state, timestamps and environment profile. Evidence IDs are insert-only: an exact replay is idempotent and conflicting content is rejected. Agent command evidence is deliberately untrusted. Verification commands are buffered until Offcanon fingerprints the disposable verification workspace; evidence is trusted only when promotion-relevant source files still match the sealed result. A mutating check is retained as invalidated evidence and fails the lifecycle.

The hand-written agent loop enforces a step limit, an overall model/tool deadline, bounded transient-model retries, sequential tool execution, per-response tool-call limits, unique call IDs and repeated-failure termination. It validates provider finish reasons before dispatching side effects. `ContextManager` budgets the canonical serialized message/tool document, hashes that stable representation, and performs deterministic rolling compaction by removing complete old assistant/tool turns. A bounded rolling summary is explicitly marked untrusted historical data; the latest tool turn and fixed policy/task prefix are retained or the run fails clearly. Durable context events include a base Snapshot ID, the hash of the same context sent to the provider, removed-turn identities and bounded tool output. A continuation carries a bounded explicit intent chain (including a failed run's task and outcome), projects the typed Task Memory ledger against the fresh base fingerprint, labels summaries as stale reasoning, and never imports prior tool observations.

## Promotion protocol

Promotion is candidate-first. Offcanon materializes the sealed result, verifies it, records a plan containing touched files and expected preimage/postimage hashes over each file's bytes and tracked Git regular-file mode (`100644`/`100755`), then enters the short project-lock critical section. Under the lock it rejects unresolved older journals, rechecks candidate and canonical fingerprints, validates the plan again, applies each guarded file change, captures canonical, and commits the lifecycle plus journal state.

The database state transitions are paired in short SQLite transactions, but the filesystem apply sits between those transactions. Offcanon therefore does not claim fully atomic multi-file promotion. Its journal and fingerprint reconciliation make completed, unapplied and ambiguous outcomes explicit after failure. A process-local project lock and an application data-directory instance lock prevent two desktop workers from applying changes concurrently.

The Experiment aggregate persists the verification outcome needed for lifecycle decisions. Full per-command provenance is authoritative in the Evidence table and is queried separately; a JDBC-rehydrated Experiment does not duplicate that command list.

Offcanon v1 intentionally has no three-way merge. If current canonical differs from the experiment base, promotion becomes `STALE` even when a textual merge might be possible. This conservative rule is less convenient, but it guarantees that Offcanon will not overwrite a human edit it cannot attribute. Three-way merge plus post-merge semantic verification is future work, not a hidden fallback.

Acknowledging a stale preview is a separate intent from promotion. The stale-confirmation path reads the current canonical fingerprint and may change only a `VERIFIED` experiment to `STALE`; it has no promotion candidate, journal or `PromotionPort` capability. If canonical has returned to the experiment base, the action leaves the experiment `VERIFIED` so a stale dialog can never turn into an unexpected canonical write.

The `POST /api/experiments/{id}/continue` action is the single user-level continuation entry point for completed, rejected, stale, failed and cancelled work. It captures a fresh canonical base first. A sealed result or partial failed workspace is carried into the successor only when that fresh base has exactly the predecessor's base fingerprint; otherwise the successor starts from current canonical and retains only the intent chain. The old Experiment remains terminal and auditable.

## Runtime lifecycle

Runtime directories are disposable materializations, not a second history store. Active and recovery experiments, open or unresolved promotion journals, and every snapshot referenced by a lifecycle row remain protected so historical diff, promotion and continuation stay reproducible. Unreferenced snapshot materializations, terminal mutable workspaces, verification attempts and unprotected promotion candidates follow the configured retention windows; database rows and audit evidence are never deleted by this process. A failed, stale or cancelled run without a sealed result keeps its partial workspace until a durable successor has forked it; after that eviction, the diff endpoint reports that the change set is unavailable rather than presenting an empty diff.

## Modules

The backend is a modular monolith organized by business capability. Domain objects do not depend on Spring, persistence DTOs, model-provider DTOs or Git implementation details. Ports describe change boundaries; adapters implement them.

The desktop deployment has one persistence implementation: SQLite in the application data directory, managed by Flyway. Session execution and promotion coordination use process-local, token-checked locks. A file lock prevents a second Offcanon process from opening the same data directory. There are no storage profiles or external database services to configure.

The local workspace remains application-level isolation rather than an OS security sandbox. Shell policy rejects absolute paths, parent traversal, environment expansion, token-reconstruction metacharacters, nested shells, inline runtime code and common direct network utilities. These checks are defense-in-depth guardrails, not complete egress or hostile-code isolation: project runtimes and scripts may still access resources permitted by the operating system. A deployment that needs hostile-code guarantees must add a container or OS sandbox around the worker.

## Identity and settings

The HTTP surface keeps account state separate from experiment state:

```text
POST /api/auth/register  -> create a local user, default settings and an HttpOnly session cookie
POST /api/auth/login     -> issue another HttpOnly session cookie
GET  /api/auth/me        -> validate the HttpOnly session cookie
POST /api/auth/logout    -> revoke the current HttpOnly session cookie
GET/PUT /api/settings    -> read or update account preferences (key status only)
DELETE /api/settings/model-credential -> explicitly clear the encrypted account model key
POST /api/settings/model-test -> test saved or draft model credentials without persisting them
GET /api/local-directories -> browse local directories for the open-project flow
```

The `OFFCANON_SESSION` HttpOnly cookie is required on project, session, experiment and event endpoints; browser clients never handle bearer tokens. Malformed, expired or revoked sessions return `401`, and a valid session can access only its own project rows. Settings contain theme, locale, an OpenAI-compatible Chat Completions endpoint reference, model name, encrypted API key and bounded agent defaults. The server exposes runtime ceilings as advisory metadata, while every update is validated against those ceilings. The browser treats edits as a draft until `PUT /api/settings` atomically saves preferences and any replacement credential; clearing the credential is an explicit delete action, and a connection test uses draft values without changing saved settings. Account security supports password rotation, and the data section offers a bounded, account-scoped history export that omits passwords/session credentials and model keys and redacts secret-looking output; durable audit rows are never deleted by those actions.

The boundary is intentional: account Settings answer “how should my next run look,” Project metadata answers “which repository and acceptance policy are authoritative,” and an Experiment answers “what actually ran and what evidence was produced.” A registered project's canonical path is immutable. Its acceptance commands are guarded by the project promotion lock and cannot change while any experiment or promotion is active; this prevents a verified result from being reinterpreted under a later policy. Infrastructure details such as the SQLite file location, schema engine, process lock and OS/container sandbox policy are application-owned, while model credentials and bounded run preferences are user-owned.

The directory browser lists the machine running the backend; it is not a browser-native picker for a remote client. It returns directory metadata, resolved paths, a detected containing Git root and heuristic verification-command suggestions. The endpoint requires authentication and currently accepts only direct loopback requests. Project creation treats none of that metadata as trusted: it resolves the path again, requires the exact Git root and applies project ownership/uniqueness rules. Suggested commands are editable input for the user, not pre-verified evidence. A deployment behind a reverse proxy must disable this endpoint or add an explicit authorization boundary instead of treating the proxy's loopback address as a remote-access boundary.
