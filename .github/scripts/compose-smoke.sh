#!/usr/bin/env bash
# Disposable GitHub-hosted CI only. Never target an existing/shared project.
set -euo pipefail
trap 'echo "Compose acceptance failed at line $LINENO" >&2' ERR
[[ ${GITHUB_ACTIONS:-} == true && ${RUNNER_ENVIRONMENT:-} == github-hosted ]] || {
  echo 'This destructive cleanup script is restricted to disposable GitHub-hosted CI.' >&2
  exit 1
}
cd "$(dirname "$0")/../.."
export COMPOSE_PROJECT_NAME="datalens-ci-${GITHUB_RUN_ID:?}-${GITHUB_RUN_ATTEMPT:?}-$(openssl rand -hex 4)"
export DATALENS_DB_PASSWORD="$(openssl rand -hex 24)"
export DATALENS_MYSQL_ROOT_PASSWORD="$(openssl rand -hex 24)"
export DATALENS_WEB_PORT=8088
compose=(docker compose -f deploy/compose.yml)
original=("${compose[@]}")
mkdir -p test-results/compose
# Dumps contain synthetic fixture data only and are never uploaded as artifacts.
backup_dir=$(mktemp -d)
chmod 700 "$backup_dir"
restore_project="${COMPOSE_PROJECT_NAME}-restore"
restore=(docker compose -p "$restore_project" -f deploy/compose.yml -f "$backup_dir/restore.yml")
cat > "$backup_dir/restore.yml" <<YAML
services:
  server:
    image: ${COMPOSE_PROJECT_NAME}-server
  web:
    image: ${COMPOSE_PROJECT_NAME}-web
