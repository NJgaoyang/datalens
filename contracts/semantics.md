# M06 semantic contract

All endpoints use `/api/v1/semantics/{subjectId}` and standard ApiResponse/request IDs. All require active server-authenticated accounts. CSRF applies to mutations. Subject grant and current source/field scope are revalidated; chart sharing does not grant semantic access. Maintainers/reviewers/publishers are SUPER_ADMIN only. Business metric `owner` is descriptive, not permission-bearing.

## Endpoints

| Method/path | Input | Output |
| --- | --- | --- |
| GET `/draft` | none | Draft (admin) |
| PUT `/draft` | `{expectedVersion,document}` | Draft (admin) |
| POST `/review` | `{expectedVersion,confirmation:true}` | Draft (admin) |
| POST `/publish` | `{expectedVersion}` | Publication (admin) |
| POST `/withdraw` | `{expectedVersion}` | redacted Draft (admin) |
| GET `/published` | none | current Publication (subject-authorized) |
| GET `/search?q=` | max 200 characters, blank lists all matches | Search (subject-authorized) |
| GET `/releases` | none | latest 20 immutable snapshots with matching current scope (admin) |

Full field definitions and limits are `SemanticModels.java`. `Document` contains `fields`, `metrics`, `dimensions`, `relations`, `examples`; lists and nested fields are validated on HTTP and application entry. Fields require physical table/column, Chinese/display name, aliases, exact metadata type, unit, sensitivity. Metrics require calculation, filters (including effective statuses/refunds/distinct-key interpretation), time field, owner, unit, base grain, null handling, time policy, complete field IDs (time field must be DATE/DATETIME/TIMESTAMP), SQL and typed parameters. Dimensions reference one field. Relations encode an equality join between two field IDs plus direction/cardinality and both grains. Examples require question, referenced metric IDs, SQL, typed parameters, and expected result or verification method in `verification`. Parameters use M03 types. Assets have document-global unique IDs; physical fields are not duplicated. Metric declared field dependencies must exactly match SQL references. Example dependencies must be covered by its metrics.

`Draft`: subjectId, version, document, reviewed, reviewerId, reviewedAt, publishedVersion, warnings. Absent draft has version 0 and null document. Save/review/publish/withdraw increment draft version. Only save accepts expectedVersion 0. A stale version fails with 409 SEMANTIC_VERSION_CONFLICT. Draft replacement permits asset creation/update/deletion atomically and invalidates review, while previously published content remains immutable.

`Publication`: subjectId, monotonically increasing release version, document, digest, scopeFingerprint, reviewerId, publisherId, publishedAt, reviewedAt. Publish requires human review of the exact current digest and scope. All assets in that snapshot share the same review/release. A publication never uses unreviewed draft changes. Withdrawal removes current pointer and clears review; old snapshots are retained solely for admin history, not user retrieval. Re-publishing requires review again. Current publication has no public historical-version selector.

`Search`: publicationVersion and matches [{kind,id,name,reasons}]. Matching is case-insensitive substring on names, aliases, reviewed questions. All ambiguous matches are returned, not silently resolved. Empty matches are honest. No vector search or AI provider. Search and HTTP publication reads are audited without storing raw question text. Save/review/publish/withdraw audit the real administrator.

## Trust and grain boundaries

This module statically validates SQL through M03 SafeSql and typed parameters; it NEVER executes an example or opens JDBC. It does not certify business correctness. `confirmation:true` explicitly attests that a human reviewed business definitions and example validation evidence. Synthetic test fixtures are not actual business acceptance. The requested 20–30 real questions remain an external acceptance gate; sample counts outside this range produce a warning, not fictional success.

Only single-table, no-join, no-CTE, no-subquery SQL is accepted for metric/example definitions in this initial conservative release. The complete M03 AST compiler runs first. Canonical JOIN/WITH/nested SELECT rejection is an additional fail-closed restriction, not keyword-based permission or a parser fallback. Even declared one-to-one relationships cannot waive the restriction. A pre-aggregated, authorized single-table view can be used once its metadata is harvested. `SUM(DISTINCT amount)` is never automatically inserted or recommended as a grain fix.

