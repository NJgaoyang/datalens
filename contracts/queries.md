# M03 controlled query API

All endpoints require a valid server session. POST requires CSRF. `ApiResponse` and stable error envelopes follow the existing contract. Acting identity is never accepted from request data. Only the task creator may read/cancel; administrator role does not bypass result ownership.

- `POST /api/v1/query-tasks`: `{subjectId, sql, parameters:[{type,value}], idempotencyKey}`. Types: STRING, DECIMAL, INTEGER, BOOLEAN, DATE (`YYYY-MM-DD`), TIMESTAMP (ISO local datetime, or explicit ISO UTC Instant ending in Z), NULL. Instant timestamps are bound with an explicit UTC JDBC Calendar; legacy local datetime binding is unchanged. Non-null values are strings; NULL requires null. Up to100 parameters,4000 characters each and60000 UTF-8 bytes aggregate JSON. Key is8–64 ASCII letters/digits/underscore/hyphen. Repeating same key/content returns same durable task; differing content yields409 IDEMPOTENCY_CONFLICT. Source revision is part of request identity.
- `GET /api/v1/query-tasks/{id}`: TaskView.
- `GET /api/v1/query-tasks/{id}/result`: Result only after SUCCEEDED; otherwise409 QUERY_RESULT_UNAVAILABLE. Rechecks identity, ownership, source revision, subject membership and every referenced SQL field.
- `POST /api/v1/query-tasks/{id}/cancel`: TaskView. Terminal tasks remain terminal. Cancellation requests call JDBC Statement.cancel and attempt Connection.abort after grace period if still active. It is not a guarantee of database termination.

TaskView: `{id,subjectId,status,errorCode,createdAt,finishedAt,durationMs,truncated}`. Statuses: QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLING, CANCELLED, TIMED_OUT, CANCEL_UNCONFIRMED, RECOVERY_UNCONFIRMED. Only undispatched work can be confirmed CANCELLED in this implementation. Any cancellation after dispatch is conservatively CANCEL_UNCONFIRMED until real driver/cluster cancellation semantics have been accepted. Such a source is quarantined against new and already-queued work. Database errors are sanitized and can also quarantine because remote termination is uncertain.

Result: `{columns:[{name,jdbcType}],rows:(string|null)[][],truncated,rowLimit}`. Numeric and date values remain strings to preserve DECIMAL/BIGINT precision. Limits:100 columns,1000 returned rows,2MB serialized UTF-8 result; fetches one extra row to detect truncation. Truncation is explicit: do not calculate authoritative totals from the truncated rows. SQL aggregate queries over the authorized source remain the way to obtain complete totals.

Server-controlled boundaries:4 worker threads;2 outstanding tasks/user;3/source;32 globally. Queued tasks count toward admission.10-second JDBC/server query timeout; cancellation grace3 seconds. Pool acquisition and setup have separate bounds. The2MB cap is output storage/response size, not a hard driver heap bound; a driver may buffer larger intermediate values. No runtime configuration weakens TLS or accepts arbitrary JDBC URLs.

SQL subset and rejection list: `docs/m03-sql-subset.md`. No raw input SQL is executed; a fail-closed AST compiler emits approved syntax and JDBC binds values.

## M10 non-destructive capacity guard

New task admission can return HTTP 429 QUERY_HISTORY_CAPACITY when configured global/actor durable-history capacity is reached. Matching idempotent replay and authorized existing reads/cancel/recovery/terminal transitions remain available; no task is deleted or budget automatically reset. Variable text result values use bounded character-stream materialization; exact fixed scalar values retain their string contract. Real JDBC buffering remains a separate acceptance gate. See `docs/M10-capacity-hardening.md` and `docs/M10-capacity-query-model.md` for configuration and residual storage limits.
