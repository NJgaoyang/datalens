# M08 intelligent analytics and multi-turn conversation

## Scope
Task M08: compose M04/M05/M07 into owner-isolated durable conversations, reviewed natural-language metric selection, clarification, canonical query/chart preview, approved previous-month follow-up and explicit private chart save. Dependencies M04/M05/M06/M07. No new query engine, provider transport, arbitrary SQL rewriting, automatic sharing, streaming or internal reasoning exposure.

Schema: additive V9 conversation/turn tables with unique owner/request and conversation/sequence keys. API: contracts/conversations.md. Semantics adds backward-compatible optional reviewed timeParameters; chart definitions add optional semanticBinding; existing constructors/manual charts remain compatible. Existing migration scripts unchanged.

Security: owner identity from current session, repeated subject/publication rechecks, server-derived immutable definitions, query-purpose guards, durable admission/idempotency, locked save/publication checks, bounded history. Source/semantic revocation prevents stale output/save/refresh. Chart sharing/restore/transfer preserve semantic checks. Model responses never directly select SQL/tool names.

## Acceptance evidence

## Exact-commit CI acceptance (2026-11-03)

Accepted working-branch head: `f37377d41130a0dac8f014387c264a144d7f080c`. All three workflows are green:
- Backend: https://github.com/NJgaoyang/datalens/actions/runs/41656669075 — full pinned Maven verify/package, **341 unit tests + 87 real MySQL/Redis integration tests**, including 14 ConversationIT cases; no failures/skips.
- Frontend: https://github.com/NJgaoyang/datalens/actions/runs/41656668986 — clean install, **60 unit tests + 73 Chromium browser tests**, typecheck/build.
- Docker: https://github.com/NJgaoyang/datalens/actions/runs/41656669169 — cold empty-database start, Nginx/API/security checks, same-volume recreation, migration/data preservation, Redis failure and recovery.

Initial head `464d16ef72f74149679b26773ea1ec4bf092ef0f` had one test-only mismatch: disabled-account session invalidation also invalidated its CSRF token, so the next POST correctly returned 403 before authentication. Final test verifies stale-token 403 then fresh-anonymous-token 401. Production behavior was unchanged. The full canonical last-month/preview/save/refresh/revocation integration path passed.

These CI results resolve the local Maven/MySQL/browser/Compose environmental limits below. Integration analytics JDBC and ModelGateway remain mocked: no real provider or StarRocks acceptance is claimed. No merge/deployment occurred. This evidence update is local for inclusion in the next module's publication.

Executed locally on 2026-11-03:
- Java 21.0.13 compiled every main and test source using cached resolved dependencies. Full `com.datalens.*Test` JUnit run: **341 passed, 0 failed/skipped**. Independent security reviewer repeated the full suite successfully. Explicit Mockito javaagent avoids unavailable sandbox self-attachment.
- Fourteen new ConversationIT cases compile, with real HTTP/session/CSRF/MySQL/Redis persistence paths intended. They cover owner/admin isolation, idempotency/concurrency/recovery and canonical query → preview → last-month follow-up → explicit save → refresh/revocation. ModelGateway and analytics SourcePools JDBC are synthetic mocks. **These integration cases were not run locally** because disposable MySQL/Redis are unavailable.
- Frontend clean Node 22.23.4 offline install, **60 unit tests**, Vue typecheck and production build passed. Existing chunk-size warnings remain. Playwright discovers **73 browser cases** including 10 conversation cases and one provenance edit case; browser execution fails at Chromium IPC startup (EPERM), including permitted escalation. No browser assertions counted as passed.
- Full Maven offline lifecycle attempted using writable cached repository; it fails before compilation because the pinned Spring Boot parent POM is absent. Manual compilation/JUnit is not a Maven package/verify result.
- Independent security review fixes: locked save/publication rechecks, READ_COMMITTED transaction visibility, exact business-description provenance validation, semantic metadata/count filtering, truthful truncated/invalid-chart state, exclusive-query-lease-before-recovery ordering and zero-row planning transition rejection. Conversation creation uses its own lock, not identity-table access.
- No provider call, billing, real StarRocks contact, merge or deployment performed.

Remaining implementation constraints: exact previous-month phrases are recognized deterministically; other modifications use clarification/unchanged reviewed metric selection. Up to 100 turns/conversation and 100 recent conversations are exposed. Metadata filtering scans ACL-visible chart definitions and performs no SQL queries; large asset counts need performance evaluation/optimization in M09/M10. Trash metadata remains owner/admin-only for recovery management. These checks are not production approval.

## Required remaining gates
- Working-branch publication and automated CI acceptance are complete at the exact head above. No merge/deployment authorization.
- Full pinned Maven/CI, MySQL/Redis integration, browser scenarios and Compose cold/restart/recovery passed on the exact head above.
- No provider contact or credentials installed. Real provider protocol/TLS/billing not tested.
- StarRocks live setup was deferred by user; real query cancellation/timeouts, isolation, data freshness and independently verified business question results remain pending, regardless of mock success.
- M10 independent business evaluation and production readiness are not replaced by this module's tests.
