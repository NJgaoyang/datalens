# M10 integration acceptance and production preparation

## Decision

Development integration and release preparation are implemented; **production acceptance remains BLOCKED**. No deployment, merge, real model call or real StarRocks connection is authorized or performed. The user deferred live StarRocks until development is complete. M00–M09 software evidence must not be equated with real business or production acceptance.

## Task scope

- M10-01: assess complete lifecycle/security/reliability evidence across M00–M09; integrate final M09 report and reconcile baseline documentation.
- M10-02: add query-executor lease-aware readiness, negative regression and disposable metadata restart/recovery drill.
- M10-03: add real MySQL V9→V10 additive migration acceptance and full logical backup/fresh-volume restore equality check; retain existing empty/same-volume and Redis recovery tests.
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
- `python3 -m unittest discover -s tests/acceptance -p 'test_*.py' -v` (9 validator regressions, NOT 100 business cases).
- Local Java 21 full source compilation and cached-classpath unit fallback are supplementary, not Maven/package/integration evidence. Maven locally cannot resolve Maven Central; Docker is unavailable. Local Java21 compilation passed; cached JUnit fallback passed 358 tests, zero failures/skips, and frontend 69 unit tests plus typecheck/build passed. Exact pushed-commit CI is authoritative and will be recorded after execution.

The prior M09 accepted head is `67089355b4be6a8dfb3becf1c77347ad9496b14b`; its 356 unit + 91 integration, 69 frontend unit + 83 browser and Compose results are in `M09-acceptance.md` and do not count as an M10 run.

## Independent review

A separate read-only review identified green readiness after lost exclusive executor lease; fixed by exposing executor availability in readiness and exercising metadata restart/recovery. Other verified boundaries remain explicit release blockers rather than hidden assumptions: lifetime storage growth, chart-list full scans, post-allocation large-cell cap, manual quarantine recovery, external wrapping-key escrow and complete versioned state. See `deploy/production-readiness.md` for exact operational requirements.

The production decision cannot turn green solely because these automated tests pass. The independent business template is intentionally empty; no artificial question count, real performance result, live database cancellation or production-ready assertion is supplied.
