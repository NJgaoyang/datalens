# M02 data sources and scoped metadata

## Implemented boundaries
- Admin-only structured configuration. Exact numeric IPv4:port runtime allowlist; default denies all. No arbitrary JDBC URL, DNS resolution, or client-supplied driver options.
- Official StarRocks Connector/J 1.1.1, separate lazy Hikari pools (3 connections/source, at most 100 sources). Application metadata MySQL datasource and Flyway are independent.
- Runtime AES-256-GCM key, random nonce, source-bound AAD, encrypted MySQL storage. No plaintext/ciphertext in API DTOs or error logs. Empty runtime key supports zero-source startup and fails closed on credential operations. Key loss makes stored credentials unusable; rotation currently requires operator-managed re-encryption/re-entry, not an automatic rotation feature.
- TLS VERIFY_IDENTITY; JVM trusted CA and certificate matching configured IP are required. No TLS bypass switch. Dedicated account must have server-enforced read-only/metadata grants; JDBC client flags do not establish least privilege.
- Exact table allowlist; escaped JDBC metadata patterns; scope checked on every returned row. Never enumerate all catalogs/tables or query analytic rows in M02. Max 100 tables, 1,000 columns/table, 10,000 columns/source, 30-second application deadline and 10-second socket timeout. Four concurrent harvests per instance; recoverable two-minute lease and generation checks prevent old work publishing after replacement.
- Failed refresh preserves prior successful snapshot; reconfiguration clears metadata and closes prior pool. Resource identity/table-scope changes also revoke existing subject grants/users to prevent same-name cross-database privilege carryover.
- Subject membership, exact allowed fields, denied fields (deny wins), share scope NONE/AUTHORIZED_USERS. User metadata responses omit membership and denied-field details. Sharing itself is not implemented in M02; later modules must enforce stored scope rather than treating it as share authorization.

## Verification distinctions
- SourceSecurityTest, SourcePoolsTest, MetadataCollectorTest: deterministic unit tests, including encryption/authenticated tampering, allowlist parsing, lifecycle, exact metadata calls and rejection of out-of-scope results.
- SourceIT: real HTTP cookies/CSRF/Spring Security + MySQL migration/transactions + Redis sessions. Analysis pools/collector are Mockito simulations, explicitly NOT a StarRocks cluster test. Covers admin and ordinary roles, redaction, encrypted persistence, refresh failure atomicity, subject filtering/revocation, endpoint changes and stale harvest recovery.
- Existing FoundationIT and Compose acceptance retain zero-StarRocks startup without runtime source key.
- No real StarRocks account/cluster was supplied. Real-cluster acceptance remains pending: connector catalog/schema mappings and wildcard escaping, table/column types, TLS/IP SAN, read-only grants, test and collection, and pool replacement under load against the user's exact cluster version. Do not mark M02 production-integrated from simulated tests.

## Runtime configuration
DATALENS_SOURCE_KEY: base64-encoded 32 random bytes supplied via the runtime secret manager, not source control. DATALENS_SOURCE_ALLOWED_ENDPOINTS: comma-separated exact numeric IPv4:port entries, for example 10.20.30.40:9030. No default destination, password, or key. Compose forwards both variables without requiring them for empty-source startup.

## Driver reference
https://docs.starrocks.io/docs/integrations/JDBC_driver/ (official driver coordinates and structured catalog.database URL).

## Confirmed target: StarRocks 3.3.22
The user identified 3.3.22 as the test cluster version. Official upstream SSL documentation says native encrypted connections are supported from 3.4.1, so stock 3.3.22 cannot satisfy this application's current VERIFY_IDENTITY default. Before any live connection, establish whether the deployment has vendor-backported TLS or an approved MySQL-aware TLS gateway. No plaintext fallback is implemented, and no credentials should be sent through chat or committed. Any alternate test-only transport policy requires an explicit operational decision. Connector/J 1.1.1 documents general JDBC metadata support, but does not publish an explicit 3.3.22 compatibility matrix; metadata mapping must be validated against the actual cluster.
Reference: https://docs.starrocks.io/docs/administration/user_privs/ssl_authentication/
