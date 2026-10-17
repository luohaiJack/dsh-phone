# dsh-phone —— 把本机 harness 接到手机上

在手机上**看**和**操作**你电脑上的 DSH harness：实时看会话在干什么、
直接在手机上继续对话、甚至开一个真终端敲命令。
电脑侧加一个桥接插件，手机侧一个原生 Android App，中间走 USB 或 tailnet。

同一个 App，两条路，自动选路：

```
                                    ┌── adb reverse ──USB──┐
harness (dsh web) ──/phone/* HTTP+SSE┤                      ├─▶ Android App
                                    └── tailscale serve ────┘
                                         （蜂窝网也能到）
```

- **在家**：USB 反向映射，手机回环即电脑，~15ms
- **在外**：Tailscale tailnet（WireGuard 端到端加密），蜂窝实测能打洞走 IPv6 直连
- 两条路都写进 App 的端点列表，按顺序探活，哪条通用哪条
- **会话的唯一权威存储始终在电脑上**（`~/.dsh/sessions`），手机只是视图，
  但手机上发的消息是**真正的写入**，电脑浏览器和手机看到的是同一份日志

开发与实测环境：小米 14 Pro / 澎湃 OS 3 / Android 16，但代码没有绑定机型，
任何 Android 7.0+（minSdk 26）的手机都能装。

完整设计取舍见 [`docs/技术路线.md`](docs/技术路线.md)（约 1400 行，含接口表与踩过的坑）。

---

## 想让它自动部署？把这句话丢给你的 AI

> 克隆这个仓库，按 `AGENTS.md` 把它部署到我这台电脑和手机上，遇到需要我
> 决定的地方先问我。

`AGENTS.md` 是**给 AI 看的部署手册**：每一步做什么、怎么验证、哪些事绝对不能做
（不 root、不解锁 Bootloader、不刷机），以及每一步失败时先查哪里。

---

## 目录

| 路径 | 作用 |
| --- | --- |
| `bridge/` | PC 侧桥接插件（Cordis Host 平面，注册 `/phone/*`） |
| `bridge/bridge-core.js` | 全部逻辑，宿主无关；静态/动态两条路共用同一份 |
| `bridge/lib/index.js` | 静态入口（生产） |
| `bridge/tools/gen-dynamic.mjs` | 内联成动态插件代码（开发时免重启调试） |
| `android/` | Kotlin + Compose 应用 |
| `scripts/` | 工具链准备 / 构建 / 安装 / 装载桥接 / 打通远程 / 全链路自检 |
| `docs/技术路线.md` | 架构、取舍、接口表、踩过的坑 |
| `tests/` | 离线验收：桥接终端接口（真 PTY）+ ANSI 解析 |
| `AGENTS.md` | 给 AI 的部署手册 |
| `CHANGELOG.md` | 版本记录（最新的在最上面） |

---

## 环境要求

| 需要什么 | 说明 |
| --- | --- |
| 一台跑 DSH 的电脑 | 需要 `dsh`（`@deepseek-ai/dsh`）已安装并能 `dsh web` 起来。**没有 harness 的话这个 App 没有意义** |
| JDK 21 | 构建 APK 用；`scripts/setup-toolchain.sh` 会装到用户目录，不需要 sudo |
| Android SDK 37 + build-tools | 同上，脚本装到 `~/android-sdk` |
| Gradle 9.8.1 | 同上，或直接用仓库里的 `./gradlew`（wrapper 已带） |
| adb | 装 APK、建 USB 反向映射 |
| 一部 Android 7.0+ 手机 | 打开「USB 调试」与「USB 安装」（小米/澎湃在开发者选项里） |
| （可选）Tailscale | 只有"在外面用"这一步需要，电脑和手机登同一账号 |

---

## 快速开始

