#!/usr/bin/env bash
# 让电脑能通过网络（Tailscale tailnet）调试手机，不依赖数据线。
#
# 背景：这台电脑的 tailscaled 跑在 userspace 模式（为了免 root，不建 TUN），
# 内核没有到 100.64.0.0/10 的路由，所以"手机能连电脑、电脑连不到手机"。
# 脚本用 tailscaled 的 SOCKS5 出口搭一个用户态 TCP 桥，把本地端口
# 转发到手机的 adbd —— adb 本身不支持 SOCKS，所以这层桥是必须的。
#
# 用法：
#   1) 手机插一次数据线（只需一次）
#   2) scripts/adb-over-tailnet.sh setup     # 打开手机的 TCP adbd 并连上
#   3) 拔线，之后 adb 依然可用
#
#   关闭（强烈建议不用时关掉）：
#      scripts/adb-over-tailnet.sh off
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/android-sdk/platform-tools-dl/platform-tools/adb}"
TS_BIN="${TS_BIN:-$HOME/tailscale/tailscale_1.104.1_amd64/tailscale}"
TS_SOCK="${TS_SOCK:-$HOME/.ts-userspace/tailscaled.sock}"
SOCKS_PORT="${SOCKS_PORT:-1055}"
LOCAL_PORT="${LOCAL_PORT:-15555}"
ADB_TCP_PORT="${ADB_TCP_PORT:-5555}"
BRIDGE_PID="$HOME/.dsh-adb-bridge.pid"

phone_ip() {
  "$TS_BIN" --socket="$TS_SOCK" status --json 2>/dev/null | python3 -c "
import sys,json
d=json.load(sys.stdin)
for v in (d.get('Peer') or {}).values():
    if 'xiaomi' in (v.get('HostName','')+v.get('DNSName','')).lower():
        ips=[i for i in (v.get('TailscaleIPs') or []) if ':' not in i]
        if ips: print(ips[0]); break
"
}

start_bridge() {
  local ip="$1"
  if [ -f "$BRIDGE_PID" ] && kill -0 "$(cat "$BRIDGE_PID")" 2>/dev/null; then
    echo "桥已在运行（pid $(cat "$BRIDGE_PID")）"
    return
  fi
  nohup python3 "$ROOT/scripts/tailnet-tcp-bridge.py" \
    "$LOCAL_PORT" "$ip" "$ADB_TCP_PORT" --socks-port "$SOCKS_PORT" \
    > "$HOME/.dsh-adb-bridge.log" 2>&1 &
  echo $! > "$BRIDGE_PID"
  sleep 2
  echo "桥已启动：127.0.0.1:$LOCAL_PORT -> $ip:$ADB_TCP_PORT（pid $(cat "$BRIDGE_PID")）"
}

stop_all() {
  if [ -f "$BRIDGE_PID" ]; then
    kill "$(cat "$BRIDGE_PID")" 2>/dev/null || true
    rm -f "$BRIDGE_PID"
    echo "桥已停"
  fi
  "$ADB" disconnect "127.0.0.1:$LOCAL_PORT" 2>/dev/null || true
}

case "${1:-setup}" in
  off|stop)
    stop_all
    if "$ADB" get-state >/dev/null 2>&1; then
      "$ADB" usb 2>/dev/null || true
      echo "已让手机 adbd 回到 USB 模式（TCP 监听关闭）"
    fi
    exit 0
    ;;
  status)
    echo -n "桥："; [ -f "$BRIDGE_PID" ] && kill -0 "$(cat "$BRIDGE_PID")" 2>/dev/null \
      && echo "运行中 pid $(cat "$BRIDGE_PID")" || echo "未运行"
    echo -n "手机 tailnet IP："; phone_ip
    echo -n "adb 设备："; "$ADB" devices | tail -n +2 | tr '\n' ' '; echo
    exit 0
    ;;
esac

IP="$(phone_ip)"
if [ -z "$IP" ]; then
  echo "在 tailnet 上找不到手机（名字里带 xiaomi 的节点）。" >&2
  echo "确认手机 Tailscale 已登录且在跑，然后重试。" >&2
  exit 1
fi
echo "手机 tailnet IP：$IP"

# 第一步需要在 USB 上执行（adb tcpip 只能从已有连接下发）
if "$ADB" get-state >/dev/null 2>&1; then
  echo "== 打开手机 TCP adbd（端口 $ADB_TCP_PORT）=="
  "$ADB" tcpip "$ADB_TCP_PORT"
  sleep 2
  "$ADB" disconnect >/dev/null 2>&1 || true
else
  cat <<EOF
手机当前不在 USB 上。打开 TCP adbd 有两条路：

  A) 插一次数据线，重跑本脚本；之后拔线也一直能用（重启手机失效）
  B) 手机开发者选项 → 打开「无线调试」→ 用「使用配对码配对设备」，
     把屏幕上的 IP:端口 和 6 位配对码给我

EOF
  exit 1
fi

start_bridge "$IP"
echo "== 通过 tailnet 连接手机 =="
"$ADB" connect "127.0.0.1:$LOCAL_PORT"
sleep 1
"$ADB" devices -l
echo
"$ADB" shell getprop ro.product.model 2>/dev/null || echo "(连上了但取不到型号，检查授权弹窗)"
cat <<EOF

现在可以拔数据线了，adb 走 tailnet 继续可用。

⚠️ 安全提醒：
  adb tcpip 会让 adbd 监听**所有网卡**，不只是 Tailscale。
  也就是说手机连到公共 WiFi 时，同一网络的人可能连上你的 adb（等于 shell）。
  不用的时候请执行：
      scripts/adb-over-tailnet.sh off
  或者至少别让手机连不可信的 WiFi。
EOF