ONE_TO_MANY, MANY_TO_MANY and UNKNOWN relations produce explicit FANOUT_RISK warnings. Other cardinalities are human assertions, not verified uniqueness; reversing many-to-one can fan out. Multiple relationships warn about multiple paths. Relationship metadata is inspectable for future planning, but does not authorize executing a join.

Fingerprint includes source identity/version and all sorted effective columns with type/nullability/ordinal. Retrieval rechecks account, subject grant, source enabled status and exact fingerprint, including after assembling results. Metadata/policy changes fail current retrieval with 409 SEMANTIC_SCOPE_CHANGED rather than returning stale trusted context. Stale or disabled/empty-scope admin draft returns only safe version metadata and null document so it can be replaced/reviewed without exposing removed fields. History omits mismatched snapshots. Withdraw is still allowed for an admin after source disable, returning no draft content. Lack of a current release yields 404 SEMANTIC_NOT_PUBLISHED; malformed content 422 SEMANTIC_INVALID; unsupported join/subquery semantics 422 SEMANTIC_JOIN_RISK; missing review 409 SEMANTIC_NOT_REVIEWED.

## Persistence and limits

V7 adds semantic_workspace and semantic_release only. A lock row serializes mutations and all snapshots are inserted immutably within the same transaction as the published pointer and audit. No existing migration is changed. A document is at most 500KB; a subject has at most 1000 releases. Ordinary published reads query the exact (subject,version) primary key, not all history. Admin history returns only latest 20 snapshots (at most 10MB before envelope); it is intentionally bounded, not a complete export. Decommissioning the feature withdraws publications; do not drop immutable audit/history data as a rollback.

M07 must consume `SemanticService.retrievePublished(actor,subjectId)` or a freshly authorized search; never read these tables/drafts, cache authority indefinitely, or infer execution rights from semantic references. Query execution still belongs exclusively to M03.

## M08 reviewed temporal parameters

Metrics may add `timeParameters: {startIndex,endIndex,timeZone,parameterType}` or omit it / use null. Existing definitions and Java constructors remain valid; absence means no time substitution is authorized, even when SQL happens to contain temporal placeholders. `timePolicy` remains required human-readable business meaning and is not executable authority.

Both indices are distinct zero-based positions in the complete immutable SQL parameter list (0..99). `parameterType` is exactly DATE or TIMESTAMP and must match both approved slots. `timeZone` is an explicit valid IANA business timezone. Save, review, publication and authorized retrieval validate this contract. The full SafeSql compiler runs first; an additional AST check requires those exact JDBC placeholders to form mandatory WHERE conjuncts `approved_time_field >= ?` and `approved_time_field < ?` against the physical field declared by `timeFieldId`. Aliases and nonadjacent/reversed parameter positions are supported. OR branches, BETWEEN, arithmetic, HAVING/projection parameters, different fields and incorrect inclusive/exclusive operators do not qualify. No SQL is rewritten and other parameter values/types cannot be replaced.

`SemanticRules.lastMonthRule(metric)` returns the approved LAST_MONTH chart rule only when a contract exists. Missing approval produces 422 SEMANTIC_TIME_UNSUPPORTED rather than inferring indices from dates, prompt text or parameter types. A calendar previous month is `[first day of previous business month,first day of current business month)`, including leap-year and year-crossing cases. DATE uses ISO calendar dates; TIMESTAMP converts each local midnight separately to an ISO UTC Instant, preserving daylight-saving transitions. The JDBC boundary binds Instant timestamps with an explicit UTC Calendar; existing fixed local TIMESTAMP strings retain their previous behavior. A TIMESTAMP contract requires human review that the datasource field's stored timestamp convention matches UTC-instant boundaries; software tests do not establish real StarRocks timezone behavior.

Semantic chart provenance binds the metric ID, release version, digest and scope fingerprint to the immutable chart definition. Changing or withdrawing the current semantic release requires re-preview under the new reviewed version rather than silently rewriting a saved chart.
