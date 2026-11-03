# DataLens engineering rules

## Scope and source of truth

Read `docs/requirements.md`, `docs/architecture.md`, `docs/permission-matrix.md`, relevant contracts, and local run instructions before changing code. This is an independent Java 21 / Spring Boot modular monolith with a Vue 3 / TypeScript frontend. Do not replace the agreed technology stack or expand a milestone without an explicit request.

M00 is infrastructure only. Do not add fake-success query/chat endpoints, dummy business features or unverified completion claims. Implement one reviewable task at a time, with backend, frontend, migration and tests where applicable. Keep commits focused and reversible.

## Module boundaries

- Keep business code in its owning module. Expose application interfaces rather than accessing another module's mapper or tables directly.
- Query authorization and execution belong to the query/permission services. AI and chart modules must use those services, never bypass them with direct JDBC.
- Provider SDK details stay behind ModelGateway. Model availability must not be required for saved-chart execution or application startup.
- Use separate connection pools for MySQL metadata and StarRocks analytics.

## Data, contracts and security

- Add new versioned Flyway migrations; never edit an already-applied migration or rely on manual DDL. Add rollback/recovery notes for destructive changes.
- Update `contracts/` and consumers when changing APIs. Preserve consistent error, pagination and request tracing conventions.
- Authenticate on the server. Never derive acting identity from a client-supplied userId. Deny by default and recheck authorization at result delivery and download, including after revocation.
- Sharing grants published-chart viewing only, not arbitrary query or export rights. Follow the permission matrix.
- SQL parsing failure means rejection. Do not replace AST validation with keyword filtering. Bind values; whitelist structural identifiers. Read-only credentials are a second barrier, not the only barrier.
- Never commit secrets, log credentials/session tokens, expose them to the frontend/model, or test writes against production StarRocks.
- Admin configuration must restrict outbound datasource/model endpoints; never accept arbitrary user-provided JDBC URLs.

## Verification and reporting

Run applicable builds, tests, type checks and integration checks on the final code. Permission changes require negative/regression tests. Check startup with empty and existing MySQL volumes and without model/StarRocks configuration when touching bootstrap.

Distinguish passed, failed and not-run checks. Mock tests do not establish real StarRocks compatibility, database cancellation or production readiness. Record command, result and environmental blockers; do not silently skip a failing or unavailable check. Do not claim CI passed until the exact commit has a verified result.

For each task state its ID, goal, dependencies, allowed scope, non-goals, API/schema changes, acceptance scenarios and checks. Completion reports list changed files, executed checks, unexecuted checks and remaining risks. Advance to the next module only after acceptance.

## Dependencies and operations

Keep exact direct dependency versions and the frontend lockfile committed. Validate Spring Boot / Spring AI compatibility before adding or upgrading model integration, using the official BOM. Update the version table with dependency changes.

Do not introduce Python services, Kafka, vector databases, service registries or multi-agent systems. Reused third-party code must include source, commit, license and test evidence. Do not merge, publish, deploy or change production data without the required explicit authorization.
