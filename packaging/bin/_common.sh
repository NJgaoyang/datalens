#!/usr/bin/env bash
# Sourced by the runtime bin scripts. Do not run directly.
APP_HOME="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
PID_FILE="$APP_HOME/run/datalens.pid"
JAR_FILE="$APP_HOME/lib/datalens-server.jar"
CONF_FILE="$APP_HOME/conf/application.env"
LOG_FILE="$APP_HOME/logs/datalens.log"

app_pid_is_ours() {
  local pid="$1" command_line
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  if [[ -r "/proc/$pid/cmdline" ]]; then
    command_line="$(tr '\0' ' ' < "/proc/$pid/cmdline")"
  else
    command_line="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  fi
  [[ "$command_line" == *"$JAR_FILE"* ]]
}
