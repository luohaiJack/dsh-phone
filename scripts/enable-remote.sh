#!/usr/bin/env bash
# 打通「在外面用手机访问家里 harness」的远程通道（Tailscale，免 root）。
#
# 做四件事，全部幂等：
#   1. 把 tailscaled 装成用户级 systemd 服务（userspace 模式，不需要 sudo）
#   2. 等 tailnet 就绪，打印本机节点名
#   3. 用 tailscale serve 把本地 harness 端口发布到 tailnet（仅 tailnet 内可达）
#   4. 把端点与令牌灌进手机 App（需要 adb 与手机连着）
#
# 为什么不建 TUN、不用 sudo：
#   userspace 模式下 tailscaled 不碰内核路由，靠 serve 在进程内做 TCP 代理；
#   这台机器 sudo 要密码，所以这是唯一能自动化的路子。
#
# 为什么 tailscaled 要挂代理：
#   controlplane.tailscale.com 在本机 DNS 被污染成 2001:2::fc，直连必超时。
set -euo pipefail

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

TS_DIR="${TS_DIR:-$HOME/tailscale}"
TS_VER="${TS_VER:-1.104.1}"
TS_ARCH="${TS_ARCH:-amd64}"
TS_HOME="${TS_HOME:-$HOME/.ts-userspace}"
TS_SOCK="$TS_HOME/tailscaled.sock"
TS_BIN="$TS_DIR/tailscale_${TS_VER}_${TS_ARCH}/tailscale"
TSD_BIN="$TS_DIR/tailscale_${TS_VER}_${TS_ARCH}/tailscaled"
UNIT="$HOME/.config/systemd/user/tailscaled-userspace.service"
UNIT_NAME="tailscaled-userspace.service"
LOCAL_PORT="${LOCAL_PORT:-3080}"
TAILNET_PORT="${TAILNET_PORT:-3080}"
PROXY="${PROXY:-http://127.0.0.1:7897}"
ADB="${ADB:-$HOME/android-sdk/platform-tools-dl/platform-tools/adb}"
BRIDGE_PATCH="$ROOT/bridge/cordis.patch.yml"

echo "== 0/4 探测哪个本地端口上有桥接 =="
TOKEN_EARLY="$(read_token)"
probe_port() {
  # 必须看响应体：harness 的 web 服务器对未匹配路由会回落到 SPA，
  # 于是没有桥接的端口也返回 200（HTML）。只看状态码会误判。
  local port="$1" body
  body="$(curl -sS --max-time 4 \
    "http://127.0.0.1:$port/phone/health?token=$TOKEN_EARLY" 2>/dev/null)"
  case "$body" in
    *dsh-phone-bridge*) return 0 ;;
    *) return 1 ;;
  esac
}
if probe_port "$LOCAL_PORT"; then
  echo "   $LOCAL_PORT 上有桥接，用它"
else
  # 真实 profile 还没重启时，桥接可能只跑在临时实例上
  for alt in 3099 3098 3097; do
    if probe_port "$alt"; then
      echo "   注意：$LOCAL_PORT 上没有桥接，改用临时实例端口 $alt"
      echo "   （把桥接装进真实 profile 并重启 dsh web 后，重跑本脚本会自动切回 $LOCAL_PORT）"
      LOCAL_PORT="$alt"
      break
    fi
  done
fi
if ! probe_port "$LOCAL_PORT"; then
  echo "   警告：没探测到任何带桥接的端口，仍按 $LOCAL_PORT 配置" >&2
fi

echo
echo "== 1/4 tailscaled（userspace，免 root）=="
if [ ! -x "$TSD_BIN" ]; then
  mkdir -p "$TS_DIR"
  TGZ="tailscale_${TS_VER}_${TS_ARCH}.tgz"
  curl -sSL -o "$TS_DIR/$TGZ" "https://pkgs.tailscale.com/stable/$TGZ"
  tar xzf "$TS_DIR/$TGZ" -C "$TS_DIR"
fi
mkdir -p "$TS_HOME" "$(dirname "$UNIT")"

cat > "$UNIT" <<EOF
[Unit]
Description=Tailscale (userspace, no-root) — 给 DSH 手机端提供外网通道
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
# controlplane.tailscale.com 在本机被 DNS 污染成 2001:2::fc，直连必超时；
# 走本机 HTTP 出口才能拿到 netmap。
Environment=HTTPS_PROXY=$PROXY
Environment=HTTP_PROXY=$PROXY
Environment=NO_PROXY=127.0.0.1,localhost
ExecStart=$TSD_BIN \\
  --tun=userspace-networking \\
  --state=$TS_HOME/state.json \\
  --socket=$TS_SOCK \\
  --port=41641 \\
  --socks5-server=127.0.0.1:1055 \\
  --no-logs-no-support
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now "$UNIT_NAME" >/dev/null 2>&1 || true

echo -n "   等待 tailnet 就绪"
for i in $(seq 1 30); do
  if "$TS_BIN" --socket="$TS_SOCK" status >/dev/null 2>&1; then break; fi
  echo -n "."
  sleep 2
done
echo

if ! "$TS_BIN" --socket="$TS_SOCK" status >/dev/null 2>&1; then
  cat <<EOF

tailnet 还没就绪。如果这是第一次在本机跑 Tailscale，需要先登录：

    $TS_BIN --socket=$TS_SOCK up --hostname=\$(hostname -s) --accept-dns=false

它会打印一个 https://login.tailscale.com/a/... 链接，浏览器打开授权即可。
EOF
  exit 1
fi

SELF_DNS="$("$TS_BIN" --socket="$TS_SOCK" status --json 2>/dev/null \
  | python3 -c "import sys,json;print((json.load(sys.stdin).get('Self') or {}).get('DNSName','').rstrip('.'))")"

echo "   本机节点：$SELF_DNS"

echo
echo "== 2/4 发布本地端口 $LOCAL_PORT 到 tailnet（仅 tailnet 内可达）=="
"$TS_BIN" --socket="$TS_SOCK" serve --bg --tcp="$TAILNET_PORT" "127.0.0.1:$LOCAL_PORT"
"$TS_BIN" --socket="$TS_SOCK" serve status | head -6

ENDPOINT="http://$SELF_DNS:$TAILNET_PORT"
echo
echo "== 3/4 远程端点 =="
echo "   $ENDPOINT"

echo
echo "== 4/4 把端点与令牌灌进手机 App =="
TOKEN="$(read_token)"
if [ ! -x "$ADB" ]; then
  echo "   跳过：找不到 adb（$ADB）"
elif ! "$ADB" get-state >/dev/null 2>&1; then
  echo "   跳过：手机没连着"
else
  "$ADB" shell am start -n com.dsh.phone/.MainActivity \
    --es endpoints "http://127.0.0.1:$LOCAL_PORT,$ENDPOINT" \
    --es token "$TOKEN" >/dev/null
  echo "   已写入：本机端点 + $ENDPOINT"
  echo "   在家会自动选 http://127.0.0.1:$LOCAL_PORT（USB），在外自动切到 tailnet"
fi

cat <<EOF

完成。
自测（电脑上走 tailnet 打自己）：
    curl -x socks5h://127.0.0.1:1055 "$ENDPOINT/phone/health?token=$TOKEN"

注意：
  - serve 是 "tailnet only"，公网到不了，只有你自己账号下的设备能连
  - 手机上的 Tailscale 要保持连接；建议在系统设置里把 Tailscale
    设为「始终开启的 VPN」并关掉它的电池优化，否则会被系统杀掉
EOF
