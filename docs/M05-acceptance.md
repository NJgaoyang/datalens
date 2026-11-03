# M05 chart sharing and administrator management

Task: M05. Dependency: M04. Scope: chart ACL, sharing policy, administrator edit/delete/restore/transfer, frontend, migration, contracts, actual-actor audit and revocation tests. No model integration, export, merge, production deployment or live StarRocks writes.

## Implementation and acceptance scenarios

- PRIVATE, SPECIFIC and authenticated ALL viewing; only owner/admin may manage. Unified ChartPermissionService, server session identity and CSRF.
- Sharing checks owner query/field/source scope and explicit subject sharing policy. Published SQL and resolved parameters are server-bound to a viewer-owned run; result sharing does not authorize free SQL or export.
- Query task/audit actor remains actual viewer. Only datasource authorization borrows current owner policy. Every generic status/result/cancel and chart result call reauthorizes. Queued execution and pre-persistence also check owner/viewer validity, sharing and source policy.
- SQL/subject/parameters and recipient lists are redacted from view-only responses. Detail detects ownership/version races before delivery.
- Admin publication validates unchanged owner scope under the mutation lock and records actual admin as revision creator/audit actor. Publication clears sharing.
- Soft-delete/restore and admin-only transfer use expectedVersion. Restore/transfer reset PRIVATE and create a fresh immutable revision so old task results cannot revive. Transfer validates active target's exact query scope.
- V6 changes only sharing schema and management audit snapshots; V1–V5 untouched. No hard-delete API. Rollback: stop app, back up MySQL, restore the complete pre-V6 backup and matching previous app; do not manually drop ACL tables or edit Flyway history. Forward fix preferred.
- Lists and counts use the same permission predicate, never execute chart SQL. Recipient picker exposes only active account ID/username (up to 1000); larger directories need later paged search.

## Verification before push

- Full Java 21 production/test compilation passed with cached dependencies.
- Cached JUnit full unit run: 175 tests passed, 0 failed/skipped. Explicit Mockito agent required locally. Covered direct privilege matrix, stale revision, exact SQL/parameter bindings, source/owner policy, admin owner-scope publication, detail transfer race, immutable run/save retries and query execution identity.
- Frontend `npm test`: 37 tests passed; `npm run build` / TypeScript passed. 43 browser scenarios discovered, including nine new M05 scenarios.
- Added ChartSharingIT with 10 real HTTP/session/CSRF/MySQL cases: shared BIGINT result without query privilege; all/private; in-flight sharing revoke before JDBC return; generic status/result/cancel denial; owner/viewer disablement; source-share policy revoke; admin audit; delete/restore; transfer; stale version conflict. Analytics JDBC only is mocked. All IT classes compile.
- Local Maven offline cannot resolve Boot 4.0.9 parent. Local MySQL/Docker are unavailable. Chromium launch cannot create IPC sockets even after approved escalation, so no local browser assertions ran. Exact-commit CI is required before marking those gates passed.

## Independent review fixes

Owner-scope validation added to admin publication; detail rejects concurrent ownership/revision/version changes; restoration test corrected to assert a new revision; exact in-flight chart ACL revocation added rather than relying on post-success or source-revocation cases. Existing M04 private-chart HTTP test updated for newly permitted administrator viewing.

## Outstanding external gates

Real StarRocks 3.3.22 TLS, execution/cancellation/timeout/resource limits and real-data reopen/freshness remain pending from M02–M04. Passing mocked analytics and MySQL CI cannot establish them. M05 code completion is not production readiness.
