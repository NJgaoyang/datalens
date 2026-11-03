# M09 workbench, search and controlled exports

## Scope
M09-01 integrates navigation and literal chart-name search across personal, specifically shared, all-authenticated and admin assets. Metadata/counts follow current policies without executing chart SQL. M09-02 provides separate administrator-managed named-user export grants and bounded durable CSV jobs for an existing successful viewer-owned refresh. M09-03 exposes administrator audit records and tests security boundaries.

Dependencies: M05 shared charts and M08 conversation composition. No new database query mode, anonymous export links, spreadsheet engine, production deployment, merging or real provider/StarRocks authorization is introduced.

## Schema / API
V10 adds `chart_export_grant`, `chart_export_job`, and an export admission lock; V1–V9 unchanged. The private artifact is a bounded MySQL blob, not public storage. Contracts: `contracts/exports.md`. Any chart-version mutation invalidates export grants; the administrator must regrant for the new version. Revoke/regrant increments generation, so old artifacts cannot revive. Current source policy and original source-version binding are checked at status/download as well as build.

## Checks
- Added SafeCsvTest: formula injection in headers/cells, quoting, null and exact decimal strings, truncation and byte limits.
- Added ExportServiceTest: no implicit view/owner grant, actor/auth-version binding, expiry, revoked/regranted grant, sharing/publication changes, policy revocation, source revision change and final delivery recheck, admin-only grant changes.
- Added ChartSearchTest: denied metadata excluded before count/pagination, final revocation and no query execution.
- Added ExportStoreIT: MySQL durable idempotency/conflict, single claim, restart interruption, expiry cleanup, grant generation, successful content and capacity.
- Local Maven attempted but blocked by unavailable configured Maven repository proxy (127.0.0.1:41443); Java 21 compilation of every main/test source passed against cached resolved dependencies; explicit Mockito-javaagent JUnit fallback passed **356/356 unit tests**, zero skipped. This is not a Maven verify/package result. Exact-commit CI remains authoritative for full Maven/MySQL/Redis/browser/Compose checks.

## Remaining boundaries
CSV is a bounded export of an already-completed refresh, not an unbounded full-dataset export. Truncated results require narrowing filters and refreshing. Data already downloaded cannot be remotely recalled; revocation blocks subsequent downloads. Encryption, backup retention, database storage quotas and TLS remain deployment responsibilities. The single-instance query lease is the current deployment boundary. Real business correctness, StarRocks TLS/cancellation/performance, external model and production deployment are not established by these tests.

## Independent security review
Review identified and closed original-query source-version reauthorization on export delivery, ordinary chart metadata filtering after policy revocation, final detail policy recheck, and duplicate queued-request coalescing. Regression tests cover these fixes. Two new MySQL ExportStoreIT cases and two HTTP/session/CSRF ChartSharingIT export cases compile but were not run locally; exact-commit remote CI is required.

Frontend: 69 unit tests, Vue typecheck and production build passed. Ten new workbench/export browser regressions added (83 total browser cases); local Chromium is unavailable and no browser pass is claimed before CI.

## Exact-commit CI acceptance (2026-11-03)

Accepted working-branch head: `67089355b4be6a8dfb3becf1c77347ad9496b14b` on `fix/m00-frontend-lock`.
- Backend https://github.com/NJgaoyang/datalens/actions/runs/41659689414: full pinned Maven verify/package, **356 unit + 91 real MySQL/Redis integration tests**, zero failures/errors/skips. Both new HTTP export lifecycle cases and both durable store cases passed.
- Frontend https://github.com/NJgaoyang/datalens/actions/runs/41659688862: clean install, **69 unit + 83 Chromium browser tests**, typecheck/build; no failed/flaky browser cases in the final run.
- Docker https://github.com/NJgaoyang/datalens/actions/runs/41659688756: cold start with ten migrations, security headers and API checks, same-volume restart/data preservation, Redis failure/recovery.

Initial head `d39e7e727dd46f780f09f29e8f31ca8fff42cb71` exposed missing explicit default-deny security route admissions, outdated settings/module-count assertions and two legacy root-redirect expectations. Corrective head adds precise authenticated/admin export/audit matchers, updates stage/count tests and stabilizes navigation before browser Back. Business/source/recipient authorization remains enforced by application services. Independent security review findings were fixed before initial publication.

The full CI results supersede the local Maven/MySQL/browser environmental limitations above. Analytics JDBC remains a deterministic mock; no real StarRocks/provider/business dataset acceptance or production deployment is claimed. No merge/deployment occurred. This evidence update is local for inclusion in M10 publication.
