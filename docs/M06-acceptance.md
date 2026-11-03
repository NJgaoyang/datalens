# M06 semantic assets and reviewed publication

Task: M06. Dependencies: M02 metadata/subject policy and M03 SQL validation. Scope: semantic field, metric, dimension, relationship and reviewed-example definitions; admin draft/review/publish/withdraw; immutable subject-level releases; authorized keyword/alias/example retrieval; UI, audit, tests and additive V7 migration. No model, vector store, SQL execution shortcut, merge, deployment or real business-data claims.

## Safety and acceptance contract

- Business fields explicitly bind physical fields, names, aliases, type, unit and sensitivity. Metrics record calculation, filters, time field/policy, owner, unit, base grain, null handling, dependencies and parameterized SQL. Examples require question, metric references, SQL and an expected-result/verification description.
- Admin review is an explicit human attestation tied to the exact saved document and current metadata scope. Any edit invalidates review. Publication checks the current reviewed draft and expected version atomically; published content remains immutable. Withdraw removes current availability without deleting history.
- Ordinary users may read/search only the current authorized published snapshot. No cached access or historical-ID shortcut; effective metadata scope and source state are revalidated. Admin maintenance does not create an additional role for the named business owner.
- Alias conflicts return candidates with matching reasons, not a guessed authoritative metric. Empty search results remain empty. Drafts do not enter retrieval.
- Join fields, cardinality and left/right grain are explicit relationship assets. Fanout and unknown cardinality generate warnings. This release deliberately rejects JOIN and multi-table SQL from published metrics/examples, including self-joins. SQL analysis cannot prove key uniqueness or correct preaggregation from declared metadata alone; a validated preaggregation facility is not implemented. This is a documented safe limitation, not acceptance of arbitrary business joins.
- Query syntax and parameters reuse the M03 fail-closed parser and parameter validators. Reviewed examples are stored and searched, never executed by M06; manual execution, when needed, uses the existing controlled query page/service.
- V7 adds only semantic-owned tables. Recovery: back up MySQL before upgrade; forward fixes preferred. To roll back, stop the app and restore the complete pre-V7 database backup with matching previous application. Do not edit applied Flyway migrations or selectively drop tables with active consumers.

## External business acceptance remains open

The user has not supplied the first real business subject, responsible owner, or 20–30 human-confirmed questions and ground truth. No synthetic fixture is represented as a production metric or human business sign-off. The application can collect explicit review attestations; the implementation does not make those attestations on the user's behalf.

Real StarRocks 3.3.22 TLS, read-only access, dialect compatibility, cancellation, timeout/resource limits and real-data freshness gates remain pending from M02–M04. Passing tests against mocked analytics or MySQL metadata does not establish these.

## Verification record

Local Java 21 compilation of all production and test classes passed with cached dependencies; 205 unit tests passed, none skipped. This includes 30 M06 tests for dependency closure, SQL/subquery/self-join rejection, temporal types, review binding, scope races, role changes and recovery. Fourteen new real HTTP/MySQL/Redis integration cases compile, covering concurrent publication, immutable history, withdrawal, CSRF, roles and account/source/field revocation. They have not yet run locally because MySQL/Docker are unavailable.

Local Maven offline cannot resolve the pinned Boot 4.0.9 parent from cache; exact-commit CI remains required. Independent read-only security review reported no remaining blocking findings after fixes for scalar-subquery bypass, scope-delivery races, unbounded history reads and disabled-source withdrawal recovery.

Commands required: backend `mvn -B -ntp -f server/pom.xml verify -Pintegration`; frontend `npm ci`, `npm test`, `npm run build`, `npm run test:e2e`; disposable Compose cold start, same-volume restart and recovery. Local cached Java compilation/unit tests are supporting evidence only and do not replace Maven/real MySQL CI.

Frontend final local checks: `npm test` passed 41 tests across 7 files; `npm run build` passed Vue/TypeScript and Vite. Playwright lists 56 cases across 6 files, including 13 new semantic flows. Local Chromium cannot create IPC sockets, so browser assertions and visual screenshots did not run locally. Exact-commit frontend CI must execute all 56 cases. See web/M06-verification.md for commands and boundaries.

V1–V6 were verified byte-identical against baseline commit bbab8ce20f683229e820e4c1817f89242d77ec75; only V7 is new. Foundation and Compose expectations include the added semantic lock row and M06 module/stage. No merge or deployment performed.
