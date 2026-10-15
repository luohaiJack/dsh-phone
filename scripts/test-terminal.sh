#!/usr/bin/env bash
# 终端相关的离线验收（不需要 harness、不需要手机）。
#
#   1) bridge 侧：假 ctx + 真 `script`(PTY)，用真 HTTP 打一遍 /phone/term/*
#   2) 手机侧：AnsiParser / TermBuffer 的纯逻辑测试（含一段从真机抓下来的字节）
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

echo "================ 1/2  bridge 终端接口（真 PTY） ================"
if ! command -v node >/dev/null 2>&1; then
  echo "✗  找不到 node，跳过"
  fail=$((fail + 1))
else
  if node "$ROOT/tests/bridge/term-harness.mjs"; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
  fi
fi

echo
echo "================ 2/2  ANSI / TermBuffer ================"
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
  SRC="$ROOT/android/app/src/main/java/com/dsh/phone/Ansi.kt"
  java -cp "$KC:$STDLIB:$COROUTINES:$DAEMON:$ANNOT" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$STDLIB" \
    "$SRC" "$ROOT/tests/ansi/AnsiTest.kt" -d "$OUT" -nowarn 2>&1 | grep -E "^error|exception" || true
  if java -cp "$OUT:$STDLIB" AnsiTestKt; then
    pass=$((pass + 1))
  else
    fail=$((fail + 1))
  fi
  rm -rf "$OUT"
fi

echo
echo "================ 结果：$pass 组通过，$fail 组失败 ================"
exit $((fail == 0 ? 0 : 1))
