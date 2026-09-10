#!/usr/bin/env bash
# 桌面端启动脚本（需 JDK 17+，自动解析 JavaFX 本地模块）
set -e
cd "$(dirname "$0")"
if [ -z "$JAVA_HOME" ]; then JAVA=java; else JAVA="$JAVA_HOME/bin/java"; fi
exec mvn javafx:run "$@"
