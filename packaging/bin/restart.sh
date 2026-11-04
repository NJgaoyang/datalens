#!/usr/bin/env bash
set -Eeuo pipefail
BIN_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
"$BIN_DIR/stop.sh"
"$BIN_DIR/start.sh"
