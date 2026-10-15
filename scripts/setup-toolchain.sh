#!/usr/bin/env bash
# 准备构建工具链：JDK21 + Android SDK(cmdline-tools/platform/build-tools) + Gradle。
# 全部装在用户目录，不需要 sudo，也不改动系统包。
set -euo pipefail

JDK_DIR="${JDK_DIR:-$HOME/jdk21}"
SDK_DIR="${SDK_DIR:-$HOME/android-sdk}"
GRADLE_DIR="${GRADLE_DIR:-$HOME/gradle-dl}"
GRADLE_VER="${GRADLE_VER:-9.8.1}"

mkdir -p "$SDK_DIR" "$GRADLE_DIR" "$JDK_DIR"

echo "== JDK 21 =="
if [ ! -x "$JDK_DIR"/jdk-21*/bin/java ] 2>/dev/null; then
  URL=$(curl -sSL "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=linux&vendor=eclipse" \
        | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['binary']['package']['link'])")
  curl -sSL -o "$JDK_DIR/jdk21.tar.gz" "$URL"
  tar xzf "$JDK_DIR/jdk21.tar.gz" -C "$JDK_DIR"
fi
JAVA_HOME="$(echo "$JDK_DIR"/jdk-21*)"
echo "JAVA_HOME=$JAVA_HOME"

echo "== Android cmdline-tools =="
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
  mkdir -p "$SDK_DIR/cmdline-tools"
  curl -sSL -o "$SDK_DIR/cmdline-tools.zip" \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q -o "$SDK_DIR/cmdline-tools.zip" -d "$SDK_DIR/cmdline-tools"
  mv "$SDK_DIR/cmdline-tools/cmdline-tools" "$SDK_DIR/cmdline-tools/latest" 2>/dev/null || true
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$SDK_DIR/cmdline-tools/latest/bin:$PATH"

echo "== SDK 组件 =="
yes | sdkmanager --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

echo "== Gradle $GRADLE_VER =="
if [ ! -x "$GRADLE_DIR/gradle-$GRADLE_VER/bin/gradle" ]; then
  curl -sSL -o "$GRADLE_DIR/gradle-$GRADLE_VER-bin.zip" \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  unzip -q -o "$GRADLE_DIR/gradle-$GRADLE_VER-bin.zip" -d "$GRADLE_DIR"
fi

echo
echo "工具链就绪："
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
"$SDK_DIR/platform-tools/adb" version | head -1
"$GRADLE_DIR/gradle-$GRADLE_VER/bin/gradle" --version | grep -E "^Gradle"
