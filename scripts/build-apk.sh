#!/usr/bin/env bash
# 构建 debug APK（自签名，可直接 adb install）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-$HOME/jdk21/jdk-21.0.12.1+1}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
# 优先用本机已装好的 Gradle（setup-toolchain.sh 装的那个），
# 没有就退回仓库自带的 wrapper —— 别人克隆下来不装 Gradle 也能直接构建。
GRADLE="${GRADLE:-$HOME/gradle-dl/gradle-9.8.1/bin/gradle}"
if [ ! -x "$GRADLE" ] && [ -x "$ROOT/android/gradlew" ]; then
  GRADLE="$ROOT/android/gradlew"
fi

if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "找不到 JDK21：$JAVA_HOME" >&2
  echo "先跑 scripts/setup-toolchain.sh，或用 JAVA_HOME=/path/to/jdk21 指定" >&2
  exit 1
fi
if [ ! -x "$GRADLE" ]; then
  echo "既没有本机 gradle，也没有 android/gradlew" >&2
  echo "先跑 scripts/setup-toolchain.sh" >&2
  exit 1
fi

cd "$ROOT/android"
"$GRADLE" assembleDebug "$@"

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo
ls -lh "$APK"
