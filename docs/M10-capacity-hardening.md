# M10 non-destructive capacity hardening

This follow-up progresses code-solvable admission and materialization limits. It does not authorize cleanup, deployment or live integrations, and does not establish production scale.

## New-content admission

The owning store checks a bounded row count and creates rows in the same transaction under its existing module lock. Checks follow durable idempotency replay, so a matching retry can still resolve the existing object at capacity; conflicting replay still fails. Lowering limits does not delete existing rows or prevent startup/reads. Limit/count errors fail closed. New work receives an explicit capacity error requiring operator action, not an automatic retry loop.

Charts have independent global/personal admission thresholds:

| Rows | Global default | Personal default | Environment variables |
|---|---:|---:|---|
| Assets, including trash | 5000 | 500 per owner | DATALENS_CAPACITY_CHART_ASSETS / DATALENS_CAPACITY_CHART_ASSETS_PER_OWNER |
| Previews | 10000 | 1000 per actor | DATALENS_CAPACITY_CHART_PREVIEWS / DATALENS_CAPACITY_CHART_PREVIEWS_PER_USER |
| Refresh runs | 10000 | 1000 per actor | DATALENS_CAPACITY_CHART_RUNS / DATALENS_CAPACITY_CHART_RUNS_PER_USER |
| Ordinary revision admission | 20000 | 1000 per chart | DATALENS_CAPACITY_CHART_REVISIONS / DATALENS_CAPACITY_CHART_REVISIONS_PER_CHART |

Chart limits must be 1–1000000 and a personal limit cannot exceed its global limit. `CHART_STORAGE_CAPACITY` is HTTP 429. Save checks both asset and revision capacity before inserting either. Quotas never gate result binding, reads, revoke/share, soft delete, restore or ownership transfer. Restore/transfer intentionally may add recovery revisions beyond the ordinary-content threshold. Keep those safety operations available rather than deleting evidence or silently skipping audit.

Query, conversation and model settings are described in `M10-capacity-query-model.md`. Explicit `DATALENS_CAPACITY_*` mappings are in application.yml. All values are guardrails, not promises about disk size, latency or supported users. For example 10000 query results at the current 2 MB payload cap alone can approach 20 GB before other tables/indexes/backups. Review and lower values against the actual infrastructure; capacity can be raised only after an operator reviews storage, load and policy.

## Chart-list concurrency admission

At most two list/search scans run concurrently per application process. A further request receives generic HTTP 503 `CHART_LIST_BUSY`, before search-dependent database work, and must be retried later. Permits are released in `finally` on success, denial or failure. Direct detail reads are unaffected.

The complete existing authorization-filtered enumeration remains in place, with Java ROOT literal matching and final delivery rechecks for exact totals. A proposed raw-candidate cutoff was rejected during review because inaccessible/revoked charts could change an empty authorized result into an overflow error, and conservative Unicode filtering could prevent Chinese searches from narrowing. No raw candidate cutoff, partial count or Unicode-regressing prefilter is shipped.

This bounds concurrent application scans only, not per-request metadata/authorization work or database scans/sorts. New asset admission quotas limit future ordinary asset growth; existing over-limit databases remain readable. Large collections still need separately validated query-plan/index or pagination design without weakening revocation filtering. This performance gate remains open.

## Application-side JDBC materialization

Variable-width text/LOB/unknown result values use a bounded character reader with incremental UTF-8 accounting, cancellation checks and stream closure; there is no unbounded `getString` fallback. Known fixed-size scalar JDBC values use exact textual conversion; DECIMAL/NUMERIC precision/scale are validated before conversion. This distinction is necessary because packaged StarRocks/MySQL drivers reject numeric-to-binary stream conversion while preserving exact numeric strings through their scalar factories. Unit tests exercise the actual packaged factories without a live connection.

The shared two-million-byte result-value budget applies across cells and rows, including multibyte and split-surrogate boundaries. The reader stops consuming a deliberately enormous lazy input once over budget rather than materializing it in full. JDBC drivers may still buffer server responses internally, so real-driver large-cell tests under fixed heap and upstream limits remain a deployment gate. This is not a live StarRocks compatibility claim.

## Retention remains a product/operator decision

There is NO new scheduled deletion, TTL, truncation of saved data, audit discard, hard-delete endpoint or model-budget reset. Audit events and administrative management history remain writable because refusing them can prevent account disable, logout, revoke, cancellation and recovery. These histories, exempt recovery revisions and existing immutable semantic snapshots mean total lifetime disk usage is not mathematically capped by the new admission guardrails.

Before production, decide which record classes can be archived, the required retention period, audit/legal-hold and user-visible recovery requirements, whether complete exports have been verified, and how to preserve idempotency, source quarantine, grants, budgets and semantic/chart bindings. Any destructive cleanup needs explicit authorization and its own migration/security/recovery tests. Until then alert on metadata size, backup growth, capacity error codes and remaining storage, and preserve existing data.

## Verification required

Unit tests cover invalid configuration and exact count/replay ordering. Disposable MySQL tests exercise limit boundaries, concurrent last-slot admission, refusal without partial insert/budget consumption, and continued completion/revocation/recovery with unchanged stored data. Run the entire backend/frontend/Compose acceptance on the final pushed commit. Mock checks do not establish real StarRocks behavior or representative load.
