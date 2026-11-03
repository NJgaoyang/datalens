# M10 integration acceptance and production preparation

## Decision

Development integration and release preparation are implemented; **production acceptance remains BLOCKED**. No deployment, merge, real model call or real StarRocks connection is authorized or performed. The user deferred live StarRocks until development is complete. M00–M09 software evidence must not be equated with real business or production acceptance.

## Task scope

- M10-01: assess complete lifecycle/security/reliability evidence across M00–M09; integrate final M09 report and reconcile baseline documentation.
- M10-02: add query-executor lease-aware readiness, negative regression and disposable metadata restart/recovery drill.
- M10-03: pin existing migration bytes to the accepted M09 SHA, add real MySQL V9→V10 additive migration acceptance and full logical backup/fresh-volume restore equality check; retain existing empty/same-volume and Redis recovery tests.
- M10-04: provide offline independent-business-evidence validator, fail-closed empty template and performance protocol; document production gates and backup/rollback operator plan.

Dependencies: M00–M09. Allowed scope: acceptance, health signal, CI, documentation. No new API routes, database migrations, live integrations, new provider dependencies or production settings. Health response stays opaque UP/DOWN; readiness now includes `queryExecutor`.

## Acceptance matrix

| Direction | Automated evidence | Remaining blocking gate |
|---|---|---|
| Business lifecycle | Conversation, preview/save/refresh, sharing/revoke/admin/export HTTP and service suites; frontend browser regressions | Real user/business acceptance on an authorized dataset/provider |
| Isolation | Actor/session/query/chart/conversation/export negative regressions; revoke after queue/result/download | Deployed proxy/cache/session boundary review and adversarial live acceptance |
| Data correctness | Exact numeric/date/null/truncation tests; offline evidence comparator rejects missing/mutated/incomplete suites | ≥100 independently reviewed cases on frozen real dataset, target agreed after freeze; first subject's 20–30 cases and human semantic review |
| Query safety | Fail-closed parser, objects/functions/parameters, purpose binding, concurrency/cancel tests | Actual StarRocks version/TLS/readonly/metadata, database-side cancel/timeout/orphan absence and large-cell allocation behavior |
| Failure recovery | Unit/real metadata state recovery, model failure mocks, Redis outage, lease-loss readiness/process restart | Loaded StarRocks/provider failure drill and reviewed quarantine resolution |
| Deployment/upgrade | Empty/same-volume start, V9→V10 migration with preserved data, full logical restore on fresh volume | Production TLS/secret/network/image review, encrypted backup and key-escrow/PITR, measured RPO/RTO; old-binary rollback rehearsal |
| Performance | Bounded pools/queues/exports and admission regressions | Agreed data volume/concurrency and latency/error targets; measured representative load, chart-list scale, large cells, retention/quotas |
| Supply chain | Exact direct deps, frontend lockfile, clean-install/build/CI | Reviewed dependency/image security scan and immutable base/action digests; current mutable tags are not bit-reproducible |

## Commands and evidence

- `mvn -B -ntp -f server/pom.xml verify -Pintegration` using disposable MySQL/Redis and the dedicated upgrade schema.
- `npm ci && npm test && npm run build && npm run test:e2e` in `web`.
- `bash .github/scripts/compose-smoke.sh` on disposable GitHub-hosted CI only. Its cleanup cannot target normal local/shared environments.
- `python3 -m unittest discover -s tests/acceptance -p 'test_*.py' -v` (12 validator regressions, NOT 100 business cases).
- Local Java 21 full source compilation and cached-classpath unit fallback are supplementary, not Maven/package/integration evidence. Maven locally cannot resolve Maven Central; Docker is unavailable. Local Java21 compilation passed; cached JUnit fallback passed 358 tests, zero failures/skips, and frontend 69 unit tests plus typecheck/build passed. Exact pushed-commit CI is authoritative and will be recorded after execution.

The prior M09 accepted head is `67089355b4be6a8dfb3becf1c77347ad9496b14b`; its 356 unit + 91 integration, 69 frontend unit + 83 browser and Compose results are in `M09-acceptance.md` and do not count as an M10 run.

## Independent review

A separate read-only review identified green readiness after lost exclusive executor lease; fixed by exposing executor availability in readiness and exercising metadata restart/recovery. A separate HTTP integration regression proves db/redis are UP while lease-only failure produces opaque HTTP 503, so generic DB failure cannot falsely satisfy that gate. The business comparator also rejects numeric precision hazards and normalized duplicate questions. Other verified boundaries remain explicit release blockers rather than hidden assumptions: lifetime storage growth, chart-list full scans, post-allocation large-cell cap, manual quarantine recovery, external wrapping-key escrow and complete versioned state. See `deploy/production-readiness.md` for exact operational requirements.

The production decision cannot turn green solely because these automated tests pass. The independent business template is intentionally empty; no artificial question count, real performance result, live database cancellation or production-ready assertion is supplied.

## Initial exact-commit execution (2026-11-03)

