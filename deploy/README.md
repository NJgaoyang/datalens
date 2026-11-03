# Development Docker stack

This stack is for local development and disposable CI acceptance, not production. It starts the existing Java application, Vue frontend behind Nginx, MySQL and Redis. No model or StarRocks configuration is required or supplied. Only Nginx is published, on 127.0.0.1:8088; database, Redis and Java ports remain internal.

Prerequisites: Docker Engine with Docker Compose v2, and network access to official image registries, Maven Central and npm.

From the repository root:

```sh
cp deploy/.env.example deploy/.env
# Edit deploy/.env and replace both development passwords. Do not commit this file.
docker compose --env-file deploy/.env -f deploy/compose.yml up --build -d
curl --fail http://127.0.0.1:8088/actuator/health/readiness
```

Open http://127.0.0.1:8088. M00 deliberately has no usable login or business modules. MySQL metadata uses the named mysql-data volume. Normal shutdown (`docker compose --env-file deploy/.env -f deploy/compose.yml down`) preserves it. Restart the Java application with the same command prefix followed by `restart server`.

Only for data you intend to discard, `down --volumes` deletes the development database. Never point these tools at production services. Production TLS, authentication, secrets, backups and deployment hardening are outside this development stack.

The GitHub workflow runs `bash .github/scripts/compose-smoke.sh`. The script requires GitHub-hosted CI, creates a unique disposable project and random test passwords, and removes its containers/volume on exit. It verifies empty-database startup, Nginx/API access, denied business routes, unchanged Flyway history/data after an actual Java-process restart against the same MySQL volume, and Redis readiness failure/recovery. Logs are retained as a CI artifact. It does not deploy anywhere.

## M07 optional model configuration

No provider/key is required for startup, readiness or saved-chart refresh. Spring AI's model library is used only for schema generation; no provider starter is auto-configured.

- `DATALENS_MODEL_KEY`: independent Base64-encoded 32-byte AES-GCM wrapping key. No fallback. Preserve securely across restarts; changing it without a controlled re-encryption invalidates saved credentials. Do not reuse datasource keys.
- `DATALENS_MODEL_ALLOWED_ENDPOINTS`: comma-separated exact full HTTPS completion URLs, blank denies all. Current adapter intentionally accepts only public numeric IPv4 addresses with trusted TLS certificates containing that IP SAN. Hostnames, internal/reserved addresses, URL credentials/query/fragment, redirects and system proxies are rejected. A trusted IP-SAN gateway is required for ordinary hostname-only provider services. Never disable TLS verification to work around this boundary.
- Add the wrapping key and endpoint allowlist through deployment secret/configuration management. The superadmin form sets provider endpoint/model, write-only API key, timeout and token budget. Changing endpoint never forwards an existing key to the new recipient.

No real provider was contacted during development. Before a live smoke test, authorize the exact provider/account, secure credential setup, synthetic payload and maximum spend; review applicable terms. Real semantic/business data transmission needs its own sufficient authorization. Token reservations are conservative limits, not currency billing; provider billing and protocol/TLS acceptance remain unverified.

V8 migration is additive. Rollback application code only while preserving model tables/encrypted credentials and budget rows; do not drop budget records to reset spending. Keep the original wrapping key for recovery. No destructive migration is included.