YAML
cleanup() {
  status=$?
  trap - EXIT
  "${original[@]}" logs --no-color > test-results/compose/services.log 2>&1 || true
  "${restore[@]}" logs --no-color > test-results/compose/restored-services.log 2>&1 || true
  "${restore[@]}" down --volumes --remove-orphans || true
  "${original[@]}" down --volumes --remove-orphans || true
  rm -rf "$backup_dir"
  exit "$status"
}
trap cleanup EXIT
query() {
  "${compose[@]}" exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "$1"' sh "$1"
}
wait_for_api() {
  for attempt in $(seq 1 90); do
    if body=$(curl --fail --silent --max-time 5 http://127.0.0.1:8088/actuator/health/readiness) &&
       jq --exit-status '.status == "UP" and (has("components") | not)' <<<"$body" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo 'Readiness did not become UP through Nginx.' >&2
  return 1
}
snapshot() {
  query 'SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank; SELECT setting_key, setting_value, created_at FROM sys_platform_setting ORDER BY setting_key;'
}
"${compose[@]}" config --quiet
"${compose[@]}" up -d --wait mysql redis
[[ $(query 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();') == 0 ]]
"${compose[@]}" up --build -d
wait_for_api
curl --fail --silent --max-time 5 http://127.0.0.1:8088/ > test-results/compose/index.html
grep -q 'id="app"' test-results/compose/index.html
curl --fail --silent --head http://127.0.0.1:8088/ > test-results/compose/html-headers.txt
grep -qi 'X-Frame-Options: DENY' test-results/compose/html-headers.txt
grep -qi "frame-ancestors 'none'" test-results/compose/html-headers.txt
curl --fail --silent --max-time 5 http://127.0.0.1:8088/api/v1/system/info | jq --exit-status '.data.name == "DataLens" and .data.stage == "M10"' >/dev/null
[[ $(curl --silent --max-time 5 -o test-results/compose/denied.json -w '%{http_code}' http://127.0.0.1:8088/api/v1/charts) == 401 ]]
jq --exit-status '.code == "UNAUTHENTICATED"' test-results/compose/denied.json >/dev/null
[[ $(query 'SELECT COUNT(*) FROM sys_platform_setting;') == 8 ]]
expected_migrations=$(find server/src/main/resources/db/migration -maxdepth 1 -name 'V*__*.sql' | wc -l)
[[ $(query 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;') == "$expected_migrations" ]]
[[ $(query 'SELECT COUNT(*) FROM ds_source;') == 0 ]]
query "INSERT INTO sys_platform_setting (setting_key, setting_value) VALUES ('ci.restart.probe', 'persisted');"
# Non-secret fixture proves restoration of identity/audit, exact strings and budgets.
query "INSERT INTO sys_user(username,password_hash,role,enabled) VALUES('restore_probe','disabled-not-a-password-hash','USER',FALSE);
INSERT INTO sys_audit_event(actor_id,action,resource_type,resource_id,outcome,request_id) SELECT id,'RECOVERY_FIXTURE','user',CAST(id AS CHAR),'SUCCESS','00000000-0000-0000-0000-000000000010' FROM sys_user WHERE username='restore_probe';
INSERT INTO model_daily_budget(budget_date,reserved_tokens) VALUES('2020-01-01',12345);
INSERT INTO sys_platform_setting(setting_key,setting_value) VALUES('ci.precision.probe','9007199254740993|1234567890.0123456789|恢复');"
snapshot > test-results/compose/before.txt
# Recreate ALL containers while retaining this project's MySQL volume.
"${compose[@]}" logs --no-color > test-results/compose/cold-start.log
"${compose[@]}" down
"${compose[@]}" up -d
wait_for_api
snapshot > test-results/compose/after.txt
diff -u test-results/compose/before.txt test-results/compose/after.txt
[[ $(query "SELECT setting_value FROM sys_platform_setting WHERE setting_key = 'ci.restart.probe';") == persisted ]]
# Readiness must reflect real Redis failure, then recover without mock success.
"${compose[@]}" stop redis
redis_down=false
for attempt in $(seq 1 30); do
  status=$(curl --silent --max-time 10 -o test-results/compose/redis-down.json -w '%{http_code}' http://127.0.0.1:8088/actuator/health/readiness || true)
  if [[ "$status" == 503 ]] && jq --exit-status '.status == "DOWN"' test-results/compose/redis-down.json >/dev/null; then
    redis_down=true
    break
  fi
  sleep 2
 done
[[ "$redis_down" == true ]] || { echo 'Redis outage did not produce HTTP 503 / DOWN.' >&2; exit 1; }
"${compose[@]}" start redis
wait_for_api
# A recovered metadata pool must not hide loss of the exclusive executor lease.
"${compose[@]}" restart mysql
"${compose[@]}" up -d --wait mysql
lease_down=false
for attempt in $(seq 1 30); do
  status=$(curl --silent --max-time 15 -o test-results/compose/lease-down.json -w '%{http_code}' http://127.0.0.1:8088/actuator/health/readiness || true)
  if [[ "$status" == 503 ]] && jq --exit-status '.status == "DOWN" and (has("components") | not)' test-results/compose/lease-down.json >/dev/null; then
    lease_down=true
    break
  fi
  sleep 2
done
[[ "$lease_down" == true ]] || { echo 'Lost executor lease was not reflected in readiness.' >&2; exit 1; }
"${compose[@]}" restart server
wait_for_api
# Quiesce all writers before a consistent backup. Restore into a NEW project/volume;
# retaining a volume and restarting alone is not a restore test.
"${compose[@]}" stop web server
dump() {
  "${compose[@]}" exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob --order-by-primary --skip-comments "$MYSQL_DATABASE"'
}
dump > "$backup_dir/metadata.sql"
test -s "$backup_dir/metadata.sql"
sha256sum "$backup_dir/metadata.sql" | cut -d ' ' -f1 > test-results/compose/backup-sha256.txt
"${compose[@]}" down
"${restore[@]}" up -d --wait mysql redis
[[ $("${restore[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();"') == 0 ]]
"${restore[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE"' < "$backup_dir/metadata.sql"
# Compare a full, deterministically ordered logical dump BEFORE startup recovery.
"${restore[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u "$MYSQL_USER" --single-transaction --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --hex-blob --order-by-primary --skip-comments "$MYSQL_DATABASE"' > "$backup_dir/restored.sql"
cmp "$backup_dir/metadata.sql" "$backup_dir/restored.sql"
"${restore[@]}" up --no-build -d
wait_for_api
# Select the restored project for existing read-only assertion helpers.
compose=(docker compose -p "$restore_project" -f deploy/compose.yml -f "$backup_dir/restore.yml")
snapshot > test-results/compose/restored.txt
diff -u test-results/compose/after.txt test-results/compose/restored.txt
[[ $(query "SELECT enabled FROM sys_user WHERE username='restore_probe';") == 0 ]]
[[ $(query "SELECT COUNT(*) FROM sys_audit_event WHERE action='RECOVERY_FIXTURE';") == 1 ]]
[[ $(query "SELECT reserved_tokens FROM model_daily_budget WHERE budget_date='2020-01-01';") == 12345 ]]
[[ $(curl --silent --max-time 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:8088/api/v1/charts) == 401 ]]
# Restore cleanup's original project reference too.
compose=(docker compose -f deploy/compose.yml)
echo 'PASS: empty DB, Nginx/API, deny-by-default, same-volume restart, migration/data preservation, Redis recovery, full logical backup equality, fresh-volume restore and restored app readiness.' | tee test-results/compose/result.txt