```bash
# 0) 手机：连点「OS 版本」7 次开启开发者模式，然后打开三个开关：
#      「USB 调试」           adb 才认得到设备
#      「USB 安装」           否则 adb install 会被拒（INSTALL_FAILED_USER_RESTRICTED）
#      「USB 调试(安全设置)」  可选，只在你想让脚本模拟点击时才需要
#    插线后手机上弹「允许 USB 调试吗」→ 一律允许

# 1) 工具链（JDK21 + Android SDK 37 + Gradle，全装在用户目录，不需要 sudo）
scripts/setup-toolchain.sh

# 2) 把桥接装进 web profile（脚本会要求先停掉 dsh web），装完重启 dsh web
#    这一步会现场生成访问令牌 → bridge/.token（不进仓库，别外传）
scripts/enable-bridge.sh

# 3) 构建 APK
scripts/build-apk.sh

# 4) 建 USB 通道 + 安装 + 灌入端点/令牌 + 启动
scripts/install-to-phone.sh

# 5) 可选：打通在外访问（Tailscale，免 root）
scripts/enable-remote.sh
```

第 5 步做完后，App 里会有两个端点，在家自动走 USB、在外自动走 tailnet，
不需要手动切换。

**连不上时先跑自检**，它会指出是哪一段断了，而不是让你在手机上瞎试：

```bash
scripts/verify.sh
```

装载/卸载桥接：

```bash
scripts/enable-bridge.sh --status    # 当前装了没
scripts/enable-bridge.sh             # 装载（要求 dsh web 已停）
scripts/enable-bridge.sh --revert    # 回滚，harness 回到原样
```

---

## 桥接接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/phone/health` | 探活 |
| GET | `/phone/state` | 整机快照（模型 / 会话 / 任务 / goal） |
| GET | `/phone/events` | SSE：状态实时推送 |
| GET | `/phone/sessions/<id>/history` | 历史消息（已归一化） |
| GET | `/phone/sessions/<id>/live` | SSE：消息 + 模型输出增量 |
| GET | `/phone/workspaces` | 电脑上的历史 + 当前工作区，各自带会话 |
| POST | `/phone/workspaces/create` | 把已存在的目录登记成工作区 `{path,title?}` |
| GET | `/phone/fs/list?path=` | 浏览电脑上的目录（用于挑/建项目文件夹） |
| POST | `/phone/fs/mkdir` | 在电脑上新建文件夹 `{parent,name}` |
| POST | `/phone/sessions/<id>/prompt` | 发消息 `{text, receiptIds?}`（带附件凭据时一起发） |
| POST | `/phone/upload?sessionId=&name=` | 手机传文件进会话（请求体就是原始字节，返回 receiptId） |
| POST | `/phone/sessions/<id>/cancel` | 打断当前回合 |
| POST | `/phone/new` | 新建会话 `{cwd?}` |
| POST | `/phone/term/open` | 在电脑上开一个真终端（真 PTY）`{cols,rows,cwd?}` |
| GET | `/phone/term/<id>/stream` | SSE：终端输出（先补一段滚动缓冲，再接增量） |
| POST | `/phone/term/<id>/input` | 键盘输入 `{data}`（可含 `\n` 与控制字符） |
| POST | `/phone/term/<id>/signal` | 给前台进程组发信号 `{signal}`（Ctrl-C 等） |
| POST | `/phone/term/<id>/close` | 关掉这个终端 |
| GET | `/phone/term/list` | 当前活着的终端（排查用） |
| GET | `/phone/sessions/<id>/queue` | 还没轮到执行的排队消息 |
| `GET` | `/phone/debug/observe?sessionId=<id>` | **诊断**：`sessionQuery.observeSession` 返回的投影键名 + `userQuestions` 原文 |
| POST | `/phone/sessions/<id>/queue/<itemId>` | 改/撤/插话 `{action:'remove'\|'edit'\|'steer', text?}` |

自测（桥接起来之后）：

```bash
curl -s http://127.0.0.1:3080/phone/health
curl -s http://127.0.0.1:3080/phone/state | python3 -m json.tool | head -40
```

---

## App 能做什么

- **状态页**：连接状态与往返延迟、当前模型、会话/运行中统计、
  运行中的后台任务及已运行时长、进行中目标的轮次进度
- **会话页（按工作区）**：顶上三栏是**跨工作区的**「未读回复」「正在工作」「最近会话」；
  下面是电脑上的**工作区列表**（历史 + 当前），展开可看各自会话、直接在某个工作区再开会话。
  三栏互斥：还在跑的会话只算「正在工作」，跑完且你没看过才进「未读回复」，
  看过又停下来的进「最近会话」（手机上点开过的顺序，存在手机上，电脑端不变）
