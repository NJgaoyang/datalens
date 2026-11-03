# M09 frontend verification

## Implemented scope

- Authenticated `/workspace` landing with permission-filtered asset metadata and counts; listing never executes chart SQL. Login now lands here. Existing `/` retains system diagnostics without claiming unverified external-service health.
- Optional literal title search within each chart scope, URL-backed search state, reset pagination and cancellation of obsolete requests. Permission failure/logout clears lists and counts.
- Admin-only navigation and paginated safe audit projection at `/audit`.
- Independent export permission display, explicit admin grant/revoke controls, successful-current-result export only, truncated-result rejection, durable job polling and explicit authenticated CSV download.
- Requests use server identity, chart/task bindings and stable creation keys. Downloads reject redirects, non-CSV/error bodies and payloads over 8 MB; files are never accessed through anonymous URLs. Navigation/logout/unmount cancel pending reads and prevent stale download delivery.
- No new dependencies, browser storage, model calls, SQL execution paths or deployment changes.

## Checks on 2026-11-03

Using Node 22.23.4 from the existing local runtime:

- `npm run build`: passed Vue typecheck and production build; existing large-chunk warnings remain.
- `npm test`: 69 tests passed across 11 files.
- `npm run test:e2e -- --list`: 83 tests discovered across 9 files, including 10 new M09 browser scenarios.
- `npm run test:e2e -- e2e/workbench.spec.ts --workers=1 --max-failures=1`: browser launch failed before assertions because pinned Playwright Chromium was unavailable. No browser tests or screenshots are claimed as passed. Parent reported prior sandbox Chromium socket restrictions and directed browser validation to CI; no bypass attempted.

New automated coverage includes scoped/encoded search, export creation binding and idempotent requests, separate grant routes/version payloads, exact CSV bytes, permission rejection, login cleanup, invalid MIME response, payload bounds, aborted download, and safe audit pagination. Browser cases cover metadata-only lists, hidden results, independent grants, export/download, revoked downloads, truncation, canceled polling, grant management, admin navigation and stale search responses.

Backend authorization, CSV sanitization, real HTTP integration and exact-commit CI remain separate gates. Mock API/unit passes do not establish real browser or StarRocks correctness. No remote publication was performed by this worker.
