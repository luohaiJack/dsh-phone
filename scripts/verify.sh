#!/usr/bin/env bash
# 全链路自检：把「手机 ⇄ harness」这条链的每一段都验一遍，打印一份报告。
#
# 在任何人报告"连不上"的时候，先跑这个 —— 它会指出是哪一段断了，
# 而不是让你在手机上瞎试。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# 令牌：优先 bridge/.token（插件自己生成的那份，不进仓库），
# 其次才是 cordis.patch.yml 里手填的值。
read_token() {
  local f="$ROOT/bridge/.token"
  if [ -s "$f" ]; then
    tr -d '\r\n' < "$f"
    return 0
  fi
  grep -oE "token: '[^']*'" "$ROOT/bridge/cordis.patch.yml" 2>/dev/null | head -1 | sed "s/token: '//;s/'//" || true
}

BRIDGE_PATCH="$ROOT/bridge/cordis.patch.yml"
ADB="${ADB:-$HOME/android-sdk/platform-tools-dl/platform-tools/adb}"
TS_BIN="${TS_BIN:-$HOME/tailscale/tailscale_1.104.1_amd64/tailscale}"
TS_SOCK="${TS_SOCK:-$HOME/.ts-userspace/tailscaled.sock}"
LOCAL_PORT="${LOCAL_PORT:-3080}"
TAILNET_PORT="${TAILNET_PORT:-3080}"

OK="✓"; BAD="✗"; WARN="!"
pass=0; fail=0

say()  { printf '%-4s %s\n' "$1" "$2"; }
good() { say "$OK" "$1"; pass=$((pass+1)); }
bad()  { say "$BAD" "$1"; fail=$((fail+1)); }
warn() { say "$WARN" "$1"; }

TOKEN="$(read_token)"

echo "================ DSH 手机端 全链路自检 ================"
echo

echo "--- 1. harness 进程 ---"
if ss -ltn 2>/dev/null | grep -q ":$LOCAL_PORT "; then
  good "端口 $LOCAL_PORT 在监听（harness 或其它服务）"
else
  bad "端口 $LOCAL_PORT 没有监听：dsh web 没跑？"
fi

echo
echo "--- 2. 桥接插件是否挂上（关键：没挂上就只是一台普通 harness）---"
BRIDGE_BODY="$(curl -sS --max-time 5 "http://127.0.0.1:$LOCAL_PORT/phone/health?token=$TOKEN" 2>/dev/null)"
case "$BRIDGE_BODY" in
  *dsh-phone-bridge*)
    good "桥接已挂载：$BRIDGE_BODY"
    ;;
  *)
    bad "端口 $LOCAL_PORT 上没有桥接"
    echo "       => 需要：停掉 dsh web → scripts/enable-bridge.sh → 重启 dsh web"
    ;;
esac

echo
echo "--- 3. 令牌校验（离开回环后令牌是唯一门禁）---"
if ! echo "${BRIDGE_BODY:-}" | grep -q dsh-phone-bridge; then
  warn "跳过：桥接都没挂上，令牌无从校验（见上一节）"
elif [ -z "$TOKEN" ]; then
  warn "令牌为空：只在纯 USB 回环场景下可接受"
else
  CODE_NO="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$LOCAL_PORT/phone/health" 2>/dev/null)"
  CODE_OK="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1:$LOCAL_PORT/phone/health?token=$TOKEN" 2>/dev/null)"
  [ "$CODE_NO" = "401" ] && good "无令牌被拒（401）" || bad "无令牌竟然不是 401，而是 $CODE_NO"
  [ "$CODE_OK" = "200" ] && good "正确令牌放行（200）" || bad "正确令牌没放行，返回 $CODE_OK"
fi

echo
echo "--- 3b. 桥接版本 与 终端能力 ---"
BRIDGE_VER="$(curl -sS --max-time 5 "http://127.0.0.1:$LOCAL_PORT/phone/health?token=$TOKEN" 2>/dev/null \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('version',''))" 2>/dev/null)"
if [ -n "$BRIDGE_VER" ]; then
  good "桥接已加载（v$BRIDGE_VER）"
else
  bad "读不到桥接版本 => 动态插件没挂上？"
fi
TERM_LIST="$(curl -sS --max-time 5 -H "x-phone-token: $TOKEN" "http://127.0.0.1:$LOCAL_PORT/phone/term/list" 2>/dev/null)"
if echo "$TERM_LIST" | grep -q '"ok":true'; then
  TERM_N="$(echo "$TERM_LIST" | python3 -c "import sys,json;print(len(json.load(sys.stdin).get('terminals',[])))" 2>/dev/null)"
  good "终端接口可用（当前活着的终端：${TERM_N:-?} 个）"
else
  bad "终端接口不可用 => 重载桥接插件（见 docs/技术路线.md §25）"
fi

echo
echo "--- 3c. 终端离线验收（真 PTY） ---"
if [ -x "$ROOT/scripts/test-offline.sh" ]; then
  if "$ROOT/scripts/test-offline.sh" >/tmp/dsh-phone-term-test.log 2>&1; then
    good "终端离线验收全通过（详见 /tmp/dsh-phone-term-test.log）"
  else
    bad "终端离线验收有失败 => /tmp/dsh-phone-term-test.log"
  fi
