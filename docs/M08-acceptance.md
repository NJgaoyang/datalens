# M08 intelligent analytics and multi-turn conversation

## Scope
Task M08: compose M04/M05/M07 into owner-isolated durable conversations, reviewed natural-language metric selection, clarification, canonical query/chart preview, approved previous-month follow-up and explicit private chart save. Dependencies M04/M05/M06/M07. No new query engine, provider transport, arbitrary SQL rewriting, automatic sharing, streaming or internal reasoning exposure.

Schema: additive V9 conversation/turn tables with unique owner/request and conversation/sequence keys. API: contracts/conversations.md. Semantics adds backward-compatible optional reviewed timeParameters; chart definitions add optional semanticBinding; existing constructors/manual charts remain compatible. Existing migration scripts unchanged.

Security: owner identity from current session, repeated subject/publication rechecks, server-derived immutable definitions, query-purpose guards, durable admission/idempotency, locked save/publication checks, bounded history. Source/semantic revocation prevents stale output/save/refresh. Chart sharing/restore/transfer preserve semantic checks. Model responses never directly select SQL/tool names.

## Acceptance evidence
Executed locally on 2026-11-03:
- Java 21.0.13 compiled every main and test source using cached resolved dependencies. Full `com.datalens.*Test` JUnit run: **341 passed, 0 failed/skipped**. Independent security reviewer repeated the full suite successfully. Explicit Mockito javaagent avoids unavailable sandbox self-attachment.
- Fourteen new ConversationIT cases compile, with real HTTP/session/CSRF/MySQL/Redis persistence paths intended. They cover owner/admin isolation, idempotency/concurrency/recovery and canonical query → preview → last-month follow-up → explicit save → refresh/revocation. ModelGateway and analytics SourcePools JDBC are synthetic mocks. **These integration cases were not run locally** because disposable MySQL/Redis are unavailable.
- Frontend clean Node 22.23.4 offline install, **60 unit tests**, Vue typecheck and production build passed. Existing chunk-size warnings remain. Playwright discovers **73 browser cases** including 10 conversation cases and one provenance edit case; browser execution fails at Chromium IPC startup (EPERM), including permitted escalation. No browser assertions counted as passed.
- Full Maven offline lifecycle attempted using writable cached repository; it fails before compilation because the pinned Spring Boot parent POM is absent. Manual compilation/JUnit is not a Maven package/verify result.
- Independent security review fixes: locked save/publication rechecks, READ_COMMITTED transaction visibility, exact business-description provenance validation, semantic metadata/count filtering, truthful truncated/invalid-chart state, exclusive-query-lease-before-recovery ordering and zero-row planning transition rejection. Conversation creation uses its own lock, not identity-table access.
- No provider call, billing, real StarRocks contact, merge or deployment performed.

Remaining implementation constraints: exact previous-month phrases are recognized deterministically; other modifications use clarification/unchanged reviewed metric selection. Up to 100 turns/conversation and 100 recent conversations are exposed. Metadata filtering scans ACL-visible chart definitions and performs no SQL queries; large asset counts need performance evaluation/optimization in M09/M10. Trash metadata remains owner/admin-only for recovery management. These checks are not production approval.

## Required remaining gates
- Publication is authorized by the user for M07–M10 working-branch source/tests/docs, but still waits for parent coordination and M07 CI acceptance. No merge/deployment authorization.
- Full pinned Maven/CI, actual MySQL/Redis integration, browser concurrent-user scenarios, empty/existing-volume Flyway and Compose startup/recovery require runnable infrastructure.
- No provider contact or credentials installed. Real provider protocol/TLS/billing not tested.
- StarRocks live setup was deferred by user; real query cancellation/timeouts, isolation, data freshness and independently verified business question results remain pending, regardless of mock success.
- M10 independent business evaluation and production readiness are not replaced by this module's tests.
