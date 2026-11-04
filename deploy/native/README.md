# DataLens native Linux deployment (MySQL 8 / no Docker / no Redis)

Branch: `dev20261104-linux-native`. This is a controlled initial deployment path, **not production acceptance**. The system uses MySQL 8 for all metadata and login sessions (Spring Session JDBC). V11 is additive and leaves V1-V10 unchanged. Old Redis session cookies expire; users re-login. External StarRocks is optional at startup; later add its read-only account through the administrator UI.

## Requirements

Linux with Java 21, Maven 3.9.x, Node.js 22.x, npm, Git, Nginx and local MySQL 8 accessible on TCP 127.0.0.1:3306. Confirm versions with `java -version; mvn -version; node --version; nginx -v`. Install prerequisites from supported OS repositories. Do not run Docker or Redis.

### 1. Prepare MySQL

Run `mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -p` (enter the user-supplied password interactively), then:

```sql
CREATE DATABASE IF NOT EXISTS datalens
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

For this initial setup the requested local `root` credentials are used. Switch to a dedicated, least-privileged account before exposure. Take a verified backup before applying migrations to any nonempty database.

### 2. Checkout and build

```sh
sudo useradd --system --home-dir /opt/datalens --shell /usr/sbin/nologin datalens 2>/dev/null || true
sudo mkdir -p /opt/datalens /etc/datalens
sudo chown "$USER":datalens /opt/datalens
git clone --branch dev20261104-linux-native https://github.com/NJgaoyang/datalens.git /opt/datalens
cd /opt/datalens
mvn -B -ntp -f server/pom.xml -DskipTests package
(cd web && npm ci --no-audit --no-fund && npm run build)
sudo chown -R datalens:datalens /opt/datalens
```

If the directory already exists, inspect local changes and fetch/checkout the named branch rather than overwriting. The build command skips tests for provisioning; run and review CI separately.

### 3. Configuration (local secrets only)

```sh
sudo cp /opt/datalens/deploy/native/datalens.env.example /etc/datalens/datalens.env
sudo chown root:root /etc/datalens/datalens.env
sudo chmod 600 /etc/datalens/datalens.env
sudoedit /etc/datalens/datalens.env
```

Set `DATALENS_DB_PASSWORD` to the supplied local MySQL password; do not put it in Git. Set a unique first-run `DATALENS_BOOTSTRAP_PASSWORD` (12–72 UTF-8 bytes). Generate `DATALENS_SOURCE_KEY` using `openssl rand -base64 32` and retain it securely across restarts (lost key means saved StarRocks credentials cannot be decrypted). Existing database/credentials are never deleted or reset.

To use external StarRocks, set `DATALENS_SOURCE_ALLOWED_ENDPOINTS=IP:PORT` with its exact numeric FE IP and MySQL protocol port (often 9030). Log in as superadmin, add the StarRocks source, use a dedicated read-only user, test the connection, then harvest allowed table metadata and create an authorized subject. Check real StarRocks driver/TLS/SQL dialect compatibility before exposing queries. Do not point StarRocks fields at the metadata MySQL.

### 4. Start API using systemd

```sh
sudo cp /opt/datalens/deploy/native/datalens.service /etc/systemd/system/datalens.service
sudo systemctl daemon-reload
sudo systemctl enable --now datalens
curl --fail http://127.0.0.1:8080/actuator/health/readiness
sudo journalctl -u datalens -n 100 --no-pager
```

Flyway will create V1-V11 including `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES`. Readiness needs healthy MySQL and the exclusive query executor lease. Only one Java instance may use a metadata database.

After first admin initialization, remove the two `DATALENS_BOOTSTRAP_*` variables from the local env file and restart the service. Existing accounts persist.

### 5. Host Nginx and open UI

```sh
sudo cp /opt/datalens/deploy/native/nginx.conf /etc/nginx/conf.d/datalens.conf
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
curl --fail http://127.0.0.1:8088/actuator/health/readiness
```

Nginx deliberately listens on loopback HTTP only. From a desktop, use `ssh -L 8088:127.0.0.1:8088 USER@SERVER` and open `http://127.0.0.1:8088`. Keep `DATALENS_COOKIE_SECURE=false` **only** for localhost HTTP testing. For network/public access, use TLS with a vetted ingress and set `DATALENS_COOKIE_SECURE=true` (and correct proxy headers). Never publish the sample HTTP listener directly.

### 6. Verify MySQL and diagnose

```sh
mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -p -D datalens -e "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank; SHOW TABLES LIKE 'SPRING_SESSION%';"
systemctl is-active datalens nginx
```

Expected: 11 applied migrations, two JDBC session tables, readiness UP, working login through the same-origin Nginx listener and **no Redis process**. `Access denied` means TCP credential mismatch. `SOURCE_ENDPOINT_DENIED` means the external IP:port is not exactly allowed. `SOURCE_KEY_UNAVAILABLE` means missing/invalid AES key. Investigate Java logs with `journalctl -u datalens`; never paste secrets publicly.

Production release remains blocked until real StarRocks/provider validation, business accuracy tests, HTTPS/security review, backup/restore, quotas/retention and representative load tests complete (see `docs/M10-acceptance.md`).
