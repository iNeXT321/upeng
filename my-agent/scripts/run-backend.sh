#!/bin/bash
# =====================================================================
# 后端启动脚本：注入 .env 环境变量后以 JDK21 运行 Spring Boot
# 用法：bash scripts/run-backend.sh
# =====================================================================
set -e
cd "$(dirname "$0")/.."

# .env 注入（DEEPSEEK_API_KEY / AGENT_JWT_SECRET 等，不进代码仓库）
set -a
[ -f .env ] && source .env
set +a

# JDK 21（brew openjdk@21）
if [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
echo "[run-backend] JAVA_HOME=$JAVA_HOME, java=$(java -version 2>&1 | head -1)"

exec mvn -q -DskipTests spring-boot:run
