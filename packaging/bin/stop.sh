#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/_common.sh"

if [[ ! -f "$PID_FILE" ]]; then
  echo "DataLens 未运行（无 PID 文件）"; exit 0
fi
pid="$(cat "$PID_FILE")"
if ! app_pid_is_ours "$pid"; then
  if [[ "$pid" =~ ^[1-9][0-9]*$ ]] && kill -0 "$pid" 2>/dev/null; then
    echo "PID $pid 属于其他进程，拒绝停止" >&2; exit 1
  fi
  rm -f "$PID_FILE"
  echo "清理了失效的 PID 文件"; exit 0
fi
echo "正在优雅停止 DataLens (PID $pid)"
kill -TERM "$pid"
for ((i=0; i<40; i++)); do
  if ! app_pid_is_ours "$pid"; then
    rm -f "$PID_FILE"
    echo "DataLens 已停止"; exit 0
  fi
  sleep 1
done
echo "超时：进程仍在运行。未强制 kill -9，请检查日志后人工处理。" >&2
exit 1
