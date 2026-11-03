# M08 conversation frontend verification

## Scope and dependencies

M08 adds the Vue `/ask` and `/ask/:id` conversation experience, using M01 session authentication, authorized M02 subjects, M04 chart rendering and private assets, M06 reviewed semantic definitions, and the M07/M08 server orchestration. It does not add browser SQL generation, provider access, generated numerical narratives, exports, public conversations, deployment, or production configuration. No package or database schema changes are made by this frontend work.

The new typed API uses the existing response envelope, same-origin session credentials and CSRF. Conversation creation returns a summary; detail includes ordered turns. Turn submission sends only question, current expectedVersion and a stable intent key. Status/result reads use owner-scoped conversation and turn IDs. Explicit save sends name and `confirmation: true`, never browser-supplied SQL or chart definitions. The server remains responsible for authorization, idempotency, semantic provenance and current result access.

## User flows and safeguards

- Create a subject-bound conversation, reopen its history, ask a question, answer clarification, and poll PLANNING/QUERYING to a terminal status.
- Display the reviewed metric description, business time rule/timezone, query completion time, exact result strings, truncation and an explicit no-data state. No numerical conclusion or causal explanation is invented.
- Reuse the existing safe ChartRenderer for tables/KPI/charts. Saving is available only after this session has loaded and validated the turn's preview. Empty but valid previews may be saved for future refresh; no-data remains clearly labeled. Saving is explicit and private, with a direct link to the saved chart.
- Send the exact `改成上个月` follow-up; the server retains the metric and resolves the reviewed calendar rule. The frontend does not reinterpret SQL or calculate date bounds.
- Abort and invalidate requests on route/session changes and unmount. Responses must match the request epoch, actor and route. Clear private questions, metadata, results and names on access failures or account changes. An abandoned response cannot invoke the global unauthorized handler and clear a later session.
- Prevent duplicate mutation clicks; retain the same intent key for an unchanged uncertain submission. Version conflicts require explicit reload before another mutation. A failed/pending/interrupted request does not become success, and old rows are cleared before a new question.
- Keep questions and results in memory only, with no local/session storage or provider calls from the browser.
- Chart editing now supports LAST_MONTH and preserves an optional semanticBinding through preview/publication instead of silently dropping provenance.

Turn POST alone has a bounded 150-second client deadline to accommodate at most two configured 60-second model attempts plus orchestration. Ordinary requests retain their existing 10-second deadline. This timeout is not an automatic retry policy.

## Executed checks

Commands run from `web` with Node v22.23.4 using `PATH=/tmp/datalens-diagnosis-tools/node_modules/node/bin:$PATH`:

- `npm ci --offline --cache /tmp/datalens-npm-cache`: passed, 108 packages from the existing lockfile.
- `npm test`: passed, 9 files and 60 tests on the final frontend changes, including 11 conversation API/validation cases and the abandoned-401 regression.
- `npm run build`: passed, including `vue-tsc --noEmit`. Existing ECharts/Element Plus large-chunk warnings remain.
- `npx playwright test --list`: passed, 73 tests in 8 files discovered. Discovery is not browser execution.

The initial nine conversation browser scenarios were attempted with `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium npm run test:e2e -- e2e/conversations.spec.ts --workers=2`. Chromium failed before any assertions at local IPC `socket() failed: Operation not permitted`. One approved escalated attempt failed at the same system restriction. No browser assertion, visual QA, screenshot or real-backend UI integration is claimed to have passed. No further browser launch workaround was attempted.

The final supplied cases include ten conversation browser scenarios (creation/duplicates, preview/confirmation, clarification/polling/last month, no data/truncation, optimistic conflict, revocation, late-response navigation/Back, session expiry, interruption and uncertain-submission idempotency), plus one chart-edit provenance/LAST_MONTH case. The last two were added after the launch blocker and discovered but not executed.

## Files and remaining gates

Primary implementation: `web/src/api/conversations.ts`, `web/src/views/ConversationView.vue`, `web/src/router.ts`, `web/src/api/http.ts`, `web/src/api/charts.ts`, and `web/src/views/ChartsView.vue`. Tests: `web/src/api/conversations.test.ts`, `web/src/api/http.test.ts`, `web/e2e/conversations.spec.ts`, and the added case in `web/e2e/charts.spec.ts`.

Runnable-browser verification, real API orchestration, live provider/data correctness, exact-commit CI, and production performance remain separate gates. No provider credential was used, no real model provider was contacted, and no push, merge or deployment was performed by this frontend task.

## CI resolution

Exact accepted head `f37377d41130a0dac8f014387c264a144d7f080c`: https://github.com/NJgaoyang/datalens/actions/runs/41656668986 passed clean install, **60 unit tests**, typecheck/build and **73 Chromium browser tests**. This resolves the local browser-launch limitation above. The browser scenarios use controlled API fixtures; real backend MySQL/Redis orchestration is separately covered by backend CI, not live provider/StarRocks compatibility.
