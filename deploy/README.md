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
