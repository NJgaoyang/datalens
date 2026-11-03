# M04 private dynamic charts

Task M04 depends on M03. Scope: chart/revision persistence, controlled query previews, explicit save, immutable publication, fresh queries on opening, business-timezone relative dates, all five chart types, truthful errors/freshness and precision. No AI, sharing, export, production deploy or live StarRocks writes.

## Implementation

- Added chart module, V5 migration, chart API contract and frontend routes/API/renderer/editor/list/detail.
- Added server-only query purpose SPI and atomic persisted context. Generic task endpoints and asynchronous execution recheck resource access. Direct requests cannot forge purpose or gain chart access.
- Parameters fixed by definition with a bounded LAST_DAYS rule; revision data contains no results or renderer code. Preview validation checks successful authorized result schema and supported numeric combinations before publication.
- Saves are idempotent by preview. Refreshes persist revision and dates per intent; new intent submits new M03 query. Revisions append atomically under optimistic version checks. List never dispatches.
- M04 intentionally remains owner-only; M05 will add superadmin management and sharing under the fixed permission matrix. No author impersonation.

## Verification

- Local Java 21 compilation and JUnit cached-dependency launcher: 153 unit cases passed in the final local run, 0 skipped/failed. New tests cover business timezone midnight, DST/leap day, exact decimal/BIGINT/null/zero, empty results, invalid mappings, KPI aggregation/truncation refusal, PIE restrictions, resource-bound task revocation and preview proof.
- Independent review fixed refresh-retry publication races and save retry after rename; unit regressions pass. Added real HTTP/session/CSRF lifecycle isolation and revocation scenarios with mocked analytics JDBC. Added MySQL ChartStoreIT: persistence/owner listing, save races, immutable revisions/version conflicts, refresh/date idempotency. Added query context persistence and idempotency integration regression. Integration tests compile; local MySQL/Docker unavailable, exact-commit CI pending.
- Frontend: 36 unit tests, Vue typecheck and production build passed. 11 new Playwright chart scenarios (34 total suite cases) are ready; local Chromium cannot launch under sandbox IPC restrictions even with the approved escalation. Exact-commit browser CI pending.
- Cold-start, V4→V5 migration and same-volume restart to be established in exact-commit CI; existing V1–V4 migrations not modified.

## Remaining external acceptance

M03 real StarRocks 3.3.22 TLS compatibility, actual query/cancel/timeout and resource-budget gates remain open. Mock/metadata MySQL tests do not close them. No live cluster was contacted or secrets requested. M04 real-data repeat-open/failure freshness demo remains subject to that gate. No production readiness claim.
