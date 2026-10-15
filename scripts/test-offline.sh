#!/usr/bin/env bash
# 离线验收（不需要 harness、不需要手机，约 20 秒）。
#
#   1) bridge 侧：假 ctx 起真 HTTP，把 /phone/* 打一遍
#        tests/bridge/term-harness.mjs    终端（真 `script` 当 PTY 后端）
#        tests/bridge/queue-harness.mjs   排队消息（镜像 / 改 / 撤 / 插话）
#   2) Android 侧：AnsiParser / TermScreen 的纯逻辑测试（含真机字节回放）
#
# 为什么要有这两个：终端这条链路上"看起来对"的东西太多了 ——
#   * 转义序列被 chunk 切断、
#   * 中文被切成半个字符、
#   * CRLF 里的 \r 把整行抹掉（★ 这个真发生过，只有"真机字节"那条用例抓到了）、
#   * Ctrl-C 之后 shell 还活着吗、
#   * 插件 reload 有没有把 PTY 漏在宿主里。
# 这些都得跑起来才看得见，所以固化成脚本。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pass=0
fail=0

echo "================ 1/2  bridge 接口 ================"
if ! command -v node >/dev/null 2>&1; then
  echo "✗  找不到 node，跳过"
  fail=$((fail + 1))
else
  for h in "$ROOT"/tests/bridge/*-harness.mjs; do
    echo "--- $(basename "$h") ---"
    if node "$h"; then
      pass=$((pass + 1))
    else
      fail=$((fail + 1))
    fi
    echo
  done
fi

echo
echo "================ 2/2  ANSI / 屏幕模型 ================"
# 用 Gradle 缓存里的 kotlin-compiler-embeddable，不额外装东西
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"
KC="$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name '*.jar' 2>/dev/null | sort | tail -1)"
STDLIB="$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib" -name 'kotlin-stdlib-2*.jar' 2>/dev/null | sort | tail -1)"
COROUTINES="$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' 2>/dev/null | sort | tail -1)"
DAEMON="$(find "$CACHE/org.jetbrains.kotlin/kotlin-daemon-embeddable" -name '*.jar' 2>/dev/null | sort | tail -1)"
ANNOT="$(find "$CACHE/org.jetbrains/annotations" -name 'annotations-13*.jar' 2>/dev/null | sort | tail -1)"

if [ -z "$KC" ] || [ -z "$STDLIB" ]; then
  echo "✗  找不到 kotlin 编译器（看 $CACHE），跳过"
  fail=$((fail + 1))
else
  OUT="$(mktemp -d)"
  SRC="$ROOT/android/app/src/main/java/com/dsh/phone"
  java -cp "$KC:$STDLIB:$COROUTINES:$DAEMON:$ANNOT" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$STDLIB" \
    "$SRC/Ansi.kt" "$SRC/TermScreen.kt" "$ROOT/tests/ansi/AnsiTest.kt" -d "$OUT" -nowarn 2>&1 \
    | grep -E "error:|exception" || true
  if java -cp "$OUT:$STDLIB" AnsiTestKt; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
  fi

  # 终端状态文案：要连 Models.kt 一起编（TermState 在那儿），
  # 它引用了 org.json，所以拿 android.jar 当编译期 classpath
  ANDROID_JAR="$(ls "$HOME"/android-sdk/platforms/*/android.jar 2>/dev/null | tail -1)"
  if [ -n "$ANDROID_JAR" ]; then
    OUT2="$(mktemp -d)"
    java -cp "$KC:$STDLIB:$COROUTINES:$DAEMON:$ANNOT" \
      org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
      -classpath "$STDLIB:$ANDROID_JAR" \
      "$SRC/Ansi.kt" "$SRC/TermScreen.kt" "$SRC/Models.kt" "$ROOT/tests/ansi/StateTest.kt" -d "$OUT2" -nowarn 2>&1 \
      | grep -E "error:|exception" || true
    if java -cp "$OUT2:$STDLIB:$ANDROID_JAR" StateTestKt; then
      pass=$((pass + 1))
    else
      fail=$((fail + 1))
    fi
    rm -rf "$OUT2"
  else
    echo "（跳过状态文案验收：没找到 android.jar）"
  fi
  rm -rf "$OUT"
fi

echo
echo "================ 结果：$pass 组通过，$fail 组失败 ================"
exit $((fail == 0 ? 0 : 1))
