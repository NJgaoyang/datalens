# M05 frontend verification

Scope: chart visibility and recipients, shared/public/mine/admin/trash lists, owner/admin management, optimistic sharing/delete/restore/transfer, explicit subject sharing policy. Depends on M04 and M05 backend contracts. Only web files changed; no deployment or push. No export or arbitrary viewer query capability added.

Implementation:
- Server `canManage` gates chart editor and management, with no permissive fallback. Viewers retain published metrics/time rules and renderer, never a SQL editor. Super-admin ownership transfer uses the actual session and server validation.
- All mutations carry `expectedVersion`. Deletes require confirmation and navigate to trash; restoring deleted assets does not dispatch a query from the trash list. Deleted details do not refresh. Specific sharing requires recipients; PRIVATE/ALL send an empty recipient list.
- Route/session invalidation aborts requests and clears private state. Late recipient responses cannot reopen the previous chart's management panel. Result rendering waits for the authorized result and a current detail/version check. Denied access clears prior results and management state.
- Subject settings support explicit `ALL_AUTHENTICATED`, alongside `NONE` and `AUTHORIZED_USERS`.

Verification:
- `npm run build`: Vue/TypeScript and Vite production build passed. Existing large-chunk warning remains.
- `npm test`: 37 tests passed in 6 files, including encoded management routes, expected versions, scopes and absence of SQL in sharing calls.
- 20 chart browser scenarios authored (9 new M05), 43 total across existing suites. Browser execution attempted with installed Chromium; all attempts stopped at browser launch before application assertions. Chromium cannot create its IPC socket (`process_singleton_posix.cc`, `Operation not permitted`), including approved escalated retry. Run these tests in supported CI; no browser pass or screenshot verification claimed.
- No real StarRocks, database migration or backend integration checks performed by this frontend task. Mock fixtures are not live integration evidence.