- **新建会话页**：浏览电脑上的目录（面包屑 + 家目录快捷键）、
  **在此新建文件夹**、填工作区标题，一键「在这里新建会话」——
  等于在电脑上建目录 + 登记成 harness 工作区 + 开一个会话
- **聊天页**：SSE 实时流式输出、工具调用与结果、打断按钮、
  harness 注入的上下文消息默认折叠（设置里可打开）；
  **历史可以往上无限翻**（滑到顶自动载入更早的），
  还有「↑ 上一条我的提问」一键跳到你上次说的话
- **传文件到会话**：聊天页点 **＋** 从手机里选一个文件 → 传到电脑 → 跟下一条消息一起发；
  可以只发附件不写字，发出去之前还能移除。上限 64 MB
- **通知栏**：可选常驻，关着 App 也能看到运行中的会话与任务
- **终端页（状态页进入）**：在手机上调这台电脑的**真 PTY**，
  而且是**真正的屏幕模型**（光标寻址 / 擦除 / 滚动区域 / 备用屏），
  所以 **vim、nano、htop、less 都能正常用**：
  - 命令行模式：写好一行发送；在会话里长按复制的命令，点「粘贴并跑」直接执行
  - **进 vim 自动切"逐键直通"**：隐藏发送框，用手机原生输入法打字，
    外加一排电脑键（Esc / ⏎ / Tab / ⌫ / 方向键 / Ctrl 锁存 / `:` `/` `|` `-`）
  - **滚动跟着光标走**：键盘弹起来也不会盖住你正在编辑的那一行
  - **选中复制**：点「选择」后点两行选范围（或长按某行）→「复制」到手机剪贴板；
    也可以 `--es termCopy 20` 深链抄最近 20 行
- **排队消息**：harness 还在跑的时候你发的消息会进队列 ——
  输入框上方显示「N 条排队中」，展开可以**逐条改 / 撤回 / 立刻插话**
- **设置页**：地址 / 令牌 / 显示开关 / 测试连接

---

## 在外面用（远程访问）

电脑侧不需要 sudo、不需要端口映射、不需要公网 IP：

```bash
scripts/enable-remote.sh
```

它做四件事：把 `tailscaled` 装成用户级 systemd 服务（userspace 模式，不建 TUN）、
等你登录 tailnet、用 `tailscale serve` 把 harness 端口发布到 tailnet、
把端点与令牌灌进手机 App。

前提：

