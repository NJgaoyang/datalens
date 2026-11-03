# M02 API contract
All responses use existing ApiResponse envelope. All writes require CSRF. Admin endpoints require current SUPER_ADMIN, checked in service as well as security filter.

- GET /api/v1/admin/sources: SourceView[] (bounded to 100 configured sources)
- POST /api/v1/admin/sources: SourceInput -> SourceView
- PUT /api/v1/admin/sources/{id}: SourceInput -> SourceView, whole replacement; blank/null password retains existing credential
- POST /api/v1/admin/sources/{id}/test: {} -> {success:true}; safe error otherwise
- POST /api/v1/admin/sources/{id}/harvest: {} -> SourceView
- GET /api/v1/admin/sources/{id}/metadata: ColumnView[]
- GET /api/v1/admin/subjects: SubjectView[]
- POST /api/v1/admin/subjects: SubjectInput -> SubjectView
- PUT /api/v1/admin/subjects/{id}: SubjectInput -> SubjectView
- GET /api/v1/subjects: SubjectView[] for authorized current user (admin sees all)
- GET /api/v1/subjects/{id}/metadata: ColumnView[] filtered to allowed fields minus denied fields; disabled source unavailable

SourceInput: {name,host,port,catalog,database,username,password,enabled,tables:string[]}. No JDBC URLs. Exact allowed tables, max 100. Source IDs are UUID strings. Password mandatory on creation, never returned. Table/catalog/database identifiers: letters/digits/underscore, first letter or underscore.
SourceView: {id,name,host,port,catalog,database,username,enabled,tables:string[],version,harvestStatus,lastHarvestAt,lastErrorCode}. No password/ciphertext. Status NEVER/RUNNING/SUCCEEDED/FAILED; public errors use codes, never driver text.
ColumnView: {sourceId,tableName,columnName,typeName,ordinalPosition,nullable}.
SubjectInput: {name,sourceId,fields:[{tableName,columnName}],deniedFields:[{tableName,columnName}],userIds:number[],shareScope:'NONE'|'AUTHORIZED_USERS'}. Exact allowlist, no wildcard. Denied fields always win. At most 1000 allowed/denied fields and 100 users; allowed fields must exist in latest successful snapshot.
SubjectView: {id,name,sourceId,fields,deniedFields,userIds,shareScope}; user-facing subject list excludes userIds and deniedFields via empty lists and includes only permitted fields.

Configuration: DATALENS_SOURCE_KEY is base64 32-byte AES key (required only when managing connections); DATALENS_SOURCE_ALLOWED_ENDPOINTS is comma-separated exact IPv4:port entries; default empty deny all. Numeric IPv4 eliminates DNS rebinding and URL parser ambiguity. Use dedicated read-only cluster credentials with server-enforced SELECT/metadata permissions. No real cluster is assumed.

Changing host/port/catalog/database/username or table scope revokes all associated subject field grants and user memberships. Administrators must reharvest and explicitly reauthorize. Password/name/enable-only updates preserve subject grants but clear the snapshot until reharvest. Harvests have a 30s application deadline, driver timeouts, 4 concurrent tasks per instance, and a recoverable 2-minute lease with generation checks to prevent stale publication.