Candidate `f4fbd4c36ee5946a8d9ef417d2e2c0606a9124a5` passed all three workflows:
- Backend: https://github.com/NJgaoyang/datalens/actions/runs/41661927192
- Frontend: https://github.com/NJgaoyang/datalens/actions/runs/41661927214
- Disposable Docker: https://github.com/NJgaoyang/datalens/actions/runs/41661927171

The real synthetic MySQL backup→fresh-volume restore, exact full-dump comparison, restored application readiness and MySQL/Redis outage recovery all passed. No real analytical service was involved. Subsequent review strengthens the lease-only HTTP assertion, historical migration-byte check, and comparator precision/question uniqueness tests. Those follow-up changes require their own exact-head CI; the links above do not falsely certify later commits. Current workflow history is the authoritative final status.

## Earlier strengthened-head evidence

Head `3dea22a6a52e598b0a11e7c29f6e924e0d9161fe`:
- Frontend https://github.com/NJgaoyang/datalens/actions/runs/41662695743: 69 unit + 83 Chromium cases passed, typecheck/build passed.
- Docker https://github.com/NJgaoyang/datalens/actions/runs/41662695695: fresh-volume restore and dependency recovery passed.
- Backend https://github.com/NJgaoyang/datalens/actions/runs/41662695778: full pinned Maven verify/package passed: 358 unit + 93 real MySQL/Redis integration tests, zero failures/errors/skips; 12 Python evidence-gate tests passed; migration baseline check passed.

Independent final static review found no remaining material concerns in the M10 fixes. All production blockers in the matrix remain.

The earlier strengthened code head passed all three workflows on 2026-11-03. It is superseded by the accepted hardening code head below. No merge or deployment occurred.

## Further M10 code-solvable hardening
Non-destructive admission quotas, chart-list concurrency admission, and application-side bounded JDBC cell reading passed the final exact-head CI recorded below. See `M10-capacity-hardening.md`; quota defaults are not production sizing. No retention deletion, real service contact, merge or deployment is introduced. Earlier candidate evidence is kept for traceability; only the final accepted head below certifies the follow-up.

Final local hardening check: Java21 compilation of all main/test sources; 404 unit tests passed, zero skips; frontend69 unit tests, typecheck/build; 12 offline validator tests; shell/YAML validation. Independent static review cleared quota transactions/replay, preserved revoke/recovery and fixed-scalar/bounded-stream paths. No local MySQL/Docker or live services were used.

Hardening candidate `b5eee4af099a35f4cbe5d7f0b62985583748b7d0`: frontend69+83 and full Docker restore passed, backend404 unit passed but4 new ChartCapacityIT assertions failed. The tests mutated a field on the Spring proxy instead of configuring its target, so their lowered limits were not active. Corrective change makes ChartCapacity immutable constructor injection, constructs explicit low-limit transactional stores for boundary tests, adds a real Spring-configured proxy regression, and cleans fixture-owned rows even if an assertion fails. This failed candidate is NOT accepted; the corrective head below passed every workflow.

## Accepted hardening code head (2026-11-03)

`61fedfbab3e2a46a7a4e1c863e16be75e8451a8e`, branch `fix/m00-frontend-lock`:

- Backend https://github.com/NJgaoyang/datalens/actions/runs/41666746988: full Maven verify/package, **404 unit + 106 real MySQL/Redis integration tests**, zero failures/errors/skips. All five ChartCapacityIT and seven HistoryCapacityIT cases passed, including real Spring-configured quotas, race boundaries, preservation and model-budget rollback. Twelve offline evidence-validator tests and historical migration-byte checks passed.
- Frontend https://github.com/NJgaoyang/datalens/actions/runs/41666746909: **69 unit + 83 Chromium browser tests**, clean install/typecheck/build passed.
- Docker https://github.com/NJgaoyang/datalens/actions/runs/41666747067: cold start, same-volume restart, Redis recovery, lost-query-lease readiness and controlled process restart, full logical backup equality and fresh-volume restore/readiness passed.

This documentation update changes no runtime source, test, configuration or workflow. Subsequent branch-head workflow results remain independently visible in GitHub Actions.

### What remains blocked, precisely

1. User-deferred live StarRocks version/TLS/metadata/read-only access, cancellation/timeout and database-side orphan checks; real-driver buffering and loaded recovery.
2. Authorized provider/account/payload/budget and a real human-reviewed business subject. At least100 independently reviewed business cases on a frozen dataset; agree correctness target after freezing, then execute and verify. No synthetic fixture is counted as business acceptance.
3. Representative scale inputs and measured latency/error targets, including full authorization-filtered chart scans. Two concurrent scans and admission quotas reduce risk, but do not prove a production SLA or bounded per-request work.
4. Product-approved archival/retention and recovery policy. No new automatic deletion exists; audit/admin recovery history intentionally remains writable. Do not invent a purge duration or delete idempotency, grants, budget or quarantine evidence.
5. Production TLS/network/secrets and supply-chain review, immutable release image/action provenance, encrypted backup/key escrow/PITR and measured RPO/RTO, plus old-application rollback rehearsal and actual deployment authorization.

Therefore development hardening is accepted, but **production acceptance and deployment remain blocked**. No merge, deployment, real provider contact or StarRocks connection occurred.
