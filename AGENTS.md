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

> **改桥接不用再重启（2026-10-16 起，§61）**：桥接入口是薄壳 `bridge/lib/entry.js`，
> 它按 mtime+size 拼 query 去动态 import `bridge/lib/mount.js`（挂载层），
> mount 再同样方式 import `bridge-core.js`。于是改完 `mount.js` / `bridge-core.js`：
>
> 1. 把 `bridge-core.js` 里的 `VERSION` 加一（它是"这次改动生效了吗"的自检位）；
> 2. 用 `plugin_manager` 把 `include:phone-bridge` **关一次再开一次**；
> 3. `curl -s -H "x-phone-token: <token>" http://127.0.0.1:3080/phone/health`
>    —— `version` 变了就是生效了（**`dsh web` 进程不用重启**，实测 pid 不变）。
>
> 只有"改入口 `entry.js` 本身"才需要重启（极少；那时换新文件名再重启）。
>
> **本机现状（2026-10-15 起）**：桥接已经作为 profile bundle 装进 `web`
> （`~/.dsh/profiles/web/package.json` 的 `dependencies` 与
> `dsh.profile.bundles` 都有 `dsh-phone-bridge`，`node_modules` 里是指向
> 本仓库 `bridge/` 的符号链接）。**它随 `dsh web` 常驻，重启不再丢。**
>
> 手机连不上时第一步先打本机回环：
> `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3080/phone/health`
> —— **404 = 桥接没挂载**（查 `dsh --profile web --dump-config | grep phone-bridge`，
> 缺了就重跑 `scripts/enable-bridge.sh`）；**超时 = 网络/tailnet 问题**。
> 拔了数据线时手机走 tailnet：`tailscale --socket ~/.ts-userspace/tailscaled.sock serve status`，
> 手机上没连上看桥接 `/phone/diag` 的 `stateClients`。

第 ⑤ 步失败时**先看它是哪一组红的**，别乱试：

- `桥接已挂载` 红 → 桥接没进组合：确认 `dsh web` 是在 `enable-bridge.sh` **之后**才重启的
- `终端接口可用` 红 → 桥接版本旧了：让用户重启 `dsh web`
- `终端离线验收` 红 → 桥接代码本身有问题，看 `/tmp/dsh-phone-term-test.log`
- `tailnet 通路` 红 → 这一步是可选的，用户只想在家里用就直接跳过第 6 步

**页面左下角黄色「自动重连」→「重连失败」时先读这段**（2026-10-15 实测）：

- 这**不是网络问题**：浏览器与 harness 在同一台机器上走 `127.0.0.1` 的 WebSocket。
  客户端给一次连接"就绪"的窗口默认只有 **15 秒**
  （`@deepseek-ai/dsh-client-connection` 的 `recovery.generationReadyTimeoutMs`），
  宿主一忙就握手不完 → 连续失败。当前 profile 已放宽到 45 秒。
- **先别重启**：先打 `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3080/phone/health`。
  有响应（哪怕慢）说明进程活着、agent 也在跑，等它自己恢复；
  重启会打断正在跑的会话（历史上还把动态插件桥接弄丢过）。
- **别起第二个实例**：`~/.dsh/logs/startup-*.log` 里全是 `EADDRINUSE` 就是重复启动。
- 宿主有多忙看桥接 `/phone/diag` 的 `eventLoop`（1 秒 tick 的迟到量，
  `maxLagMs` 到几十秒就别怀疑网络了）与 `vmstat` 的 `cs`。

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
4. "电脑上明明有的信息"要**去看电脑是从哪拿的**，别在事件流里守株待兔 ——
   上下文窗口就是例子：harness 前端是从持久日志里 `findLast` 出来的，
   而我在等一条只在"上下文变化时"才写的实时事件，重启/冷会话就退化成"未知"。
   顺带：窗口按**模型**缓存（它是模型的属性，不是会话的）。
5. 进度/未读这类"按会话累计"的东西，**别只依赖根监听器** ——
   实测 `session/event` 只覆盖一部分会话（桥接自己 create 的会话到不了根），
   而单会话的 `follow()` 流对任何会话都成立。`diag` 里的事件类型直方图就是查这个的。
6. 任何"主机上的副作用"（PTY、监听、临时文件）都要在插件卸载时清理干净：
   见 `bridge-core.js` 里的 `dispose()`。
7. 前端/手机的滚动与跟随逻辑动之前先看 `docs/技术路线.md` §22/§23/§25.5 ——
   同一个坑踩过三次（"布局在动"和"人在动"分不清）。

## 6. 提交纪律

- 一个功能一个提交，message 写**为什么**，不写"update"。
- 提交前跑 `scripts/test-offline.sh`（9 组 386 项）+ `scripts/verify.sh`（15 项）；红的不要提交。
- 动过什么就写进 `CHANGELOG.md`：**最新的加在最上面**，写「为什么」不写「改了什么」。
- 涉及 UI 的改动，用 `adb shell uiautomator dump` 把屏上的文字读出来核对，
  **不要用截图**（会把用户屏幕上的隐私内容带进上下文）。
