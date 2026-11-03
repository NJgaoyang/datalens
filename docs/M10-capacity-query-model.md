# M10 query, conversation and model history admission

## Scope

M10 adds non-destructive admission guardrails to durable query tasks, conversations and model-call history. No rows are removed, expired, reset or rewritten by these limits. They do not establish a retention policy, storage-size guarantee, production capacity or performance SLA. Existing databases above a threshold remain readable and can complete existing work. An administrator must review capacity and explicitly change configuration if further admission is needed; retrying with new keys does not free capacity.

The default limits are deliberately finite development guardrails:

| Property | Default | Scope |
| --- | ---: | --- |
| `datalens.capacity.query.total` | 10000 | All persisted query tasks |
| `datalens.capacity.query.per-actor` | 1000 | Query tasks for one actor |
| `datalens.capacity.conversation.total` | 1000 | All persisted conversations |
| `datalens.capacity.conversation.per-actor` | 100 | Conversations for one owner |
| `datalens.capacity.model.total` | 10000 | All persisted model calls |
| `datalens.capacity.model.per-actor` | 1000 | Model calls for one actor |

Each setting is a positive Java integer. Missing settings use the defaults. Zero, negative, nonnumeric or out-of-range values fail application configuration rather than silently disabling protection. Explicit environment mappings are maintained in `application.yml`. Configure the same limits across application instances. Lowering a limit does not delete or invalidate stored rows.

Ten thousand results at the existing two-million-byte query result cap could retain roughly 20 GB of result payload alone, before SQL, parameters, indexes and other metadata. Conversation histories still permit up to 100 turns per existing conversation. These thresholds must be set from representative measurements and available storage, not treated as a sizing recommendation.

## Admission and errors

`QueryStore.create` takes `query.lock`, resolves durable idempotency or conflict first, and then checks global and actor counts before any insert or audit. Quarantined, failed, cancelled and successful tasks all count. Existing concurrency and source-quarantine protections remain in effect. Capacity refusal uses HTTP 429 / `QUERY_HISTORY_CAPACITY`.

`ConversationStore.create` takes `conversation.lock`, resolves durable idempotency or conflict first, and checks global and owner counts before insertion. Capacity refusal uses HTTP 429 / `CONVERSATION_HISTORY_CAPACITY`. Existing conversations can admit further turns up to the pre-existing 100-turn bound. Turn completion and recovery do not consult the creation quota.

`ModelStore.reserve` acquires `model.lock`, checks global and actor history, and only then acquires/updates the current daily budget and inserts the call, within one `REQUIRES_NEW`, `READ_COMMITTED` transaction. Capacity refusal uses HTTP 429 / `MODEL_HISTORY_CAPACITY`; it creates no budget reservation and makes no provider request. A later insertion failure rolls the reservation back. Model calls have no idempotent replay API, and no new retry/refund semantics are introduced. Existing conservative non-refund behavior after actual provider reservation remains unchanged.

The only application caller of `reserve`, `ControlledModelGateway.generate`, has no surrounding transaction and holds no model configuration lock. Lock order is module lock, then budget row. Do not invoke `reserve` from a transaction already holding `model.lock`: suspending that transaction for `REQUIRES_NEW` would self-block. Configuration saves use the model lock but never reserve calls. `finish` acquires neither a quota lock nor an admission check.

## Atomicity and preserved operations

Counts are performed only for fresh admission, in the same transaction as insertion and under the existing module lock. Query and conversation transactions explicitly request `READ_COMMITTED`; model reservation declares it on its independent transaction. Counts use a bounded derived selection (`LIMIT` the configured threshold), using the primary/actor/owner indexes rather than materializing history in application memory. They do not scan all modules on each HTTP request. Missing counts or database failures fail closed before admission.

Query result reads, query start/finish/cancel/recovery, existing conversation reads/turn updates and model finish do not enforce history capacity. Idempotent query/conversation replay returns the original record, including when configured limits are subsequently lowered below persisted usage. Conflicting replays remain conflicts. Authorization rechecks and all prior constraints remain applicable.

Cross-module workflows remain staged. A conversation turn or chart preview/run may already exist when downstream model/query capacity rejects admission. No cleanup or side-effect replay is introduced to conceal this; existing durable failure/retry behavior applies.

## Tests and limits of evidence

`QueryCapacityTest`, `ConversationCapacityTest` and `ModelCapacityTest` cover positive configuration, global/actor boundaries, replay-before-count, fail-closed count handling, transaction settings, model lock/budget ordering and terminal-operation exemptions.

`HistoryCapacityIT` uses the actual disposable MySQL application context, never a provider or analytics execution. It covers one-remaining-slot races across eight actors, per-actor limits, replay/conflict, retained rows after constructing stores with lower limits, terminal/cancel/recovery behavior, continued conversation turns, model budget preservation on quota refusal and budget rollback on duplicate insertion. It must run with the repository integration harness and its disposable dependencies; mocked unit success does not establish MySQL locking behavior.

Audit events, chart-management history, permitted administrative revision growth and other operational storage still require separately approved capacity/retention planning. These changes do not close the overall production lifetime-storage release gate.

Local implementation verification (2026-11-03): all current main/test Java sources compiled with JDK 21.0.13 and the cached dependency classpath. The JUnit Platform unit-only runner completed 399 tests with zero failures, including all 18 new query/conversation/model quota unit tests. The seven `HistoryCapacityIT` MySQL tests compiled but were not run locally because disposable MySQL/Redis are unavailable. Exact-commit Maven/integration CI remains required; this local cached-classpath run does not replace it.