- 手机装 [Tailscale](https://tailscale.com/download) 并用同一账号登录
- 建议把手机上的 Tailscale 设为「始终开启的 VPN」并关掉电池优化，
  否则澎湃 OS 会在后台把它杀掉（表现为 App 突然连不上）

`serve` 发布出来的是 **tailnet only**：公网到不了，只有你自己账号下的设备能连。
桥接另外还有一层令牌校验（`/phone/*` 全部要 `?token=`）。

## 工作区是怎么对上的

harness 的工作区（`workspaceRegistry`）就是「项目文件夹」，会话按
**canonical-cwd 索引**归属到工作区。实测有个必须注意的差别：

| 建会话的方式 | 会挂进工作区吗 |
| --- | --- |
| `sessionController.create({ workspaceId })` | ✅ 会 |
| `sessionController.create({ cwd })` | ❌ 不会（会话能跑，但工作区列表里看不见） |

所以桥接在只给 `cwd` 时会先 `workspaceRegistry.resolveByPath(cwd)` 反查，
查到就把 `workspaceId` 一起带上。

新建文件夹走的是 harness 的 `shell` 服务。这里有个坑：组合里的
`sandbox-policy` 默认是 `workspace-write` 且根是 harness 的 cwd，
工作区目录通常落在它**之外** —— 于是 `mkdir` 会被沙箱**静默丢弃**
（退出码 0 但文件没建出来）。桥接显式传 `danger-full-access` 策略，
并且建完用 `fs.stat` **复核**，不信退出码。

## 存储在哪

**会话的唯一权威存储始终是电脑上的 `~/.dsh/sessions`。**

手机不落任何会话数据 —— App 进程里只有内存视图，SharedPreferences 里只存
连接参数（端点、令牌、显示开关）。所以：

- 电脑上浏览器和手机看到的是同一份日志，天然同步
- 手机上发的消息走 `sessionController.prompt` 落进本机会话日志，是真正的写入
- 手机关掉/卸载都不影响会话；重装后重新灌一次端点即可

## 安全边界

- `adb install` 一个普通 APK，**不 root、不解锁 Bootloader、不刷机、不改系统分区**
- 桥接走 `adb reverse`，harness 始终只监听回环，**不监听 `0.0.0.0`**
- 明文 HTTP 只对 `127.0.0.1 / localhost / 10.0.2.2` 放行（`network_security_config.xml`）
- 桥接强制 `token` 校验：`/phone/*` 全部要求 `?token=`，无令牌/错令牌一律 401
- 令牌**不进仓库**：首次挂载时生成到 `bridge/.token`（`0600`，已 gitignore），
  `scripts/install-to-phone.sh` 读它并灌进 App
- 远程只经 `tailscale serve` 发布，且是 **tailnet only**，公网不可达
- ⚠️ 终端那一页等于把电脑的 shell 交出去，**令牌的强度就是这台电脑的强度**，
  别把它贴进聊天/截图里。怀疑泄露就删掉 `bridge/.token` 重启 harness，重新灌一次手机

卸载：`adb uninstall com.dsh.phone`。
撤销桥接：`dsh plugin --profile web remove <本仓库>/bridge` 后重启 `dsh web`
（或 `scripts/enable-bridge.sh --revert`）。

---

## 开发与测试

改完东西**别只靠眼看**，仓库里带了三层验收，从便宜到贵：

```bash
# 1) 离线（不需要 harness / 手机，约 25 秒，67 项）
#    桥接终端接口（真 PTY）+ 排队消息（镜像/改/撤/插话）+ 终端屏幕模型（含真机 vim 帧）
scripts/test-offline.sh

# 2) 在线全链路（桥接挂了没、令牌对不对、tailnet 通不通、手机认不认）
scripts/verify.sh

# 3) 手机上（能自动化的部分）
adb shell am start -n com.dsh.phone/.MainActivity --es tab sessions   # 直接落到某一页
adb shell am start -n com.dsh.phone/.MainActivity --es screen terminal
adb shell am start -n com.dsh.phone/.MainActivity --es termSend "ls -l ~"
adb shell uiautomator dump /sdcard/ui.xml   # 把屏上的文字读出来核对（比截图干净）
```

动态插件这条调试路径可以**不重启 harness** 就更新桥接：

```bash
node bridge/tools/gen-dynamic.mjs --minify --out /tmp/bridge-host.js
# 然后把 /tmp/bridge-host.js 的内容贴进 cordis_define 的 code.host
```

`bridge-core.js` 有一条硬纪律：**不许出现** `import / require / process / Buffer /
fetch / setTimeout` —— 它要能同时跑在"真 Node 进程"和"受限的动态宿主"里，
`gen-dynamic.mjs` 会对这些构造做硬校验。

---

## 已知限制

- **`adb reverse` 不稳定**：拔插数据线会失效，实测在系统重新协商 USB 时也可能被清掉
  （表现为 App 显示未连接）。重跑 `scripts/install-to-phone.sh` 恢复即可。
  App 已做了两层兜底：状态页显示往返延迟与错误原文；聊天页在掉线时显示横幅并自动重连，
  通道恢复后由 SSE 快照自动补齐历史，不用重启 App。
- 暂不支持从手机发图片/附件
- 不展示 diff、文件预览与工具卡片，`tool/result` 只显示截断文本
- 未做 WebView 兜底（成本很低，需要时可加）
- 远程延迟取决于链路：家里同网实测 240ms 直连、蜂窝 IPv6 打洞 58ms、
  走 DERP 中继则 ~300ms（中继节点都在境外，最近的洛杉矶 303ms）
- 终端页的屏幕模型覆盖"跑命令 + 编辑文本"够用的那部分 VT100；
  鼠标上报、完整字符集表、真 24 位光标形状没做（vim/htop/less 不受影响）
- APK 是 **debug 自签名**，只能 `adb install`，不能上应用商店；
  要正式分发就把 `app/build.gradle.kts` 里的 `signingConfig` 换成自己的 keystore

---

## 许可

[MIT](LICENSE) —— 随便用，出事别找我。
