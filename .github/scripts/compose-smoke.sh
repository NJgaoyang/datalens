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
mkdir -p test-results/compose
cleanup() {
  status=$?
  trap - EXIT
  "${compose[@]}" logs --no-color > test-results/compose/services.log 2>&1 || true
  "${compose[@]}" down --volumes --remove-orphans || true
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
curl --fail --silent --max-time 5 http://127.0.0.1:8088/api/v1/system/info | jq --exit-status '.data.name == "DataLens" and .data.stage == "M03"' >/dev/null
[[ $(curl --silent --max-time 5 -o test-results/compose/denied.json -w '%{http_code}' http://127.0.0.1:8088/api/v1/charts) == 401 ]]
jq --exit-status '.code == "UNAUTHENTICATED"' test-results/compose/denied.json >/dev/null
[[ $(query 'SELECT COUNT(*) FROM sys_platform_setting;') == 3 ]]
expected_migrations=$(find server/src/main/resources/db/migration -maxdepth 1 -name 'V*__*.sql' | wc -l)
[[ $(query 'SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1;') == "$expected_migrations" ]]
[[ $(query 'SELECT COUNT(*) FROM ds_source;') == 0 ]]
query "INSERT INTO sys_platform_setting (setting_key, setting_value) VALUES ('ci.restart.probe', 'persisted');"
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
echo 'PASS: empty DB, Nginx/API, deny-by-default, same-volume container recreation, migration/data preservation, Redis failure and recovery.' | tee test-results/compose/result.txt
