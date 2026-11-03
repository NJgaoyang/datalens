# M08 conversations

Session principal and CSRF apply to all endpoints under `/api/v1/conversations`. No acting user ID, SQL, plan, purpose capability or provider response is accepted from clients. Histories are durable MySQL records owned by one principal, never shared global model memory.

- POST root `{subjectId,title,idempotencyKey}` returns `{id,subjectId,title,version}`. Same owner/key/content returns original conversation; changed content conflicts.
- GET root returns up to 100 newest accessible own summaries. Unavailable subject titles are excluded.
- GET `/{id}` returns summary fields and ordered `turns`.
- POST `/{id}/turns` `{question,expectedVersion,idempotencyKey}` returns a Turn. At most 100 turns/conversation. One PLANNING/QUERYING turn at a time; stale version or changed retry content returns 409. Admission commits before any provider call. A duplicate returns existing persisted status and does not repeat model expenditure.
- GET `/{id}/turns/{turnId}` returns status and validated metadata; poll while QUERYING.
- GET `/{id}/turns/{turnId}/result` returns existing M03 Result through M04 preview validation.
- POST `/{id}/turns/{turnId}/save` `{name,confirmation:true}` explicitly saves the immutable preview via M04 ChartService and returns ChartSummary. Saving is private and idempotent for that preview/name. Different names on retry conflict. This never shares a chart.

Turn: id, sequence, question, status, clarification, metricDescription, previewId, taskId, chartId, errorCode, createdAt, spec, timeRule, queryTime, truncated. Nullable fields are absent work, not fake success. Status: PLANNING, CLARIFICATION_REQUIRED, QUERYING, READY, NO_DATA, FAILED or INTERRUPTED. No rows means NO_DATA; no narrative conclusion is generated. Results expose truncation and query finish time. Chart validation failures do not become a successful preview.

Model planning currently runs synchronously in the initiating HTTP request, with existing bounded gateway concurrency/deadlines/budget. Persistent admission permits concurrent retries to observe PLANNING. Restart marks PLANNING as INTERRUPTED, never automatically repeats a possibly billed operation. Query work uses the existing bounded M03 executor/recovery. Streaming and chain-of-thought are not exposed.

A model can choose one unchanged reviewed PUBLIC metric or ask for clarification. Context is bounded owner-local prior question/clarification or confirmed metric ID, never rows, SQL, credentials, physical mappings or arbitrary provider responses. Exact follow-up `改成上个月` or `上个月` uses the most recent approved metric definition, its immutable publication and approved time parameter contract, bypassing the provider. No SQL rewrite occurs. Unsupported/unreviewed time contracts ask for clarification. Calendar boundaries use the reviewed business timezone and half-open previous-month range. DATE and Instant TIMESTAMP parameter semantics are documented in semantics/charts contracts.

Definitions carry optional semanticBinding `{publicationVersion,digest,scopeFingerprint,metricId}`. Semantic-bound definitions must match current eligible publication, unchanged reviewed SQL/base parameters, centrally generated business description and NONE or the exact approved LAST_MONTH rule. Preview, query dispatch/persistence/delivery, explicit save, publication, sharing/transfer/restore and saved refresh recheck it. Republishing cannot silently remove provenance. Shared execution uses the chart owner's permitted source scope under M05, not the viewer's arbitrary query rights. Saved refresh has no model dependency, but semantic withdrawal/republication invalidates old provenance until re-previewed.

No real provider/StarRocks compatibility or business-result correctness is implied by synthetic tests. Real data/provider setup remains separate and authorized.

## M10 non-destructive capacity guard

New conversation creation can return HTTP 429 CONVERSATION_HISTORY_CAPACITY. Existing matching creation replay, ownership checks, existing conversations and their bounded turns remain available. No conversation/history is deleted automatically. See `docs/M10-capacity-hardening.md` and `docs/M10-capacity-query-model.md` for configuration and residual storage limits.
