# DataLens architecture and locked versions

> Effective greenfield baseline for dev20261104 is [greenfield-baseline-20261104.md](greenfield-baseline-20261104.md). Historical compatibility requirements do not apply; never automatically clear an existing database.

Independent Vue application and Java 21 Spring Boot modular monolith. MySQL 8 stores identities, JDBC sessions, login throttling, subjects, queries, charts, revisions, sharing and audit. StarRocks is an external read-only analytics database with an isolated pool. Runs as one native Linux JAR + tar.gz with conf/bin and direct IP:port, no Redis/Nginx/Docker production requirement. No Python service, microservices, Kafka, vector database or model agent system.

## Version baseline

The committed manifests and lockfile are authoritative. Existing verified dependencies are preserved:

- Java 21; Maven 3.9.x.
- Spring Boot 4.0.9; Spring AI 2.0.2 BOM + spring-ai-model schema converter; explicit bounded HTTP transport, no auto-configured model starter or automatic tool loop.
- MyBatis-Plus 3.5.18 Boot 4 starter; Flyway and MySQL connector managed by Boot.
- JSQLParser 5.3; StarRocks official JDBC 1.1.1; actual 3.3.22 TLS compatibility remains unverified.
- MySQL 8.4.13 (CI test baseline); JDBC-backed sessions, no Redis runtime dependency. Example first-install connection defaults are documented separately.
- Frontend exact versions in web/package.json and package-lock.json; npm ci is mandatory, no floating direct dependencies.

## Boundaries

Identity owns users and sessions, with AccountDirectory providing credential-free identity facts. PermissionService rereads the active session principal. Datasource owns endpoint restrictions, credentials, pools, metadata and subject policy. Query owns SQL parsing, parameter binding, persistent tasks, executor limits and cancellation. Chart owns preview/save/revision/run/ACL/admin operations. Audit stores actual actor events. Semantic, model and conversation modules use these capabilities, never bypass query authorization.

QueryPurposeAuthorization is a server-only SPI. Public query DTOs cannot specify a purpose. A chart query binds resource, immutable revision and viewer-owned run with its resolved parameters. The guard rejects changed SQL/parameters. QueryService uses the chart owner's current data scope for JDBC only while retaining the actual viewer for task ownership, concurrency and audit. Status, result, cancel and executor recheck purpose authorization. A missing or ambiguous guard denies access.

Chart viewing is separate from management, arbitrary SQL and export. Publication resets sharing to private. Delete is recoverable; restore/transfer resets private and advances immutable revision. Version checks serialize mutations and prevent stale updates. Sharing lives in MySQL, not solely Redis. Generic chart query results never gain anonymous URLs.

## Test and deployment workflow

- server: `mvn -B verify`; full MySQL integration: `mvn -B -Pintegration verify` against a disposable empty MySQL 8 database.
- web: `npm ci`, `npm test`, `npm run build`, `npm run test:e2e`.
- CI backend and frontend browser checks must pass on the exact pushed commit; native tar startup/readiness checks are separate release evidence.
- Flyway currently contains V1–V12 from the earlier build. Since this is a greenfield project, the pre-release baseline may be refactored without upgrade compatibility; test cold install on an empty schema. Never change a populated schema without separate authorization.
- Native release checks cover cold start, authenticated API boundary, repeat restart on the SAME initialized schema, logical restore, executor lease loss/process restart and missing external model/StarRocks configuration. Historical Compose tests are not the deployment target.
- Real StarRocks protocol/TLS, cancellation, timeout, concurrency and business-data freshness gates are tracked separately. Mocks and MySQL integration do not close them.

M05 is not production deployment authorization. No merge/deploy is performed by this implementation.

## M06 semantic boundary

Semantic owns subject-level draft documents and immutable release snapshots. Admin-only review binds the document digest and metadata fingerprint; publication changes a current-release pointer under an optimistic mutation lock. QuerySourceAccess provides current authorization and effective columns without exposing datasource repositories, credentials or pools. The fingerprint includes actual effective metadata, not only source configuration version. Retrieval is publication-only and reauthorizes the caller on every request. M07 must consume the semantic application service and preserve publication evidence, never read drafts or semantic tables directly.

M06 stores reviewed examples but does not execute them. SafeSql and QueryParameters validate definitions through the established query boundary. Relationship cardinality/grain is declared business metadata, not a database uniqueness proof; the initial publication subset therefore rejects JOIN/multi-table SQL rather than accepting uncertain aggregate grain. Real business correctness requires user-provided cases and human review, independent of software test success.

## M08 conversation composition
Durable principal-owned conversations and bounded turns compose the existing ModelGateway, published semantics, ChartService and QueryService. Admission and idempotency are persisted before any model operation. No provider SDK or direct analytics JDBC exists in the conversation module. A canonical chart preview is the only query for a turn; explicit save delegates to the same M04 service. SemanticBinding remains attached to saved definitions and revalidated at preview, query capability boundaries, save, sharing and refresh. Optional reviewed TimeParameters select immutable SQL parameter slots, with calendar LAST_MONTH resolution in the business timezone. Query executor lease admission precedes conversation restart recovery; uncertain model operations become INTERRUPTED rather than automatically replayed.

## M10 deployment boundary
The query executor health indicator participates in readiness; losing the exclusive lease cannot appear healthy merely because the metadata pool reconnects. No automatic lease reacquisition/replay is introduced. Production remains blocked pending the release matrix in `M10-acceptance.md`; operator preparation is in `../deploy/production-readiness.md`.
