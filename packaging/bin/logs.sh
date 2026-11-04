#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/_common.sh"
[[ -f "$LOG_FILE" ]] || { echo "尚无日志: $LOG_FILE" >&2; exit 1; }
tail -n 100 -f "$LOG_FILE"
