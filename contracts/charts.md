# M04 private chart API

Authenticated session, CSRF, standard ApiResponse/error/request ID and PageResult contracts apply. Server determines actor. M04 owner-only PRIVATE assets; sharing, admin management, filters and exports are not enabled until later milestones. Chart definitions never depend on chat or model services.

- GET /api/v1/charts?page=1&size=20: owner's metadata only, no SQL dispatch.
- POST /charts/previews: {definition,idempotencyKey}. Returns {id,definition,taskId}. Idempotent key is actor-scoped; a changed definition conflicts. Retry retains initial resolved dates.
- GET /charts/previews/{id}: owner-only preview metadata. GET /result returns successful authorized data and validates the spec against actual columns/types.
- POST /charts: {name,previewId}. Requires successful still-authorized preview; server loads its complete definition. Explicit confirmation produces independent persistent asset. Repeating same preview/name returns original asset, not a duplicate.
- GET /charts/{id}: {id,name,ownerId,version,revisionId,definition,latestTaskId,lastSuccessAt}. GET does not dispatch SQL; detail UI issues refresh POST every new opening.
- POST /charts/{id}/revisions: {name,previewId,expectedVersion}. Successful preview required. Atomic optimistic publication appends immutable revision; old version remains unchanged. Stale version returns CHART_VERSION_CONFLICT.
- POST /charts/{id}/refreshes: {idempotencyKey}. Server loads current published SQL/spec and fixed parameters. No SQL or arbitrary filters accepted. Returns QueryTask. Same intent reuses run/revision/resolved dates; new intent creates fresh query.
- GET /charts/{id}/refreshes/{taskId}/result: current owner and query authorization, validates spec again on schema drift, records revision-specific last successful delivery time from task's completion timestamp. No result aggregation or SQL rewriting.
- Generic query-task GET/status/result/cancel still checks immutable purpose/resource/revision binding. A chart task never becomes an unrestricted free-query capability.

All paths above use /api/v1 prefix. Query task status/error/truncation contract remains unchanged. Errors never include raw SQL or driver messages. List default 20, maximum 200. Unknown extra JSON fields are ignored by the standard mapper and never persisted as executable options.

## Definition

{subjectId,sql,parameters:[{type,value}],timeRule,metricDescription,spec}

The parameter list is the complete fixed typed parameter constraint for M04: viewers cannot override values. SQL parameters are JDBC-bound through M03. Time rule NONE requires all other time-rule fields null. LAST_DAYS requires days 1..3660, explicit IANA timeZone, distinct valid zero-based startIndex/endIndex pointing to DATE parameters. Bounds are business-calendar [today-(days-1),tomorrow), including today; not rolling N×24 hours. Resolution occurs once per run/preview intent and is persisted. The SQL author must use >= start and < endExclusive placeholders accordingly. Server clock is UTC; timezone calculation uses java.time.

M08 adds LAST_MONTH with `days:null`, an explicit business `timeZone` and distinct zero-based approved start/end slots. It resolves `[first day of previous business month,first day of current business month)`, not the previous 30 days. Relative bounds now support matching DATE or TIMESTAMP slots. DATE yields calendar-date strings; TIMESTAMP yields UTC Instant strings from each local boundary, including offset changes across DST. Only the two selected values change; SQL, parameter types and other parameter values remain immutable. Conversation-created definitions include optional `semanticBinding:{publicationVersion,digest,scopeFingerprint,metricId}`; older six-field definitions remain valid. Semantic-bound previews and charts must be reauthorized against that reviewed publication rather than silently adopting changed semantics.

Spec is {type:TABLE|KPI|BAR|LINE|PIE,dimension:null|string,measures:string[]}. Measures 1..20 for numeric charts, distinct actual output columns. TABLE uses selected columns with no dimension; an empty list means all returned columns. KPI requires one numeric measure, at most one complete SQL-result row and no dimension; it never sums samples. BAR/LINE require a distinct dimension and numeric measures. PIE requires one numeric measure, nonnegative non-null values and untruncated data. Empty/null/zero remain distinct. No arbitrary ECharts options, JavaScript, HTML, formatter code or callbacks accepted.

Raw results remain string/null, preserving DECIMAL and BIGINT exactly. Table/KPI are exact. Plot coordinates may use approximate finite IEEE-754 values with exact raw tooltips and a visible approximation notice. Unsupported nonfinite or underflow magnitudes refuse plotting; choose table/KPI. Truncation is explicit and never treated as a complete total/distribution.

Freshness is revision-specific. Failed latest refresh is not latest data. Last success is only a timestamp, not proof current permissions remain valid. Permission errors clear visible rows. Late runs do not overwrite the selected run or another revision's freshness.

## Persistence and security

V5 adds chart, immutable chart_revision, chart_preview and chart_run; query_task receives server-only purpose/resource/revision columns atomically before dispatch. Direct SQL endpoint cannot supply purpose. Query admission, queued dispatch, result persistence, and every task status/result/cancel recheck resource access plus original subject/source policy. Preview evidence includes actor/auth revision, definition, resolved parameters and query task. Current M04 requires owner and subject grants; future M05 must add bounded published-view authority without granting generic subject SQL or impersonating publisher. Credentials remain in datasource module.

