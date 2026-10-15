# harness 自身的一个问题：页面「自动重连 → 重连失败」

> 本文是一次事故的完整留痕（2026-10-15）。结论先给：
> **这不是网络问题，也不是用户操作问题；是 harness 客户端的连接「就绪窗口」
> 默认只有 15 秒，而它衡量的其实是宿主事件循环有多忙。**
> 机器一忙 → 窗口内握不完手 → 连续失败 → 界面显示「重连失败」→
> 用户以为 harness 死了，只能重启；而"重启"这一动作会打断正在跑的会话。
>
> 本文包含：现象、如何证明不是网络、根因、为什么把用户逼到只能重启、
> 四档解决方案（本机已做前两档）、给 harness 上游的建议、以及一手证据。

---

## 目录

- [一、现象](#一现象)
- [二、先证明"不是网络、也不是你在乱按"](#二先证明不是网络也不是你在乱按)
- [三、根因：连接「就绪窗口」默认 15 秒](#三根因连接就绪窗口默认-15-秒)
- [四、为什么会把用户逼到"只能重启"](#四为什么会把用户逼到只能重启)
- [五、解决方案（按代价从低到高）](#五解决方案按代价从低到高)
  - [5.1 放宽就绪窗口（本机已生效）](#51-放宽就绪窗口本机已生效)
  - [5.2 装上"宿主有多忙"的观测（已实现，待重启生效）](#52-装上宿主有多忙的观测已实现待重启生效)
  - [5.3 操作纪律：看到黄色重连先别重启](#53-操作纪律看到黄色重连先别重启)
  - [5.4 可选：systemd 用户单元托管（含副作用清单）](#54-可选systemd-用户单元托管含副作用清单)
- [六、给 harness 上游的建议（可直接提 issue）](#六给-harness-上游的建议可直接提-issue)
- [七、本机改动清单与验证](#七本机改动清单与验证)
- [附录 A：一手证据与复现命令](#附录-a一手证据与复现命令)

---

## 一、现象

用户侧看到的：

1. harness 页面**左下角出现黄色「自动重连」**；
2. 过一会儿变成**「重连失败」**；
3. 页面上的输出"卡住不动"——正在回答的那一轮**看起来像被打断了**；
4. 于是用户**重启 `dsh web`**（因为在界面上没有别的出路）。

第 3 条是最有欺骗性的一条：用户的原话是
**"你的思考直接被打断从而停止输出"**。而事后从会话日志看，
**agent 那一轮一直在正常干活**（每几秒一条 `tool/call` / `tool/result`）。

也就是说：**断的是"浏览器 ⇄ 宿主"这条连接，不是 agent**。
用户看到的是"输出流没了"，不是"工作停了"。

### 为什么这个问题对本项目（手机 App）格外重要

- 手机 App 的**全部能力**都建立在 harness 的 HTTP/服务面上；宿主一卡，
  手机端的轮询也会超时（本项目已用"状态轮询兜底"缓解，见 `docs/技术路线.md` §44）。
- 历史上这次"重启"直接**杀掉了当时的动态插件桥接**（当时桥接还不是常驻 bundle），
  手机端于是彻底连不上 —— 见 `docs/技术路线.md` §45。
  **一个 harness 侧的连接问题，最后表现成"手机小程序坏了"。**

---

## 二、先证明"不是网络、也不是你在乱按"

浏览器与 harness 跑在**同一台机器**上，连接走 `127.0.0.1` 的 WebSocket
（API Gateway 的浏览器载体）。所以"网络条件好不好"与它无关；
真正决定成败的是**宿主进程能不能及时把连接握手完**。

当时能查到的日志，与查不到的东西：

| 查了什么 | 结果 | 说明 |
| --- | --- | --- |
| `~/.dsh/logs/dsh-web.log` | 只有重启标记 | harness **不写连接日志** |
| `~/.dsh/logs/startup-*.log`（4 份） | **全是 `EADDRINUSE`** | 旧实例还在跑时又起了一个 |
| 会话日志里的中断/取消事件 | **0 条** | agent 从未被打断 |
| `turn/start` : `turn/end` | 55 : 54 | 差的正是当时**在跑**的那一轮 |
| 监听 3080 的进程 | 同一 pid，**35 分钟未重启** | 进程没崩，也不是被 OOM 杀掉 |

**结论**：进程活着、agent 在干活、没有任何中断记录。
"查不到日志"本身就是这件事的一部分 —— 现在没有任何地方记录
"客户端为什么连不上"，所以只能靠上层指标反推。

---

## 三、根因：连接「就绪窗口」默认 15 秒

`@deepseek-ai/dsh-client-connection`（客户端连接插件）的 Config
（用 Config Inspect 读的 live schema）：

```js
{
  recovery: {
    backoffBaseMs: 500,
    backoffFactor: 2,
    backoffMaxMs: 10000,
    generationReadyWarnMs: 3000,
    generationReadyTimeoutMs: 15000,   // ★ 一次连接"就绪"只给 15 秒
  },
  trustedHosts: [],
  cookieMaxAgeDays: 30,
  maxRequestBodyBytes: 314572800,
}
```

超时后客户端抛：

```
connection generation was not ready within 15000ms
```

然后按 `backoffBaseMs × backoffFactor^n`（上限 `backoffMaxMs`）重试。
**负载持续时就会连续失败 —— 这就是界面上的「重连失败」。**

### 这台机器当时有多忙（这才是"网络条件好"却失败的真正原因）

| 指标 | 读数 | 含义 |
| --- | --- | --- |
| `vmstat` 的 `cs` | 一度 **56071 /秒** | 每秒几万次上下文切换：大量短命进程 |
| `journalctl --user` | 每秒**几十个** `systemd-run` scope | 多个 agent 会话在并发跑，每次工具调用都拉一个 scope |
| swap | **12.4 GiB / 31 GiB** | 内存吃紧；`/proc/pressure/memory` 记到过 `full` 全停（开机两日累计约 21 秒） |
| harness 进程自身 | RSS 894 MB、CPU 22.5% | 单线程事件循环，任何长任务都会顶住它 |
| 桌面常驻 | 3×ChatGPT、Chrome、Zotero、Claude | 单机同时压着若干 GB 级应用 |

另外，那 4 份 `EADDRINUSE` dump 说明**当时还有重复启动**：
同一时刻只能有一个实例占 3080，第二个必然起不来。

---

## 四、为什么会把用户逼到"只能重启"

把这条链路串起来，就能看出问题出在**产品行为**，而不是用户误判：

1. 界面只给一个**黄色的"自动重连"**，没有原因、没有进度、没有"宿主很忙"的区分；
2. 重试失败后变成**"重连失败"** —— 语义上等于"这条路没了"，用户只能重启；
3. 而**重启会打断正在跑的会话**（in-flight 那一轮的 `tool/call` 没有 `result`，
   下次对话时 harness 会告诉模型"该调用被中断、结果未知"）；
4. 如果桥接是**动态插件**（本项目历史上的形态），重启还会**顺手弄丢手机端**。

**所以真正该被修的是三点**：让慢≠失败、让用户看得见原因、
让"客户端掉线"不要牵连正在跑的 agent。

---

## 五、解决方案（按代价从低到高）

### 5.1 放宽就绪窗口（本机已生效）

写进 profile 的补丁层（`~/.dsh/profiles/web/cordis.patch.yml`）：

```yaml
# 注意：这两项在插件的 Config 里是 recovery 子对象，
# 平铺在 config 下不会生效（schema 里 recovery.generationReadyTimeoutMs 默认 15000）
- id: connection
  config:
    recovery:
      generationReadyWarnMs: 8000      # 8 秒以上记一条 warn，便于发现"慢但在恢复"
      generationReadyTimeoutMs: 45000  # 45 秒才判失败：机器忙不再等于连接失败
```

- **浏览器要刷新一次页面**才拿到新参数（这份配置随页面引导下发）。
- 校验组合结果：`dsh --profile web --dump-config | grep -A6 dsh-client-connection`。

### 5.2 装上"宿主有多忙"的观测（已实现，待重启生效）

本项目桥接插件加了一张**事件循环卡顿表** —— 它的 1 秒 tick 就活在宿主
事件循环里，**迟到多少 = 宿主当时多忙**。放在 `/phone/diag`：

```json
"eventLoop": {
  "tickMs": 1000, "lastLagMs": 12, "maxLagMs": 8421, "avgLagMs": 37,
  "over100ms": 9, "over1s": 2,
  "worst": [ { "at": 1792080000000, "ms": 8421 } ]
}
```

查看：

```bash
T=$(cat bridge/.token)
curl -s -H "x-phone-token: $T" http://127.0.0.1:3080/phone/diag \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['eventLoop'])"
```

**下次再看到黄色重连，先看这张表**：`maxLagMs` 若到几十秒，
就不要再怀疑网络了 —— 是这台机器被压住了。

### 5.3 操作纪律：看到黄色重连先别重启

```bash
# 有响应（哪怕很慢）= 进程活着、agent 也在跑 → 等它自己恢复
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3080/phone/health
```

- **404** = 桥接没挂载（部署问题；`dsh --profile web --dump-config | grep phone-bridge`）；
- **超时/拒绝** = 网络或 tailnet 问题（拔线时看 `tailscale serve status`）；
- **200（可能慢）** = 一切正常，只是界面那一下没连上 → **别重启**。

另外两条：

- **别起第二个实例**：`~/.dsh/logs/startup-*.log` 里全是 `EADDRINUSE` 就是重复启动。
- **降负载**：关掉用不上的 GB 级应用；别同时开多个重活会话；
  构建 / 批量测试挑没人用界面的时候做（本项目自己的 `build-apk.sh`、
  `test-offline.sh` 每次要起两个 Kotlin 编译器 JVM）。

### 5.4 可选：systemd 用户单元托管（含副作用清单）

好处：真崩了自愈；日志进 journal；手机端因为桥接已常驻也会自己连回来。
代价（**上手前要知道的现象**）：

| 现象 | 说明 |
| --- | --- |
| 打断正在跑的会话 | 重启 = in-flight 那一轮被截断（日志里留下"有 call 无 result"） |
| 确定性故障会变成 failed 单元 | 打满速率限制后进入 `start-limit-hit`，需 `systemctl --user reset-failed` |
| Ctrl-C / `pkill` 失效且"打地鼠" | 停只能用 `systemctl --user stop`；被 kill 后 systemd 会再拉起来 |
| 环境不一样（最隐蔽） | 不继承交互 shell；`dsh` 的 shebang 是 `#!/usr/bin/env node` → 必须给 `PATH`；`secrets.env` 里的 `export` 语法 systemd **不认** |
| 与手动启动冲突 | 服务在跑时再手动 `dsh web` 就是 `EADDRINUSE` |
| 启动 URL 的 token 每次变 | 新设备要从 `journalctl --user -u dsh-web -n 20 \| grep -o 'http://[^ ]*'` 取 |

推荐的写法（`Restart=on-failure` 比 `always` 稳：干净退出不重启）：

```ini
[Unit]
Description=DSH web harness
After=network-online.target

[Service]
Type=simple
WorkingDirectory=%h
Environment=PATH=%h/.local/share/nvm/v24.15.0/bin:/usr/local/bin:/usr/bin:/bin
EnvironmentFile=-%h/.config/dsh/dsh-web.env    # 另建一份纯 KEY=value
ExecStart=%h/.local/share/nvm/v24.15.0/bin/dsh web
Restart=on-failure
RestartSec=5
StartLimitIntervalSec=60
StartLimitBurst=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=default.target
```

前置条件：`loginctl enable-linger $USER`（本机 `Linger=yes` 已开）。
**装之前要用户明确同意** —— 它改变的是"怎么启动 harness"这件事。

---

## 六、给 harness 上游的建议（可直接提 issue）

按"投入小、收益大"排序：

1. **写连接日志**。客户端与宿主两侧都应记录连接生命周期
   （打开/关闭/失败原因/就绪耗时），至少把
   `generation was not ready within Nms` 这条写进 journal。
   现在**一条都没有**，事后完全无法取证。
2. **慢 ≠ 失败**。就绪超时应是自适应的（例如宿主上报事件循环延迟时自动放宽），
   或至少把"连接被拒"与"握手慢"分开报错。
3. **界面说人话**。黄色重连旁边给出原因（如"宿主事件循环卡顿 8.4s"）
   与一个"诊断"入口，而不是一个孤零零的「重连失败」。
4. **客户端掉线不要牵连 agent**。本轮输出流断了不该让用户以为工作停了；
   重连后应能**补发**断线期间的输出。
5. **中断要留痕**。会话日志里应当有显式的 `turn/interrupted` 事件
   （本次事故里中断类事件 0 条，导致"到底有没有被打断"要靠 `turn/start` 与
   `turn/end` 的计数差来推断）。
6. **把 recovery 参数暴露到设置界面**（现在只存在于 schema 里，
   用户不可能知道有 `generationReadyTimeoutMs` 这个旋钮）。
7. **`EADDRINUSE` 要给一句人话**：现在是"2 required plugins did not activate" +
   一大坨 dump；应该说"已经有一个实例在跑（pid …），请勿重复启动"。
8. **给一份官方"作为服务运行"的配方**（systemd 用户单元 + 环境文件），
   因为用户就是把它跑在桌面工作站上、并且会以为"坏了就重启"。

---

## 七、本机改动清单与验证

| 改动 | 位置 | 生效方式 |
| --- | --- | --- |
| 放宽连接就绪窗口（45s / warn 8s） | `~/.dsh/profiles/web/cordis.patch.yml` | 组合树已吃到；**浏览器刷新一次**生效 |
| 桥接常驻（不再怕重启） | profile `package.json` + `node_modules` 符号链接 | 已生效（`/phone/health` 200） |
| 事件循环卡顿表 `eventLoop` | `bridge/bridge-core.js`（本仓库） | 下次 `dsh web` 重启生效 |
| 排查口径与纪律 | `AGENTS.md`、`docs/技术路线.md` §46 | — |

验证命令：

```bash
# 桥接活着吗（本机回环；404=没挂载，超时=网络）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3080/phone/health

# 宿主有多忙（重启后可用）
curl -s -H "x-phone-token: $(cat bridge/.token)" \
  http://127.0.0.1:3080/phone/diag | python3 -m json.tool | grep -A9 eventLoop

# 重复启动的证据
ls -la ~/.dsh/logs/startup-*.log
```

---

## 附录 A：一手证据与复现命令

```bash
# 1) harness 自己的日志（只有重启标记；startup dump 只在启动失败时写）
cat ~/.dsh/logs/dsh-web.log
grep -oE "EADDRINUSE[^\"']*" ~/.dsh/logs/startup-*.log | sort -u

# 2) 会话日志里根本没有"中断"事件（本会话为例）
F=$(ls ~/.dsh/sessions/*/session-<id>/session.v4.jsonl.zstd)
zstdcat "$F" | python3 -c "
import sys,json,collections
c=collections.Counter()
for l in sys.stdin:
    l=l.strip()
    if l: c[json.loads(l).get('type','')]+=1
print({k:v for k,v in c.items() if 'turn' in k or 'interrupt' in k})"

# 3) 进程没重启过（对比 etimes 与事故时间）
ss -ltnp | grep 3080
ps -o pid,etimes,lstart,cmd -p <pid>

# 4) 宿主机有多忙
vmstat 1 3              # 看 cs（上下文切换）与 si/so（换页）
cat /proc/pressure/memory   # PSI：full 那一行是"整机停住"的累计
journalctl --user -b --since "1 hour ago" | grep -c systemd-run

# 5) 客户端为什么判失败（浏览器 F12 → Console；刷新后阈值会变成 8000）
#    connection generation was not ready within 8000ms
```