else
  warn "没有 scripts/test-offline.sh"
fi

echo
echo "--- 4. Tailscale 服务 ---"
if systemctl --user is-active --quiet tailscaled-userspace.service; then
  good "tailscaled-userspace.service 运行中"
else
  bad "tailscaled-userspace.service 没在跑 => scripts/enable-remote.sh"
fi

TS_DNS=""
if [ -x "$TS_BIN" ] && "$TS_BIN" --socket="$TS_SOCK" status >/dev/null 2>&1; then
  good "tailnet 已登录"
  TS_DNS="$("$TS_BIN" --socket="$TS_SOCK" status --json 2>/dev/null \
    | python3 -c "import sys,json;print((json.load(sys.stdin).get('Self') or {}).get('DNSName','').rstrip('.'))" 2>/dev/null)"
  echo "       本机节点：$TS_DNS"
  PEER="$("$TS_BIN" --socket="$TS_SOCK" status 2>/dev/null | grep -i xiaomi || true)"
  if [ -n "$PEER" ]; then
    case "$PEER" in
      *offline*) warn "手机在 tailnet 上显示 offline：手机上 Tailscale 被系统杀了？" ;;
      *idle*|*active*) good "手机在线：$(echo "$PEER" | awk '{print $2, $5, $6, $7}')" ;;
      *) good "手机节点存在" ;;
    esac
  else
    warn "tailnet 里没看到手机节点：手机装 Tailscale 并登录同一账号了吗？"
  fi
else
  bad "tailnet 未就绪 => scripts/enable-remote.sh"
fi

echo
echo "--- 5. serve 发布的端口 ---"
if [ -x "$TS_BIN" ]; then
  SERVE="$("$TS_BIN" --socket="$TS_SOCK" serve status 2>/dev/null || true)"
  if echo "$SERVE" | grep -q ":$TAILNET_PORT"; then
    good "tailnet 端口 $TAILNET_PORT 已发布"
    echo "$SERVE" | sed 's/^/       /'
    TARGET="$(echo "$SERVE" | grep -oE -- '--> tcp://127\.0\.0\.1:[0-9]+' | head -1 | grep -oE '[0-9]+$')"
    if [ -n "$TARGET" ] && [ "$TARGET" != "$LOCAL_PORT" ]; then
      warn "serve 指向 127.0.0.1:$TARGET，但桥接在 $LOCAL_PORT —— 重跑 scripts/enable-remote.sh 会自动纠正"
    fi
  else
    bad "tailnet 端口 $TAILNET_PORT 未发布 => scripts/enable-remote.sh"
  fi
fi

echo
echo "--- 6. 走 tailnet 自测（电脑打自己）---"
if curl -sS --max-time 20 -x socks5h://127.0.0.1:1055 \
     "http://$TS_DNS:$TAILNET_PORT/phone/health?token=$TOKEN" 2>/dev/null | grep -q dsh-phone-bridge; then
  good "tailnet 通路正常"
else
  warn "tailnet 自测没通过（SOCKS 出口未开、或 serve 指向不对）"
fi

echo
echo "--- 7. 手机侧 ---"
if [ ! -x "$ADB" ]; then
  warn "找不到 adb，跳过"
elif [ "$("$ADB" devices | tail -n +2 | grep -c 'device$')" -eq 0 ]; then
  warn "手机没连着（在外时正常，跳过）"
else
  good "adb 已连上手机（$("$ADB" devices | tail -n +2 | grep -c 'device$') 条通道）"
  # 多设备（USB + tailnet）时 adb 会因为歧义报错，所以逐条通道查
  APP_OK=0; REV_OK=0
  for D in $("$ADB" devices | tail -n +2 | grep 'device$' | awk '{print $1}'); do
    "$ADB" -s "$D" shell pm list packages 2>/dev/null | grep -q com.dsh.phone && APP_OK=1
    "$ADB" -s "$D" reverse --list 2>/dev/null | grep -q "tcp:$LOCAL_PORT" && REV_OK=1
  done
  [ "$APP_OK" -eq 1 ] && good "App 已安装" || bad "App 未安装 => scripts/install-to-phone.sh"
  if [ "$REV_OK" -eq 1 ]; then
    good "adb 反向映射在位（在家会走这条，最快）"
  else
    # 非致命：tailnet 那条照样能用，只是慢一点
    warn "没有 adb 反向映射：在家也会走 tailnet（能用，但慢）=> scripts/install-to-phone.sh"
  fi
fi

echo
echo "================ 结果：$pass 项通过，$fail 项失败 ================"
if [ "$fail" -gt 0 ]; then
  echo
  echo "修复顺序（前一步成功再做下一步）："
  echo "  1) 桥接没挂   → 停 dsh web；scripts/enable-bridge.sh；重启 dsh web"
  echo "  2) tailnet 没通 → scripts/enable-remote.sh"
  echo "  3) App 没配上  → scripts/install-to-phone.sh"
fi
exit "$([ "$fail" -eq 0 ] && echo 0 || echo 1)"