No schema rollback by destructive DDL: back up metadata before upgrade and restore the application/database pair together when required. V1–V4 remain untouched. Run V4→V5 upgrade and same-volume restart checks in CI. Retention/preview cleanup is not implemented as an unapproved deletion job.

## M05 sharing and administration (supersedes M04 owner-only restrictions)

Authenticated active accounts only. Every mutation uses existing session/CSRF protections. Error responses use the standard envelope. Summary adds `visibility: PRIVATE|ALL|SPECIFIC` and `deleted: boolean`. Detail adds visibility, deleted, `canManage`, and `userIds`. For non-managing viewers, definition.sql and subjectId are empty, parameters and userIds are empty; spec/timeRule/metricDescription remain for rendering. This is a result-view capability, not permission to query arbitrary SQL. Refresh accepts only an idempotency key; no SQL/filter overrides are currently supported.

- GET /api/v1/charts?scope=mine|shared|all|admin|trash&page=1&size=20. Default mine. shared is specifically shared to the caller, all is authenticated-all sharing. admin requires SUPER_ADMIN. Trash returns own deleted charts or all deleted charts for admins. Candidates use the same database ACL/scope predicate. Lists never execute analytics SQL.
- GET /api/v1/charts/recipients returns active accounts [{id,username}], maximum 1000 ordered by username/id, authenticated only. No credentials or roles returned.
- PUT /api/v1/charts/{id}/sharing: {visibility,userIds,expectedVersion}. Owner/admin only. PRIVATE/ALL require empty userIds; SPECIFIC requires 1–100 unique active account IDs excluding owner. Expected version mismatch returns 409. Sharing validates the author's current subject policy and SQL fields. Reverting to PRIVATE remains allowed after source policy revocation.
- DELETE /api/v1/charts/{id}?expectedVersion=N soft-deletes, clears sharing and advances version. No hard-delete API.
- POST /api/v1/charts/{id}/restore: {expectedVersion}. Owner/admin only, requires owner current query policy, resets PRIVATE and starts a fresh immutable revision.
- POST /api/v1/charts/{id}/owner: {ownerId,expectedVersion}. SUPER_ADMIN only; active target must have current query authorization for the exact published SQL. Resets PRIVATE and starts a fresh immutable revision.

Each returns updated Summary. New content publication also resets PRIVATE and removes recipients, so old sharing cannot silently expose newly edited SQL. Restore and transfer never revive old tasks. Historical definitions stay immutable.

Sharing requires datasource subject shareScope: NONE denies, AUTHORIZED_USERS permits SPECIFIC subsets of the subject's userIds only, ALL_AUTHENTICATED permits SPECIFIC or ALL. The latter is explicit administrator configuration, not default. It grants result sharing only, never subject query access to recipients. If the owner is disabled, subject/query field access revoked, source disabled/version changed, or sharing policy tightened, tasks stop or their reads fail closed. Active viewer ACL and owner data policy are checked at submission, queued execution, result persistence and all generic task status/result/cancel routes. Revoking B after B refreshes denies B's existing task ID.

Audit uses the actual manager and viewer, never impersonation. chart_management_event additionally records before/after owner, visibility, recipient IDs, version and trace ID, without SQL, results or secrets. Export is not granted or exposed by this module.

## M10 list/search concurrency admission

`GET /api/v1/charts` accepts optional `search` (maximum 100 characters, stripped, case-insensitive Java ROOT literal substring). Pagination stays unchanged: successful `total` is exact after current source/semantic authorization and a final delivery recheck, never a truncated sample count. Complete scope enumeration uses 200-row batches and authoritative Java matching, including Unicode names. There is no candidate-count cutoff, database name prefilter or partial-results response.

The singleton chart service allows two concurrent list/search scans per application instance. After current principal and scope validation, a nonblocking admission failure returns HTTP 503 `CHART_LIST_BUSY` in the standard error envelope. This generic response reveals no chart count, title or identifier and depends only on concurrent requests, not matching or revoked asset counts. Slots are released on success and all failures. The existing UI clears rows/counts before loading and displays the server error. Direct authorized detail access is unchanged; lists do not execute analytics SQL or delete data.

This bounds concurrent scans only. Per-request database scanning, authorization work and authorized-result memory remain unbounded with historical asset count. Large-dataset query-plan, latency and memory acceptance remain unresolved production performance gates. New asset admission quotas constrain future growth but do not prove production performance or remove historical data.

## M10 chart-history admission

New preview, refresh-run, save and publication operations can return HTTP 429 `CHART_STORAGE_CAPACITY`. This is a persistent capacity refusal requiring administrator action, not a transient rate-limit retry hint. No row is deleted. Matching durable preview/run/save retries remain available; mismatched inputs retain their conflict semantics. Save checks capacity before either asset or revision insertion. Reads, result updates, sharing revocation, delete/restore and ownership transfer remain available subject to their existing authorization. Detailed configurable limits and deliberate management-history exceptions are in `docs/M10-capacity-hardening.md`.
