# DataLens engineering rules

## Scope and source of truth

**Greenfield rule for `dev20261104` and subsequent DataLens edits:** Treat DataLens as a NEW independent product with no obligations to preserve old branches' UI, routes, API payloads, deprecated modules, or database layouts. Favor clean implementations; remove obsolete code rather than adding legacy adapters. The current product baseline is `docs/greenfield-baseline-20261104.md` plus the active D00/D01 design. Old milestone notes are historical, not compatibility requirements. Do not change production or any populated database without a separate explicit instruction.

Read `docs/requirements.md`, `docs/architecture.md`, `docs/permission-matrix.md`, relevant contracts, and local run instructions before changing code. This is an independent Java 21 / Spring Boot modular monolith with a Vue 3 / TypeScript frontend. Do not replace the agreed technology stack or expand a milestone without an explicit request.

M00 is infrastructure only. Do not add fake-success query/chat endpoints, dummy business features or unverified completion claims. Implement one reviewable task at a time, with backend, frontend, migration and tests where applicable. Keep commits focused and reversible.

## Module boundaries

- Keep business code in its owning module. Expose application interfaces rather than accessing another module's mapper or tables directly.
- Query authorization and execution belong to the query/permission services. AI and chart modules must use those services, never bypass them with direct JDBC.
- Provider SDK details stay behind ModelGateway. Model availability must not be required for saved-chart execution or application startup.
- Use separate connection pools for MySQL metadata and StarRocks analytics.

## Data, contracts and security

- Greenfield schema starts from a newly created/empty MySQL 8 database. During pre-release development, migration history may be squashed/rebased to a fresh baseline without supporting old versions; update tests and docs together. NEVER run automatic clean/drop or touch populated/production databases. Destructive operations require explicit permission, a backup and a named target. Current bootstrapping must not reset an existing admin.
- Update `contracts/` and all active consumers together when changing APIs. Do not add legacy routes, old-field aliases or migration shims solely for backward compatibility. Preserve consistent error, pagination and request tracing conventions.
- Authenticate on the server. Never derive acting identity from a client-supplied userId. Deny by default and recheck authorization at result delivery and download, including after revocation.
- Sharing grants published-chart viewing only, not arbitrary query or export rights. Follow the permission matrix.
- SQL parsing failure means rejection. Do not replace AST validation with keyword filtering. Bind values; whitelist structural identifiers. Read-only credentials are a second barrier, not the only barrier.
- Never commit actual production secrets, log credentials/session tokens, expose them to the frontend/model, or test writes against production StarRocks. Documented example defaults (`root`/`123456`, `admin`/`admin@123456`) are deliberately public first-install values; require operators to replace them before exposing the service outside an isolated network.
- Admin configuration must restrict outbound datasource/model endpoints; never accept arbitrary user-provided JDBC URLs.

## Verification and reporting

Run applicable builds, tests, type checks and integration checks on the final code. Permission changes require negative/regression tests. Check startup on an empty new MySQL database and restart of that SAME initialized version without model/StarRocks configuration when touching bootstrap. Legacy schema upgrade tests are not release gates for this greenfield branch.

Distinguish passed, failed and not-run checks. Mock tests do not establish real StarRocks compatibility, database cancellation or production readiness. Record command, result and environmental blockers; do not silently skip a failing or unavailable check. Do not claim CI passed until the exact commit has a verified result.

For each task state its ID, goal, dependencies, allowed scope, non-goals, API/schema changes, acceptance scenarios and checks. Completion reports list changed files, executed checks, unexecuted checks and remaining risks. Advance to the next module only after acceptance.

## Dependencies and operations

Keep exact direct dependency versions and the frontend lockfile committed. Validate Spring Boot / Spring AI compatibility before adding or upgrading model integration, using the official BOM. Update the version table with dependency changes.

Do not introduce Python services, Kafka, vector databases, service registries or multi-agent systems. Reused third-party code must include source, commit, license and test evidence. Do not merge, publish, deploy or change production data without the required explicit authorization.
