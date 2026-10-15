#!/usr/bin/env bash
# 把手机桥接插件永久装进 web profile。
#
# 为什么需要重启：web profile 的 patchReload 是 startup，新增 bundle 只在启动时参与组合。
# 装完重启一次，桥接就随 harness 常驻，临时实例/动态插件"重启即失效"的问题消失。
#
# 为什么要求先停掉 dsh web：`dsh plugin add` 底层是 pnpm，会重写 profile 的
# node_modules。正在运行的进程如果在写的中途懒加载某个模块，可能短暂出错。
# 停机安装可以完全避开这个窗口 —— 反正装完也必须重启。
#
# 用法：
#   scripts/enable-bridge.sh            装载桥接
#   scripts/enable-bridge.sh --revert   卸载并还原（装载后不对劲时用这个）
#   scripts/enable-bridge.sh --status   只看当前装了没
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE="$ROOT/bridge"
PROFILE="${PROFILE:-web}"
MODE="${1:-install}"

# 确保有一个访问令牌：插件的静态入口会在挂载时自己生成，
# 这里提前生成一份，好让安装脚本和用户马上就能看到它。
ensure_token() {
  local f="$BRIDGE/.token"
  if [ -s "$f" ]; then return 0; fi
  ( umask 077; head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$f" )
  chmod 600 "$f"
  echo "已生成访问令牌 → $f"
}

show_status() {
  local bundles
  bundles="$(dsh --profile "$PROFILE" --dump-config 2>/dev/null | grep -c 'phone-bridge' || true)"
  if [ "$bundles" -gt 0 ]; then
    echo "桥接已装入 $PROFILE profile。"
    dsh --profile "$PROFILE" --dump-config 2>/dev/null | grep -A4 '^- id: phone-bridge'
  else
    echo "桥接未装入 $PROFILE profile。"
  fi
}

if [ "$MODE" = "--status" ]; then
  show_status
  exit 0
fi

if [ "$MODE" = "--revert" ]; then
  if pgrep -f "dsh web" >/dev/null 2>&1; then
    echo "请先停掉 dsh web 再执行 --revert（原因同装载）。" >&2
    exit 1
  fi
  echo "== 从 $PROFILE profile 移除桥接 =="
  dsh plugin --profile "$PROFILE" remove "$BRIDGE" 2>/dev/null \
    || dsh plugin --profile "$PROFILE" remove dsh-phone-bridge
  echo
  show_status
  cat <<EOF

已还原。重启 dsh web 后桥接就没了（harness 回到原样）。
临时实例也可以一并杀掉：pkill -f dsh-phone-test
EOF
  exit 0
fi

if pgrep -f "dsh web" >/dev/null 2>&1; then
  cat <<EOF
检测到 dsh web 似乎正在运行。

请先在跑它的终端按 Ctrl-C 停掉，然后重新执行本脚本。
（停机安装可以避免 pnpm 重写 node_modules 时干扰正在运行的进程；
  反正装完也必须重启，停机安装不会多花时间。）
EOF
  exit 1
fi

ensure_token

echo "== 把 $BRIDGE 作为 bundle 加入 ${PROFILE} profile =="
dsh plugin --profile "$PROFILE" add "$BRIDGE"

echo
echo "== 校验组合结果 =="
if dsh --profile "$PROFILE" --dump-config 2>/dev/null | grep -q "phone-bridge"; then
  dsh --profile "$PROFILE" --dump-config 2>/dev/null | grep -A4 "^- id: phone-bridge"
  echo
  echo "（不满意可以回滚：scripts/enable-bridge.sh --revert）"
else
  echo "组合里没有 phone-bridge，检查 profile 的 dsh.profile.bundles" >&2
  exit 1
fi

cat <<EOF

令牌（手机端要用，别外传）：
    cat $BRIDGE/.token

安装完成，现在重启 harness：
    dsh ${PROFILE}

启动后自测：
    curl -s http://127.0.0.1:3080/phone/health

手机上：地址保持 http://127.0.0.1:3080，点「测试连接」。
别忘了 adb reverse（拔插数据线后会失效）：
    scripts/install-to-phone.sh
EOF
