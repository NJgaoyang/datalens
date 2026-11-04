#!/usr/bin/env bash
set -Eeuo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/_common.sh"
umask 077

[[ -f "$JAR_FILE" ]] || { echo "JAR 文件不存在: $JAR_FILE" >&2; exit 1; }
[[ -f "$CONF_FILE" ]] || { echo "配置文件不存在: $CONF_FILE" >&2; exit 1; }
mkdir -p "$APP_HOME/logs" "$APP_HOME/run"
set -a
# Configuration is a shell assignment file; only use a trusted, locally edited copy.
source "$CONF_FILE"
set +a

: "${DATALENS_DB_URL:?缺少 DATALENS_DB_URL}"
: "${DATALENS_DB_USERNAME:?缺少 DATALENS_DB_USERNAME}"
: "${DATALENS_DB_PASSWORD:?请在 conf/application.env 中填写 MySQL 密码}"
# The released defaults are only suitable for a trusted first-install environment.
# Do not print passwords in logs; warn by credential class only.
if [[ "$DATALENS_DB_USERNAME" == "root" && "$DATALENS_DB_PASSWORD" == "123456" ]]; then
  echo "[安全提醒] 当前使用默认 MySQL root 连接凭据，仅适用于隔离的首次安装环境。" >&2
fi
if [[ "${DATALENS_BOOTSTRAP_USERNAME:-}" == "admin" && "${DATALENS_BOOTSTRAP_PASSWORD:-}" == "admin@123456" ]]; then
  echo "[安全提醒] 启用了默认系统管理员首次初始化口令，请首次登录后修改并清除 bootstrap 配置。" >&2
fi
if [[ -f "$PID_FILE" ]]; then
  old="$(cat "$PID_FILE")"
  if app_pid_is_ours "$old"; then
    echo "DataLens 已运行，PID: $old"; exit 0
  fi
  if [[ "$old" =~ ^[1-9][0-9]*$ ]] && kill -0 "$old" 2>/dev/null; then
    echo "PID 文件属于其他进程，请手动核查，不会结束该进程: $old" >&2; exit 1
  fi
  rm -f "$PID_FILE"
fi
JAVA_CMD="${JAVA_CMD:-java}"
command -v "$JAVA_CMD" >/dev/null 2>&1 || { echo "找不到 Java 运行时: $JAVA_CMD" >&2; exit 1; }
JAVA_XMS="${JAVA_XMS:-256m}"
JAVA_XMX="${JAVA_XMX:-1024m}"
[[ "$JAVA_XMS" =~ ^[0-9]+[mMgG]$ && "$JAVA_XMX" =~ ^[0-9]+[mMgG]$ ]] || {
  echo "JAVA_XMS 和 JAVA_XMX 必须是 256m、1g 这样的内存值" >&2; exit 1;
}

cd "$APP_HOME"
nohup "$JAVA_CMD" "-Xms$JAVA_XMS" "-Xmx$JAVA_XMX" -jar "$JAR_FILE" >>"$LOG_FILE" 2>&1 </dev/null &
pid=$!
printf '%s\n' "$pid" > "$PID_FILE"
echo "DataLens 启动中，PID: $pid"
# nohup may not have exec'd Java at the exact moment the shell records $!.
# Allow a short startup grace period before verifying its command line.
sleep 2
if ! command -v curl >/dev/null 2>&1; then
  echo "未检测到 curl，无法自动检查就绪状态，请执行 bin/status.sh"; exit 0
fi
for ((i=0; i<75; i++)); do
  if ! app_pid_is_ours "$pid"; then
    rm -f "$PID_FILE"
    echo "DataLens 启动失败，请检查 $LOG_FILE" >&2
    tail -n 20 "$LOG_FILE" >&2 || true
    exit 1
  fi
  if curl --fail --silent --max-time 2 "http://127.0.0.1:${DATALENS_PORT:-8088}/actuator/health/readiness" >/dev/null; then
    echo "DataLens 已就绪，访问地址: http://<服务器IP>:${DATALENS_PORT:-8088}"
    exit 0
  fi
  sleep 1
done
echo "进程已启动，但尚未达到就绪状态；请执行 bin/status.sh 和 bin/logs.sh" >&2
exit 1
