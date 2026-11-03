# DataLens architecture and locked versions

Independent Vue application and Java 21 Spring Boot modular monolith. MySQL stores identities, subjects, queries, charts, revisions, sharing and audit; Redis stores sessions. StarRocks is an external analytics database accessed with a separate read-only JDBC pool. No Python service, microservices, Kafka, vector database or model agent system.

## Version baseline

The committed manifests and lockfile are authoritative. Existing verified dependencies are preserved:

- Java 21; Maven 3.9.x.
- Spring Boot 4.0.9; Spring AI 2.0.2 BOM (no model starter yet).
- MyBatis-Plus 3.5.18 Boot 4 starter; Flyway and MySQL connector managed by Boot.
- JSQLParser 5.3; StarRocks official JDBC 1.1.1; actual 3.3.22 TLS compatibility remains unverified.
- MySQL 8.4.8; Redis 7.4.2.
- Frontend exact versions in web/package.json and package-lock.json; npm ci is mandatory, no floating direct dependencies.

## Boundaries

Identity owns users and sessions, with AccountDirectory providing credential-free identity facts. PermissionService rereads the active session principal. Datasource owns endpoint restrictions, credentials, pools, metadata and subject policy. Query owns SQL parsing, parameter binding, persistent tasks, executor limits and cancellation. Chart owns preview/save/revision/run/ACL/admin operations. Audit stores actual actor events. Future semantic, model and conversation modules must use these capabilities, never bypass query authorization.

QueryPurposeAuthorization is a server-only SPI. Public query DTOs cannot specify a purpose. A chart query binds resource, immutable revision and viewer-owned run with its resolved parameters. The guard rejects changed SQL/parameters. QueryService uses the chart owner's current data scope for JDBC only while retaining the actual viewer for task ownership, concurrency and audit. Status, result, cancel and executor recheck purpose authorization. A missing or ambiguous guard denies access.

Chart viewing is separate from management, arbitrary SQL and export. Publication resets sharing to private. Delete is recoverable; restore/transfer resets private and advances immutable revision. Version checks serialize mutations and prevent stale updates. Sharing lives in MySQL, not solely Redis. Generic chart query results never gain anonymous URLs.

## Test and deployment workflow

- server: `mvn -B verify`; full MySQL integration: `mvn -B -Pmysql-it verify` with CI MySQL/Redis configuration.
- web: `npm ci`, `npm test`, `npm run build`, `npm run test:e2e`.
- CI backend, frontend browser and disposable Compose acceptance must pass on the exact pushed commit.
- Flyway only adds migrations; V1–V5 remain unchanged and V6 upgrades sharing.
- Compose smoke checks cold start, authenticated API boundary, same-volume restart and Redis failure/recovery without requiring model or StarRocks configuration.
- Real StarRocks protocol/TLS, cancellation, timeout, concurrency and business-data freshness gates are tracked separately. Mocks and MySQL integration do not close them.

M05 is not production deployment authorization. No merge/deploy is performed by this implementation.
