#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/_common.sh"
if [[ ! -f "$PID_FILE" ]]; then
  echo "DataLens 未运行"; exit 1
fi
pid="$(cat "$PID_FILE")"
if ! app_pid_is_ours "$pid"; then
  echo "DataLens 未运行或 PID 已失效"; exit 1
fi
echo "DataLens 进程运行中，PID: $pid"
if command -v curl >/dev/null 2>&1; then
  port=8088
  if [[ -f "$CONF_FILE" ]]; then
    set -a
    source "$CONF_FILE"
    set +a
    port="${DATALENS_PORT:-8088}"
  fi
  curl --fail --silent --show-error --max-time 5 "http://127.0.0.1:$port/actuator/health/readiness" && printf '\n'
fi
