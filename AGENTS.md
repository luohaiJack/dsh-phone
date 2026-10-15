# AGENTS.md —— 部署手册（给 AI 看的）

> 你可以把这份文件当成"用户已经把仓库交给你，现在把它跑起来"的执行清单。
> 每一步都写了**做完怎么确认**；没确认就别往下走。
> 需要用户本人做的事（插线、点手机上的弹窗、Tailscale 登录、重启 `dsh web`）
> 必须明确说出来让他做，不要自己猜着往下跑。

## 0. 硬约束（先读，越界就是事故）

- ❌ **不 root、不解锁 Bootloader、不刷机、不改手机系统分区。**
  这个项目全程只用 `adb install` 装一个普通 APK，此外什么都不碰手机。
- ❌ **不要动用户的 harness 会话数据**（`~/.dsh/sessions`）。
  这个项目只读它、通过官方服务往里写消息，绝不直接改文件。
- ❌ **不要把 `bridge/.token` 的内容打印到聊天里、写进任何提交、或贴进日志。**
  它等价于这台电脑的 shell 钥匙。
- ❌ **不要在没有用户确认的情况下 `dsh web` 重启/杀掉**正在跑的 harness ——
  那会打断他正在进行的会话。装载桥接需要停机，所以**必须让用户自己重启**。
- ⚠️ 检测到用户正在用电脑干活（有正在运行的会话）时，先问一句再动手。

## 1. 先摸清环境（只读，不改任何东西）

```bash
dsh --version 2>/dev/null || echo "没有 dsh"        # 有没有 harness
dsh --profile web --dump-config 2>/dev/null | head  # 有没有 web profile
java -version 2>&1 | head -1                        # 有没有 JDK
adb devices -l                                      # 手机连上没有
node --version
```

判断：

| 现象 | 怎么办 |
| --- | --- |
| 没有 `dsh` | **停下来告诉用户**：这个 App 是给 DSH harness 用的，没有它装了也没用。问他要不要先装 harness |
| 有 `dsh`，没有 JDK / Android SDK | 跑 `scripts/setup-toolchain.sh`（装在用户目录，不需要 sudo） |
| `adb devices` 是空的 | 让用户插线、开「USB 调试」与「USB 安装」、在手机上点「允许」 |
| 手机是小米/澎湃 | 特别提醒：**「USB 安装」不开的话 `adb install` 会报 `INSTALL_FAILED_USER_RESTRICTED`** |

## 2. 部署（按顺序，每步确认）

```bash
scripts/setup-toolchain.sh      # ① 工具链（已有就跳过）
scripts/enable-bridge.sh        # ② 装桥接 —— 需要先停 dsh web，让用户来做
scripts/build-apk.sh            # ③ 构建 APK
scripts/install-to-phone.sh     # ④ 建 adb reverse + 安装 + 灌端点/令牌 + 启动
scripts/verify.sh               # ⑤ 全链路自检，必须 15 项全过
```

每步的"过关标志"：

| 步骤 | 过关标志 |
| --- | --- |
| ① | `javac -version` 与 `ls ~/android-sdk/platforms` 都有东西 |
| ② | 脚本打印出 `bridge/.token` 的路径；`dsh --profile web --dump-config` 里能看到 `phone-bridge` |
| ③ | 打印出 `app-debug.apk` 的路径与大小（约 12 MB） |
| ④ | `adb install` 输出 `Success`；手机上 App 已经起来 |
| ⑤ | `================ 结果：15 项通过，0 项失败 ================` |

第 ⑤ 步失败时**先看它是哪一组红的**，别乱试：

- `桥接已挂载` 红 → 桥接没进组合：确认 `dsh web` 是在 `enable-bridge.sh` **之后**才重启的
- `终端接口可用` 红 → 桥接版本旧了：让用户重启 `dsh web`
- `终端离线验收` 红 → 桥接代码本身有问题，看 `/tmp/dsh-phone-term-test.log`
- `tailnet 通路` 红 → 这一步是可选的，用户只想在家里用就直接跳过第 6 步

## 3. 可选：在外面用（远程）

```bash
scripts/enable-remote.sh
```

需要用户在两台设备上做两件事（你替他做不了）：

1. 电脑上完成 `tailscale up` 登录；
2. 手机上装 Tailscale、登**同一个账号**，并建议设为「始终开启的 VPN」+ 关掉电池优化
   （否则系统会在后台杀掉它，表现是 App 突然连不上）。

装完 App 里会有两个端点，在家自动走 USB、在外自动走 tailnet，不需要手动切。

## 4. 隐私检查（如果要提交/发布这个仓库）

```bash
# 令牌必须不在仓库里（应该只有 .token 这个被 ignore 的文件）
git ls-files | xargs grep -n "9b0d\|ghp_\|BEGIN.*PRIVATE" 2>/dev/null
# 个人路径 / 主机名 / tailnet 名
git ls-files | xargs grep -n "/home/\|/Users/\|\.ts\.net" 2>/dev/null
```

只应命中 `README.md` 里的占位示例（`dsh-home.xxx.ts.net` 这种带 `xxx` 的）。
命中别的就地脱敏。

## 5. 改代码时的不变量（别破坏它们）

1. `bridge/bridge-core.js` 里**不许**出现 `import / require / process / Buffer /
   fetch / setTimeout / setInterval` —— 它要能同时跑在真 Node 和受限的动态宿主里。
   定时器用 `every(ms, fn)`（内部走 `ctx.get('timer')`），
   编码用 Builtin 的 `TextDecoder`。
   改完跑 `node bridge/tools/gen-dynamic.mjs --out /tmp/x.js`，它会硬校验。
2. 手机的「未读 / 正在工作 / 最近会话」三栏**互斥**，判据只在
   `AppViewModel.refreshUnread()` 与 `refreshWorking()` 里，别在 UI 里另算一套。
3. 新增桥接接口时**必须加令牌校验**（`/phone/*` 统一走 `authorized()`），
   同步更新 README 的接口表，并往 `tests/bridge/routes-harness.mjs` 里加一行 ——
   出过一次事故：App 调 `/phone/model`、桥接实现的是 `/phone/models`，
   两边各自看着都对，切模型就一直是 404。
4. 任何"主机上的副作用"（PTY、监听、临时文件）都要在插件卸载时清理干净：
   见 `bridge-core.js` 里的 `dispose()`。
5. 前端/手机的滚动与跟随逻辑动之前先看 `docs/技术路线.md` §22/§23/§25.5 ——
   同一个坑踩过三次（"布局在动"和"人在动"分不清）。

## 6. 提交纪律

- 一个功能一个提交，message 写**为什么**，不写"update"。
- 提交前跑 `scripts/test-offline.sh`（93 项）+ `scripts/verify.sh`（15 项）；红的不要提交。
- 动过什么就写进 `CHANGELOG.md`：**最新的加在最上面**，写「为什么」不写「改了什么」。
- 涉及 UI 的改动，用 `adb shell uiautomator dump` 把屏上的文字读出来核对，
  **不要用截图**（会把用户屏幕上的隐私内容带进上下文）。
