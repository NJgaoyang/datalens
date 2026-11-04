#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
cd "$ROOT"
for program in java jar mvn node npm tar; do
  command -v "$program" >/dev/null 2>&1 || { echo "缺少打包依赖: $program" >&2; exit 1; }
done
if ! java -version 2>&1 | head -n 1 | grep -Eq '"21([."]|$)'; then
  echo "打包需要 Java 21 JDK" >&2; exit 1
fi
if [[ "$(node -p 'process.versions.node.split(".")[0]')" != "22" ]]; then
  echo "打包需要 Node.js 22.x" >&2; exit 1
fi
[[ -f web/package-lock.json ]] || { echo "缺少 web/package-lock.json" >&2; exit 1; }

echo "[1/4] 编译 Vue 前端"
(cd web && npm ci --no-audit --no-fund && npm run build)
[[ -s web/dist/index.html ]] || { echo "前端 index.html 未生成" >&2; exit 1; }

echo "[2/4] 使用 dist profile 将前端嵌入 Spring Boot JAR"
mvn -B -ntp -f server/pom.xml -Pdist -DskipTests clean package
JAR="$ROOT/server/target/datalens-server.jar"
[[ -s "$JAR" ]] || { echo "后端 JAR 未生成" >&2; exit 1; }
jar tf "$JAR" | grep -Fx 'BOOT-INF/classes/static/index.html' >/dev/null || {
  echo "JAR 不包含前端首页，打包中止" >&2; exit 1;
}

echo "[3/4] 整理 conf / bin / lib"
STAGE="$(mktemp -d)"
trap 'rm -rf -- "$STAGE"' EXIT
mkdir -p "$STAGE/datalens/"{bin,conf,lib,logs,run} "$ROOT/dist"
cp "$JAR" "$STAGE/datalens/lib/datalens-server.jar"
cp packaging/conf/application.env "$STAGE/datalens/conf/application.env"
cp packaging/bin/*.sh "$STAGE/datalens/bin/"
cp packaging/README.md "$STAGE/datalens/README.md"
chmod 755 "$STAGE/datalens/bin/"*.sh
chmod 700 "$STAGE/datalens/conf" "$STAGE/datalens/logs" "$STAGE/datalens/run"
chmod 600 "$STAGE/datalens/conf/application.env"

echo "[4/4] 生成 tar.gz"
OUTPUT="$ROOT/dist/datalens-0.1.0-linux.tar.gz"
tar -czf "$OUTPUT" -C "$STAGE" datalens
echo "打包成功: $OUTPUT"
echo "目标 Linux 只需 Java 21、MySQL 8，不需要 Node、Maven、Redis、Nginx 或 Docker。"
