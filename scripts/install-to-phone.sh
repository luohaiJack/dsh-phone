#!/usr/bin/env bash
# 建立 USB 通道 → 安装 APK → 灌入端点/令牌 → 拉起应用。
#
# 全部可逆，不碰手机系统分区：
#   adb reverse  把电脑的端口映射到手机回环（手机侧的 127.0.0.1:PORT = 电脑）
#   adb install  走系统正常的应用安装流程，等同手动点安装包
#   am start     顺带把「在家/在外」两个端点与令牌写进 App 设置
#
# 用法：
#   ./scripts/install-to-phone.sh [APK路径] [端口]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/android-sdk/platform-tools-dl/platform-tools/adb}"
APK="${1:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
PORT="${2:-3080}"
BRIDGE_PATCH="$ROOT/bridge/cordis.patch.yml"
BRIDGE_TOKEN_FILE="$ROOT/bridge/.token"

if [ ! -x "$ADB" ]; then
  echo "找不到 adb：$ADB" >&2
  echo "先跑 scripts/setup-toolchain.sh，或用 ADB=/path/to/adb 指定" >&2
  exit 1
fi

echo "== 1/5 检查设备 =="
"$ADB" devices -l
if [ "$("$ADB" devices | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
  echo "手机未就绪：确认已插线、已开「USB 调试」、并在手机上点了「允许」" >&2
  exit 1
fi
# 多设备（USB + tailnet）时要指明目标，避免 adb 因歧义报错
DEV="${DEV:-$("$ADB" devices | tail -n +2 | grep 'device$' | head -1 | awk '{print $1}')}"
echo "使用设备：$DEV"

echo
echo "== 2/5 建立端口反向映射 =="
"$ADB" -s "$DEV" reverse "tcp:$PORT" "tcp:$PORT"
"$ADB" -s "$DEV" reverse --list

echo
echo "== 3/5 安装 APK =="
if [ ! -f "$APK" ]; then
  echo "APK 不存在：$APK" >&2
  echo "先跑 scripts/build-apk.sh" >&2
  exit 1
fi
"$ADB" -s "$DEV" install -r "$APK"

echo
echo "== 4/5 灌入端点与令牌 =="
# 令牌优先取 bridge/.token（插件自己生成/保存的那份，不进仓库），
# 其次才是 cordis.patch.yml 里手填的值。
TOKEN=""
if [ -s "$BRIDGE_TOKEN_FILE" ]; then
  TOKEN="$(tr -d '\r\n' < "$BRIDGE_TOKEN_FILE")"
fi
if [ -z "$TOKEN" ]; then
  TOKEN="$(grep -oE "token: '[^']*'" "$BRIDGE_PATCH" 2>/dev/null | head -1 | sed "s/token: '//;s/'//" || true)"
fi
if [ -z "$TOKEN" ]; then
  echo "提示：还没有令牌。先跑一次 scripts/enable-bridge.sh（它会生成 bridge/.token），" >&2
  echo "      或重启一次 harness，插件挂载时也会生成。" >&2
fi
ENDPOINTS="http://127.0.0.1:$PORT"

# 跑过 scripts/enable-remote.sh 的话，把 tailnet 端点也排进列表，
# 这样出门在外不用改任何设置。
TS_BIN="$HOME/tailscale/tailscale_1.104.1_amd64/tailscale"
TS_SOCK="$HOME/.ts-userspace/tailscaled.sock"
if [ -x "$TS_BIN" ] && "$TS_BIN" --socket="$TS_SOCK" status >/dev/null 2>&1; then
  TS_DNS="$("$TS_BIN" --socket="$TS_SOCK" status --json 2>/dev/null \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('Self') or {}).get('DNSName','').rstrip('.'))" || true)"
  if [ -n "$TS_DNS" ]; then
    ENDPOINTS="$ENDPOINTS,http://$TS_DNS:$PORT"
  fi
fi

"$ADB" -s "$DEV" shell am start -n com.dsh.phone/.MainActivity \
  --es endpoints "$ENDPOINTS" \
  --es token "$TOKEN" >/dev/null
echo "   端点：$ENDPOINTS"
echo "   令牌：$( [ -n "$TOKEN" ] && echo '已设置' || echo '空' )"

echo
echo "== 5/5 启动应用 =="
"$ADB" shell am start -n com.dsh.phone/.MainActivity >/dev/null

cat <<EOF

完成。
  手机端端点： $ENDPOINTS
  电脑侧端口： $PORT
提示：拔插数据线后 adb reverse 会失效，重跑本脚本即可恢复。
      （在外时走 tailnet 那条，不受数据线影响）
EOF
