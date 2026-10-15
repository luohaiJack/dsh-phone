/**
 * DSH ⇄ 手机 桥接核心（平台无关、零依赖纯 JS）
 *
 * 同一份代码有两个宿主：
 *  1. 静态插件：bridge/lib/index.js 以 ESM 引入本文件（生产路径，随 dsh web 一起启动）
 *  2. 动态插件：bridge/tools/gen-dynamic.mjs 把本函数体原样内联进 cordis_define 的 code.host
 *     （开发路径，进程内即时生效，重启即失效）
 *
 * 因此：本文件内不得出现 import / require / process / Buffer / fetch / setTimeout
 * 等宿主机全局，只能通过 ctx.get() 拿 Cordis Service，并调用宿主对象上的方法。
 * 同理不使用 WebSocket（动态宿主内拿不到 crypto 做握手），一律走 SSE。
 */

export function createBridge(ctx, opts) {
  /* ------------------------------------------------------------------ *
   * 0. 配置与依赖
   * ------------------------------------------------------------------ */
  const cfg = opts || {}
  const TOKEN = typeof cfg.token === 'string' ? cfg.token : ''
  const BASE = typeof cfg.base === 'string' && cfg.base ? cfg.base : '/phone'
  const NAME = 'dsh-phone-bridge'
  const VERSION = 4

  const webServer = ctx.get('webServer')
  if (webServer === undefined) {
    console.error('[' + NAME + '] webServer service missing; bridge not mounted')
    return
  }
  // 硬依赖：没有会话控制器就没有桥接的意义
  const sc = ctx.get('sessionController')
  // 其余全部按可选处理，缺失时降级而不是崩溃
  const sessionsSvc = ctx.get('sessions')
  const titleSvc = ctx.get('sessionTitle')
  const jobsSvc = ctx.get('jobs')
  const goalsSvc = ctx.get('goals')
  const agentsSvc = ctx.get('agents')
  const modelSvc = ctx.get('agentDefaultModel')
  const timerSvc = ctx.get('timer')
  // 工作区注册表：harness 自己的"项目文件夹"概念，会话按 canonical-cwd 自动归属
  const workspaceSvc = ctx.get('workspaceRegistry')
  // 目录浏览/新建：复用 harness 自己的浏览后端，避免另造一套 fs 访问
  const dirPicker = ctx.get('directoryPickerController')

  /* ------------------------------------------------------------------ *
   * 1. 基础设施：AbortSignal 替身 / 响应封装 / 定时器
   * ------------------------------------------------------------------ */

  /**
   * 动态宿主里没有 AbortController。DSH 的 Remote 接口只把 signal 当鸭子类型用
   * （读 .aborted、挂 abort 监听），所以这里给一个最小实现。
   */
  function makeSignal() {
    const listeners = []
    const signal = {
      aborted: false,
      reason: undefined,
      onabort: null,
      addEventListener(type, fn) {
        if (type === 'abort' && typeof fn === 'function') listeners.push(fn)
      },
      removeEventListener(type, fn) {
        if (type !== 'abort') return
        const i = listeners.indexOf(fn)
        if (i >= 0) listeners.splice(i, 1)
      },
      dispatchEvent() {
        return true
      },
      throwIfAborted() {},
    }
    signal.abort = function abort(reason) {
      if (signal.aborted) return
      signal.aborted = true
      signal.reason = reason
      for (let i = 0; i < listeners.length; i += 1) {
        try {
          listeners[i].call(signal, { type: 'abort' })
        } catch (err) {
          /* 监听器异常不能影响其它监听器 */
        }
      }
    }
    return signal
  }

  function corsHeaders(extra) {
    const head = {
      'access-control-allow-origin': '*',
      'access-control-allow-headers': 'content-type,authorization,x-phone-token',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'cache-control': 'no-store',
    }
    if (extra) {
      const keys = Object.keys(extra)
      for (let i = 0; i < keys.length; i += 1) head[keys[i]] = extra[keys[i]]
    }
    return head
  }

  function sendJson(res, status, value) {
    let body
    try {
      body = JSON.stringify(value)
    } catch (err) {
      body = JSON.stringify({ ok: false, error: 'serialize-failed: ' + String(err && err.message) })
      status = 500
    }
    res.writeHead(status, corsHeaders({ 'content-type': 'application/json; charset=utf-8' }))
    res.end(body)
  }

  function sendText(res, status, text) {
    res.writeHead(status, corsHeaders({ 'content-type': 'text/plain; charset=utf-8' }))
    res.end(text)
  }

  /** 用宿主注入的 timer Service 做可回收定时器；拿不到就退化为「不启动」。 */
  function every(ms, fn) {
    if (timerSvc && typeof timerSvc.interval === 'function') {
      try {
        return timerSvc.interval(fn, ms)
      } catch (err) {
        /* 落到下面的空实现 */
      }
    }
    return function noop() {}
  }

  function safe(fn, fallback) {
    try {
      return fn()
    } catch (err) {
      return fallback
    }
  }

  /* ------------------------------------------------------------------ *
   * 2. 请求解析
   * ------------------------------------------------------------------ */

  function readBody(req) {
    return new Promise(function (resolve) {
      let done = false
      const chunks = []
      let size = 0
      function finish(value) {
        if (done) return
        done = true
        resolve(value)
      }
      req.on('data', function (chunk) {
        size += chunk.length
        if (size > 2 * 1024 * 1024) {
          finish(null)
          return
        }
        chunks.push(chunk)
      })
      req.on('end', function () {
        finish(BufferConcat(chunks))
      })
      req.on('error', function () {
        finish(null)
      })
    })
  }

  function BufferConcat(chunks) {
    if (chunks.length === 0) return ''
    let total = 0
    for (let i = 0; i < chunks.length; i += 1) total += chunks[i].length
    const buf = new Uint8Array(total)
    let off = 0
    for (let i = 0; i < chunks.length; i += 1) {
      buf.set(chunks[i], off)
      off += chunks[i].length
    }
    const decoder = new TextDecoder('utf-8')
    return decoder.decode(buf)
  }

  function parseBody(req) {
    return readBody(req).then(function (text) {
      if (!text) return {}
      try {
        const value = JSON.parse(text)
        return value && typeof value === 'object' ? value : {}
      } catch (err) {
        return {}
      }
    })
  }

  /** 把 `req.url` 拆成 path 段与 query。 */
  function parseUrl(rawUrl) {
    const raw = typeof rawUrl === 'string' ? rawUrl : '/'
    const q = raw.indexOf('?')
    const path = q >= 0 ? raw.slice(0, q) : raw
    const search = q >= 0 ? raw.slice(q + 1) : ''
    const query = {}
    if (search) {
      const pairs = search.split('&')
      for (let i = 0; i < pairs.length; i += 1) {
        if (!pairs[i]) continue
        const eq = pairs[i].indexOf('=')
        const k = eq >= 0 ? pairs[i].slice(0, eq) : pairs[i]
        const v = eq >= 0 ? pairs[i].slice(eq + 1) : ''
        try {
          query[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' '))
        } catch (err) {
          query[k] = v
        }
      }
    }
    let clean = path
    while (clean.length > 1 && clean.charAt(clean.length - 1) === '/') clean = clean.slice(0, -1)
    return { path: clean, query: query }
  }

  /** 相对 BASE 的路径段，例如 /phone/session/abc/live -> ['session','abc','live'] */
  function segments(path) {
    let rest = path
    if (rest.indexOf(BASE) === 0) rest = rest.slice(BASE.length)
    const parts = rest.split('/')
    const out = []
    for (let i = 0; i < parts.length; i += 1) {
      if (!parts[i]) continue
      try {
        out.push(decodeURIComponent(parts[i]))
      } catch (err) {
        out.push(parts[i])
      }
    }
    return out
  }

  function authorized(req, query) {
    if (!TOKEN) return true
    const presented =
      (query && query.token) ||
      req.headers['x-phone-token'] ||
      (function () {
        const auth = req.headers.authorization
        if (typeof auth === 'string' && auth.indexOf('Bearer ') === 0) return auth.slice(7)
        return ''
      })()
    return presented === TOKEN
  }

  /* ------------------------------------------------------------------ *
   * 3. 会话状态快照
   * ------------------------------------------------------------------ */

  function liveSessionMap() {
    const map = {}
    const list = safe(function () {
      return sessionsSvc && typeof sessionsSvc.list === 'function' ? sessionsSvc.list() : []
    }, [])
    for (let i = 0; i < list.length; i += 1) {
      const s = list[i]
      if (s && s.id !== undefined) map[String(s.id)] = s
    }
    return map
  }

  function projectJobs() {
    const out = []
    const list = safe(function () {
      return jobsSvc && typeof jobsSvc.list === 'function' ? jobsSvc.list() : []
    }, [])
    for (let i = 0; i < list.length; i += 1) {
      const j = list[i]
      if (!j) continue
      out.push({
        id: String(j.id),
        kind: String(j.kind || ''),
        label: String(j.label || ''),
        status: String(j.status || ''),
        owner: j.ownerSession !== undefined && j.ownerSession !== null ? String(j.ownerSession) : null,
        startedAt: typeof j.startedAt === 'number' ? j.startedAt : 0,
        finishedAt: typeof j.finishedAt === 'number' ? j.finishedAt : null,
        detail: typeof j.detail === 'string' ? j.detail : null,
      })
    }
    return out
  }

  function projectGoal(sessionId) {
    if (!goalsSvc || typeof goalsSvc.get !== 'function' || !agentsSvc || typeof agentsSvc.get !== 'function') return null
    return safe(function () {
      const agent = agentsSvc.get(sessionId)
      if (!agent) return null
      const g = goalsSvc.get(agent)
      if (!g) return null
      return {
        // id/revision 是宿主 clear/edit/pause 要的 GoalRef —— 少了它手机就"看得见清不掉"
        id: g.id !== undefined && g.id !== null ? String(g.id) : '',
        revision: typeof g.revision === 'number' ? g.revision : 0,
        createdAt: typeof g.createdAt === 'number' ? g.createdAt : 0,
        objective: String(g.objective || ''),
        phase: String(g.phase || ''),
        roundsStarted: typeof g.roundsStarted === 'number' ? g.roundsStarted : 0,
        maxGoalRounds: typeof g.maxGoalRounds === 'number' ? g.maxGoalRounds : null,
        blockedReason: typeof g.blockedReason === 'string' ? g.blockedReason : null,
        activation: g.activation !== undefined ? String(g.activation) : null,
        updatedAt: typeof g.updatedAt === 'number' ? g.updatedAt : 0,
      }
    }, null)
  }

  function currentModel() {
    return safe(function () {
      if (!modelSvc || typeof modelSvc.currentSelection !== 'function') return null
      const sel = modelSvc.currentSelection()
      if (!sel) return null
      return {
        provider: String(sel.provider || ''),
        model: String(sel.model || ''),
        reasoningEffort: sel.reasoningEffort ? String(sel.reasoningEffort) : null,
      }
    }, null)
  }

  /**
   * 取会话标题。
   *
   * 优先用 list() 自带的投影值 —— 标题在 dsh-session-title 里注册为 `title` 投影，
   * 是持久化的，对冷会话同样有效。投影缺失时才退回活会话的 sessionTitle 折叠。
   * 这一点很重要：本机所有会话常常共享同一个 cwd，拿不到标题时列表会全都长一样。
   */
  function titleOf(summary, session) {
    const proj = summary && summary.projections && summary.projections.values
    if (proj && typeof proj.title === 'string' && proj.title) return proj.title
    const t = safe(function () {
      if (!titleSvc || typeof titleSvc.get !== 'function' || !session) return null
      return titleSvc.get(session)
    }, null)
    if (t && typeof t.title === 'string' && t.title) return t.title
    return null
  }

  function buildState(limit) {
    const maxSessions = typeof limit === 'number' && limit > 0 ? limit : 60
    const state = {
      ok: true,
      name: NAME,
      version: VERSION,
      ts: Date.now(),
      model: currentModel(),
      sessions: [],
      jobs: projectJobs(),
      activeSessionId: activeSessionId,
      error: null,
    }
    if (sc === undefined || typeof sc.list !== 'function') {
      state.ok = false
      state.error = 'sessionController unavailable'
      return Promise.resolve(state)
    }
    const live = liveSessionMap()
    const wsByPath = workspaceByPath()
    return sc
      .list({}, makeSignal())
      .then(function (value) {
        const items = (value && value.items) || []
        for (let i = 0; i < items.length && state.sessions.length < maxSessions; i += 1) {
          const it = items[i]
          if (!it) continue
          const id = String(it.sessionId)
          const liveSession = live[id]
          const owned = []
          for (let k = 0; k < state.jobs.length; k += 1) {
            if (state.jobs[k].owner === id) {
              owned.push({
                id: state.jobs[k].id,
                label: state.jobs[k].label,
                status: state.jobs[k].status,
                kind: state.jobs[k].kind,
              })
            }
          }
          const summaryModel = projectionModel(it)
          if (summaryModel) sessionModelCache[id] = summaryModel
          const summaryPressure = projectionPressure(it)
          state.sessions.push({
            id: id,
            title: titleOf(it, liveSession),
            running: it.running === true,
            blank: it.blank === true,
            updatedAt: typeof it.updatedAt === 'number' ? it.updatedAt : 0,
            cwd: typeof it.cwd === 'string' ? it.cwd : null,
            origin: typeof it.origin === 'string' ? it.origin : null,
            parent: it.parentSessionId !== undefined && it.parentSessionId !== null ? String(it.parentSessionId) : null,
            live: !!liveSession,
            activity: activityOf(id),
            // 进度（轮/步/上下文）—— 和 workspaces 那份是**同一份镜像**，
            // 只是两处都要带上：手机既看 state 也看工作区树
            progress: progressView(id, summaryModel, summaryPressure),
            model: projectionModel(it),
            workspaceId: it.cwd && wsByPath[String(it.cwd)] ? wsByPath[String(it.cwd)] : null,
            jobs: owned,
            // goal 只在活会话上求值：冷会话没有 Agent，且逐个求值会拖慢状态轮询
            goal: liveSession ? projectGoal(id) : null,
          })
        }
        state.totalSessions = items.length
        return state
      })
      .catch(function (err) {
        state.ok = false
        state.error = String((err && err.message) || err)
        return state
      })
  }

  /* ------------------------------------------------------------------ *
   * 4. 会话历史 / 消息归一化
   * ------------------------------------------------------------------ */

  function textFromBlocks(blocks) {
    const parts = []
    if (!blocks || typeof blocks.length !== 'number') return ''
    for (let i = 0; i < blocks.length; i += 1) {
      const b = blocks[i]
      if (!b || typeof b !== 'object') continue
      if (b.type === 'text' && typeof b.text === 'string') parts.push(b.text)
      else if (b.type === 'reasoning' && typeof b.text === 'string') parts.push(b.text)
      else if (b.type === 'image') parts.push('[图片]')
      else if (b.type === 'file') parts.push('[文件]')
      // tool-call 块由 tool/call 事件单独呈现，这里跳过以免正文里出现重复的一行
      else if (b.type === 'tool-result') parts.push(textFromBlocks(b.content))
    }
    return parts.join('\n')
  }

  /**
   * 把助手消息的内容块拆成「正文」与「思考」两部分。
   *
   * 以前是把 reasoning 用 "[思考] " 前缀塞进正文里，混在一起既不好读，
   * 也和电脑上的呈现不一致 —— 电脑上是折叠的独立区块。
   */
  function splitAssistantBlocks(blocks) {
    const text = []
    const reasoning = []
    if (!blocks || typeof blocks.length !== 'number') return { text: '', reasoning: '' }
    for (let i = 0; i < blocks.length; i += 1) {
      const b = blocks[i]
      if (!b || typeof b !== 'object') continue
      if (b.type === 'text' && typeof b.text === 'string') text.push(b.text)
      else if (b.type === 'reasoning' && typeof b.text === 'string') reasoning.push(b.text)
      else if (b.type === 'image') text.push('[图片]')
      else if (b.type === 'file') text.push('[文件]')
      else if (b.type === 'tool-result') text.push(textFromBlocks(b.content))
    }
    return { text: text.join('\n'), reasoning: reasoning.join('\n') }
  }

  /**
   * 给工具调用挑一行能看的摘要（和电脑上卡片的标题一个意思）。
   * 工具参数各异，这里只挑最常见的几个字段，挑不到就留空由 App 退回首行参数。
   */
  function toolSummary(name, argsJson) {
    const a = safe(function () {
      return JSON.parse(String(argsJson || '{}'))
    }, null)
    if (!a || typeof a !== 'object') return { text: '', isPath: false, path: '' }
    const keys = ['command', 'file_path', 'path', 'pattern', 'query', 'url', 'prompt', 'description', 'name']
    for (let i = 0; i < keys.length; i += 1) {
      const v = a[keys[i]]
      if (typeof v === 'string' && v.trim()) {
        const text = v.trim()
        // file_path / path 直接就是文件；命令之类的不是，别乱标成可点
        const isPath = keys[i] === 'file_path' || keys[i] === 'path'
        return { text: text, isPath: isPath, path: isPath ? text : '' }
      }
    }
    return { text: '', isPath: false, path: '' }
  }

  function clip(text, max) {
    const s = typeof text === 'string' ? text : ''
    const limit = typeof max === 'number' ? max : 4000
    if (s.length <= limit) return s
    return s.slice(0, limit) + '\n…（已截断 ' + (s.length - limit) + ' 字）'
  }

  function sourceKind(data) {
    return safe(function () {
      const src = data && data.source
      if (!src) return 'user'
      if (typeof src === 'string') return src
      if (typeof src.kind === 'string') return src.kind
      return 'user'
    }, 'user')
  }

  /** 把 DSH 的 SessionHistoryRecord[] 归一化成手机端好渲染的消息数组。 */
  function deriveMessages(records) {
    const out = []
    if (!records || typeof records.length !== 'number') return out
    for (let i = 0; i < records.length; i += 1) {
      const rec = records[i]
      const ev = rec && rec.type === 'event' ? rec.event : rec
      if (!ev || typeof ev.type !== 'string') continue
      const data = ev.data || {}
      const seq = typeof ev.seq === 'number' ? ev.seq : 0
      const time = typeof ev.time === 'number' ? ev.time : 0

      if (ev.type === 'user/message') {
        const msg = data.message || data
        const text = textFromBlocks(msg && msg.content)
        if (!text) continue
        out.push({ seq: seq, time: time, role: 'user', text: clip(text), source: sourceKind(data) })
      } else if (ev.type === 'assistant/message') {
        const msg = data.message || data
        const parts = splitAssistantBlocks(msg && msg.content)
        if (!parts.text && !parts.reasoning) continue
        const usage = data.usage
        out.push({
          seq: seq,
          time: time,
          role: 'assistant',
          // turn/step 一定要带上：手机端"累计跑了多少轮"就是取历史里最大的 turn ——
          // 这比桥接自己攒的计数可靠（桥接重载、App 重开都不影响）
          turn: typeof data.turn === 'number' ? data.turn : 0,
          step: typeof data.step === 'number' ? data.step : 0,
          text: clip(parts.text, 12000),
          reasoning: parts.reasoning ? clip(parts.reasoning, 8000) : null,
          interrupted: data.interrupted === true,
          usage:
            usage && typeof usage === 'object'
              ? {
                  input: typeof usage.inputTokens === 'number' ? usage.inputTokens : null,
                  output: typeof usage.outputTokens === 'number' ? usage.outputTokens : null,
                  // 缓存命中/写入也要带上：宿主的"上下文压力"= input + cacheRead + cacheWrite，
                  // 只算 input 会与电脑端差一个数量级
                  cacheRead: typeof usage.cacheReadTokens === 'number' ? usage.cacheReadTokens : null,
                  cacheWrite: typeof usage.cacheWriteTokens === 'number' ? usage.cacheWriteTokens : null,
                }
              : null,
        })
      } else if (ev.type === 'tool/call') {
        const sum = toolSummary(data.name, data.arguments)
        out.push({
          seq: seq,
          time: time,
          role: 'tool',
          name: String(data.name || ''),
          title: sum.text,
          titlePath: sum.isPath ? sum.path : null,
          text: clip(String(data.arguments || ''), 4000),
          callId: data.callId !== undefined ? String(data.callId) : null,
        })
      } else if (ev.type === 'tool/result') {
        const msg = data.message || data
        const text = textFromBlocks(msg && msg.content)
        out.push({
          seq: seq,
          time: time,
          role: 'tool-result',
          isError: !!(data.error || (msg && msg.isError)),
          errorName: data.error ? String(data.error.name || '') : null,
          text: clip(text, 1500),
        })
      }
    }
    return out
  }

  /** 拉一页历史：用 follow 的开场快照，拿完即关，避免依赖 throughSeq。 */
  /**
   * 读会话历史。
   *
   * 两种模式：
   *   - 不给 beforeSeq：拿**最新**那一页（follow 的快照就是"截止到当前游标的最近 N 条"）
   *   - 给了 beforeSeq：拿**更早**那一页（sc.page 的向后翻页，语义见下）
   *
   * 为什么必须能往后翻：一轮里工具调用能有几十上百条消息，
   * 固定拿 60 条的话，用户往上拉两下就到头了 —— 连"我上一次问了什么"都看不见。
   *
   * page 的 beforeSeq 是**开区间上界**（paginate 里 `end = min(throughSeq+1, beforeSeq)`），
   * 所以要"比 seq=X 更早的消息"，得传 throughSeq = X-1。
   * 这段语义是读宿主源码确认的，不是猜的。
   */
  function loadHistory(sessionId, maxMessages, beforeSeq) {
    const max = typeof maxMessages === 'number' && maxMessages > 0 ? maxMessages : 40
    const address = { kind: 'session', sessionId: sessionId }

    if (typeof beforeSeq === 'number' && beforeSeq > 0) {
      if (sc === undefined || typeof sc.page !== 'function') {
        return Promise.reject(new Error('sessionController.page unavailable'))
      }
      const signal = makeSignal()
      return Promise.resolve()
        .then(function () {
          return sc.page({ address: address, throughSeq: beforeSeq - 1, maxMessages: max }, signal)
        })
        .then(function (value) {
          const records = (value && value.records) || []
          const messages = deriveMessages(records)
          safe(function () {
            scanContext(sessionId, records)
          }, undefined)
          return {
            messages: messages,
            hasMore: !!(value && value.hasMore),
            cursor: 0,
            header: null,
          }
        })
    }

    if (sc === undefined || typeof sc.follow !== 'function') {
      return Promise.reject(new Error('sessionController.follow unavailable'))
    }
    const signal = makeSignal()
    const request = {
      address: address,
      maxMessages: max,
    }
    // follow 是 async generator：调用即返回迭代器，不是 Promise
    return Promise.resolve().then(function () {
      const iter = sc.follow(request, signal)
      const iterator = iter[Symbol.asyncIterator] ? iter[Symbol.asyncIterator]() : iter
      return iterator.next().then(function (first) {
        try {
          if (typeof iterator.return === 'function') iterator.return()
        } catch (err) {
          /* 生成器可能已结束 */
        }
        signal.abort('history snapshot taken')
        const frame = first && first.value
        if (!frame) return { messages: [], hasMore: false, cursor: 0, header: null }
        safe(function () {
          scanContext(sessionId, frame.records)
        }, undefined)
        return {
          messages: deriveMessages(frame.records),
          // 快照里自带 hasMore = "还有更早的记录"，直接转给手机
          hasMore: frame.hasMore === true,
          cursor: typeof frame.cursor === 'number' ? frame.cursor : 0,
          header: frame.header
            ? {
                id: String(frame.header.id),
                cwd: frame.header.cwd || null,
                createdAt: frame.header.createdAt || 0,
                agentPreset: frame.header.agentPreset || null,
              }
            : null,
        }
      })
    })
  }

  /* ------------------------------------------------------------------ *
   * 5. 未读回复跟踪
   *
   * harness 自己没有"已读/未读"概念（全是客户端状态），所以这里只负责
   * 提供**判断依据**：每个会话最后一次「助手回复」和最后一次「人提问」的
   * seq 与时间。已读watermark 存在手机侧（那是每个观察者自己的状态）。
   *
   * 数据来自两处：
   *   - 实时：session/event 火线（实测能到根监听器）
   *   - 回填：挂载时对最近活跃的会话读一次日志尾部，
   *           这样"挂之前就已经存在的未读回复"也能显示出来
   * ------------------------------------------------------------------ */

  const activity = {}
  let backfillDone = false
  let backfillCount = 0

  function actOf(sid) {
    if (!activity[sid]) {
      activity[sid] = { lastSeq: 0, lastReplySeq: 0, lastReplyAt: 0, lastUserSeq: 0, lastUserAt: 0 }
    }
    return activity[sid]
  }

  /* ------------------------------------------------------------------ *
   * 5bis. 会话进度：跑到第几轮第几步、上下文用了多少
   *
   * 数据全在会话事件里，不用额外接口：
   *   turn/start       → 轮号（轮号是 1 起递增的，所以最大值 = 累计轮数）
   *   step/start       → 步号
   *   assistant/message→ 这一轮这一轮的 usage.inputTokens（上一次请求实际塞了多少上下文）
   *   request/context  → contextWindow（模型这次可用的上下文窗口）
   *
   * 手机端只在自己开着的会话上收实时事件，冷会话看不到 ——
   * 所以桥接**镜像一份**放进 /phone/state，任何会话都能显示。
   * 这份镜像从桥接挂载时开始攒；历史里的轮号另有来源（消息自带 turn）。
   * ------------------------------------------------------------------ */

  const progress = {}
  const eventTypeCounts = {}

  /**
   * 上下文窗口按**模型**记：`provider/model` → 窗口大小。
   *
   * 为什么不只记在会话上：窗口是模型的属性，不是会话的。
   * 而且"某一轮"里 request/context 只在上下文**变化**时才写一条事件，
   * 冷会话打开时可能根本碰不到 —— 按模型缓存住，
   * 换个会话、甚至桥接重启后读一次历史（下面 scanContext）就能补上，
   * 不会出现"窗口未知"。harness 自己的界面也是这么干的（从日志里翻最后一条）。
   */
  const modelWindows = {}

  function modelKey(provider, model) {
    return String(provider || '') + '/' + String(model || '')
  }

  /** 从一条 request/context 事件的数据里记下窗口（同时记到会话与模型两张表）。 */
  function recordContext(sid, data) {
    if (!sid || !data || typeof data !== 'object') return
    const w = typeof data.contextWindow === 'number' && data.contextWindow > 0 ? data.contextWindow : 0
    const p = progOf(sid)
    if (typeof data.provider === 'string' && data.provider) p.provider = data.provider
    if (typeof data.model === 'string' && data.model) p.model = data.model
    if (w > 0) {
      p.contextWindow = w
      if (p.provider || p.model) modelWindows[modelKey(p.provider, p.model)] = w
    }
  }

  /** 会话当前用的模型（跟 state 里给手机看的那个是同一份）。 */
  function sessionModel(sessionId) {
    if (sc === undefined || typeof sc.list !== 'function') return null
    const cached = sessionModelCache[sessionId]
    if (cached !== undefined) return cached
    return null
  }
  const sessionModelCache = {}

  function rememberSessionModels(summaries) {
    const ids = Object.keys(summaries)
    for (let i = 0; i < ids.length; i += 1) {
      const m = projectionModel(summaries[ids[i]])
      if (m) sessionModelCache[ids[i]] = m
    }
  }

  function progOf(sid) {
    if (!progress[sid]) {
      progress[sid] = {
        turn: 0,
        step: 0,
        turns: 0,
        contextWindow: 0,
        contextUsed: 0,
        provider: '',
        model: '',
      }
    }
    return progress[sid]
  }

  function progressView(sid, modelHint, pressure) {
    const p = progress[sid]
    if (!p) return null
    let win = p.contextWindow
    let used = p.contextUsed
    // ★ 投影优先：电脑端显示的就是它。事件推算只在投影缺失时兜底
    if (pressure) {
      if (pressure.contextWindow > 0) {
        win = pressure.contextWindow
        if (p.provider || p.model) modelWindows[modelKey(p.provider, p.model)] = win
        if (modelHint) modelWindows[modelKey(modelHint.provider, modelHint.model)] = win
      }
      const u = pressure.projectedTokens >= 0 ? pressure.projectedTokens : pressure.pressureTokens
      if (u >= 0) used = u
    }
    // 会话上没记到窗口时，用"这个会话当前用的模型"去模型表里查 ——
    // 同型号在别的会话见过一次就够了，不该让用户看到"窗口未知"
    if (!win) {
      const hint = modelHint || sessionModel(sid)
      if (hint) win = modelWindows[modelKey(hint.provider, hint.model)] || 0
    }
    return {
      turn: p.turn,
      step: p.step,
      turns: p.turns,
      contextWindow: win,
      contextUsed: used,
      provider: p.provider || (modelHint ? String(modelHint.provider || '') : ''),
      model: p.model || (modelHint ? String(modelHint.model || '') : ''),
    }
  }

  /**
   * 从一段记录里扫最后一条 request/context，补上窗口。
   *
   * 这就是 harness 自己界面的做法（它在日志里 findLast 一条 request/context）：
   * 窗口不靠"恰好收到那条实时事件"，而是**从日志里翻出来**。
   * 翻页返回的 records 里就带着它，白捡。
   */
  function scanContext(sid, records) {
    if (!records || typeof records.length !== 'number') return
    for (let i = records.length - 1; i >= 0; i -= 1) {
      const rec = records[i]
      const ev = rec && rec.type === 'event' ? rec.event : rec
      if (ev && ev.type === 'request/context') {
        safe(function () {
          recordContext(sid, ev.data)
        }, undefined)
        return
      }
    }
  }

  function trackEvent(sid, event) {
    if (!sid || !event || typeof event.type !== 'string') return
    eventTypeCounts[event.type] = (eventTypeCounts[event.type] || 0) + 1
    const a = actOf(sid)
    const seq = typeof event.seq === 'number' ? event.seq : 0
    if (seq > a.lastSeq) a.lastSeq = seq
    const t = typeof event.time === 'number' ? event.time : Date.now()
    if (event.type === 'assistant/message') {
      const p = progOf(sid)
      const d = event.data || {}
      if (typeof d.turn === 'number' && d.turn > 0) {
        p.turn = d.turn
        if (d.turn > p.turns) p.turns = d.turn
      }
      if (typeof d.step === 'number' && d.step > 0) p.step = d.step
      // 事件里的兜底口径也要和宿主一致：上下文压力 = 输入 + 缓存命中 + 缓存写入。
      // 只取 inputTokens 会小两三个数量级（缓存命中往往是主体）—— 这正是
      // "手机上显示的上下文和电脑上不一样"的根因。权威值仍是 contextPressure 投影。
      if (d.usage && typeof d.usage === 'object') {
        const u = d.usage
        if (typeof u.inputTokens === 'number') {
          p.contextUsed =
            u.inputTokens +
            (typeof u.cacheReadTokens === 'number' ? u.cacheReadTokens : 0) +
            (typeof u.cacheWriteTokens === 'number' ? u.cacheWriteTokens : 0)
        }
      }
      if (seq >= a.lastReplySeq) {
        a.lastReplySeq = seq
        a.lastReplyAt = t
      }
    } else if (event.type === 'turn/start') {
      const p = progOf(sid)
      const t = typeof event.data?.turn === 'number' ? event.data.turn : 0
      if (t > 0) {
        p.turn = t
        if (t > p.turns) p.turns = t
        p.step = 0
      }
    } else if (event.type === 'step/start') {
      const p = progOf(sid)
      const st = typeof event.data?.step === 'number' ? event.data.step : 0
      const t = typeof event.data?.turn === 'number' ? event.data.turn : 0
      if (st > 0) p.step = st
      if (t > 0) {
        p.turn = t
        if (t > p.turns) p.turns = t
      }
    } else if (event.type === 'request/context') {
      const d = event.data || {}
      lastContextEventDebug = { at: Date.now(), keys: Object.keys(d).join(','), contextWindow: d.contextWindow }
      recordContext(sid, d)
    } else if (event.type === 'user/message') {
      const kind = safe(function () {
        const d = event.data || {}
        const src = d.source
        return src && src.kind ? String(src.kind) : 'user'
      }, 'user')
      // 只认真正的人发的；harness 注入的运行时提醒/技能目录不算"人提问"
      if (kind === 'user' && seq >= a.lastUserSeq) {
        a.lastUserSeq = seq
        a.lastUserAt = t
      }
    }
  }

  function trackRecords(sid, records) {
    if (!sid || !records || typeof records.length !== 'number') return
    for (let i = 0; i < records.length; i += 1) {
      const rec = records[i]
      const ev = rec && rec.type === 'event' ? rec.event : rec
      if (ev) trackEvent(sid, ev)
    }
  }

  /** 读一个会话的日志尾部（不拉全量），补上挂载前的状态。 */
  function readTail(sid) {
    if (sc === undefined || typeof sc.follow !== 'function') return Promise.resolve()
    const signal = makeSignal()
    return Promise.resolve()
      .then(function () {
        const iter = sc.follow({ address: { kind: 'session', sessionId: sid }, maxMessages: 8 }, signal)
        const it = iter[Symbol.asyncIterator] ? iter[Symbol.asyncIterator]() : iter
        return it.next().then(function (r) {
          try {
            if (typeof it.return === 'function') it.return()
          } catch (e) {
            /* 已结束 */
          }
          signal.abort('tail read done')
          const frame = r && r.value
          if (frame && frame.records) trackRecords(sid, frame.records)
        })
      })
      .catch(function () {
        /* 单个会话读失败不影响其它 */
      })
  }

  /**
   * 回填：只挑最近 7 天活跃过、且还没被实时跟踪到的会话，
   * 串行读尾巴，最多 60 个 —— 一次性的，不放在状态轮询路径上。
   */
  function backfillActivity() {
    if (backfillDone) return
    backfillDone = true
    if (sc === undefined || typeof sc.list !== 'function') return
    sc.list({}, makeSignal())
      .then(function (value) {
        const items = (value && value.items) || []
        const cutoff = Date.now() - 7 * 24 * 3600 * 1000
        const targets = []
        for (let i = 0; i < items.length && targets.length < 60; i += 1) {
          const it = items[i]
          if (!it) continue
          const sid = String(it.sessionId)
          if (activity[sid]) continue
          const up = typeof it.updatedAt === 'number' ? it.updatedAt : 0
          if (up < cutoff) continue
          targets.push(sid)
        }
        backfillCount = targets.length
        let idx = 0
        function step() {
          if (idx >= targets.length) return
          const sid = targets[idx]
          idx += 1
          readTail(sid).then(step, step)
        }
        step()
      })
      .catch(function () {})
  }

  /** 给状态/工作区接口用的三个字段。 */
  function activityOf(sid) {
    const a = activity[sid]
    if (!a) return { lastReplySeq: 0, lastReplyAt: 0, agentSpokeLast: false }
    return {
      lastReplySeq: a.lastReplySeq,
      lastReplyAt: a.lastReplyAt,
      // "助手最后说话"= 大概率有你还没看的回复
      agentSpokeLast: a.lastReplySeq > 0 && a.lastReplySeq > a.lastUserSeq,
    }
  }

  /* ------------------------------------------------------------------ *
   * 6. 用户提问（ask_user_question）
   *
   * harness 用瀑布事件 `user-questions/request` 找回答者：
   *   谁返回 { answers:[{id, selected, custom?}] } 谁就赢了；
   *   不接就调 next() 让下一个回答者（浏览器 UI）来。
   *
   * 这里注册成**条件性**回答者，而不是无条件拦截：
   * 只有当手机正开着那个会话的实时流时才接，否则立刻 next()。
   * 这样你人在电脑前时，电脑上的选项框不会被手机拖慢。
   * ------------------------------------------------------------------ */

  const pendingQuestions = {}
  const sessionStreamClients = {}
  let questionSeq = 0
  let questionEventCount = 0
  let lastQuestionDebug = null
  let sessionEventCount = 0
  let lastSessionEventDebug = null
  let lastContextEventDebug = null

  /**
   * 「手机正在看这个会话」= 最近一次心跳足够新。
   *
   * 为什么不用 /live 连接是否开着来判断：手机静默掉线（App 被杀、蜂窝断、
   * 没有正常发 FIN）时 TCP 连接可能挂很久才被回收，那期间桥接会以为手机还在看，
   * 把提问推给一条死连接 —— 电脑要干等满超时。心跳新鲜度没有这个问题：
   * 心跳一停，最多 TTL 之后就不再算"在看"。
   *
   * 心跳搭在 App 本来就有的那次 /phone/state 轮询上，不额外加请求。
   */
  const WATCH_TTL_MS = 15000
  const watchPings = {}

  /** 往某个会话的所有实时流推送一个事件。 */
  function sessionStreamSend(sessionId, event, payload) {
    const list = sessionStreamClients[sessionId]
    if (!list || list.length === 0) return
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const res = list[i]
      if (!res || res.writableEnded) {
        list.splice(i, 1)
        continue
      }
      sseSend(res, event, payload)
    }
  }

  function touchWatch(sessionId) {
    if (typeof sessionId === 'string' && sessionId) watchPings[sessionId] = Date.now()
  }
  function isWatched(sessionId) {
    if (!sessionId) return false
    const at = watchPings[sessionId]
    return typeof at === 'number' && Date.now() - at < WATCH_TTL_MS
  }
  function watchedKeys() {
    const out = []
    const now = Date.now()
    const ids = Object.keys(watchPings)
    for (let i = 0; i < ids.length; i += 1) {
      if (now - watchPings[ids[i]] < WATCH_TTL_MS) out.push(ids[i])
    }
    return out
  }

  /** 从瀑布事件里的 agent 取出会话 id（宿主侧是活 Agent，带 id）。 */
  function agentSessionId(agent) {
    return safe(function () {
      if (!agent) return null
      if (agent.id !== undefined) return String(agent.id)
      if (agent.sessionId !== undefined) return String(agent.sessionId)
      return null
    }, null)
  }

  function normalizeQuestions(list) {
    const out = []
    const arr = Array.isArray(list) ? list : []
    for (let i = 0; i < arr.length; i += 1) {
      const q = arr[i]
      if (!q || typeof q !== 'object') continue
      const options = []
      const opts = Array.isArray(q.options) ? q.options : []
      for (let k = 0; k < opts.length; k += 1) {
        const o = opts[k]
        if (!o || typeof o.label !== 'string') continue
        options.push({
          label: o.label,
          description: typeof o.description === 'string' ? o.description : null,
        })
      }
      out.push({
        id: String(q.id === undefined ? 'q' + i : q.id),
        question: String(q.question || ''),
        detail: typeof q.detail === 'string' ? q.detail : null,
        header: typeof q.header === 'string' ? q.header : null,
        options: options,
        multiSelect: q.multiSelect === true,
      })
    }
    return out
  }

  function normalizeAnswers(list) {
    const out = []
    const arr = Array.isArray(list) ? list : []
    for (let i = 0; i < arr.length; i += 1) {
      const a = arr[i]
      if (!a || typeof a !== 'object') continue
      const selected = []
      const sel = Array.isArray(a.selected) ? a.selected : []
      for (let k = 0; k < sel.length; k += 1) {
        if (typeof sel[k] === 'string') selected.push(sel[k])
      }
      const item = { id: String(a.id === undefined ? '' : a.id), selected: selected }
      if (typeof a.custom === 'string' && a.custom !== '') item.custom = a.custom
      out.push(item)
    }
    return out
  }

  /** 把问题推给正在看这个会话的手机。 */
  function pushQuestion(entry) {
    sessionStreamSend(entry.sessionId, 'question', {
      id: entry.id,
      sessionId: entry.sessionId,
      questions: entry.questions,
    })
  }

  function pushQuestionDone(entry, reason) {
    sessionStreamSend(entry.sessionId, 'question-done', { id: entry.id, reason: reason })
  }

  function settleQuestion(entry, answer, reason) {
    if (!entry || entry.settled) return
    entry.settled = true
    pushQuestionDone(entry, reason)
    if (typeof entry.resolve === 'function') entry.resolve(answer)
  }

  /** 每秒清理：超时、或手机不再看这个会话了，就把问题交还给下一个回答者。 */
  function expireQuestions() {
    const ids = Object.keys(pendingQuestions)
    const now = Date.now()
    for (let i = 0; i < ids.length; i += 1) {
      const e = pendingQuestions[ids[i]]
      if (!e || e.settled) continue
      const age = now - e.createdAt
      // 手机不看了（心跳停）→ 3 秒宽限后立刻交还电脑；兜底 120 秒
      if (age > 120000 || (!isWatched(e.sessionId) && age > 3000)) {
        settleQuestion(e, null, 'expired')
      }
    }
  }

  /**
   * 先问手机，没人答就调 fallback 走原链路。
   *
   * 为什么不是注册 `user-questions/request` 监听器：
   * 那个瀑布是按 **agent 作用域**派发的
   * （`ctx.waterfall(scopeTarget(agent, agent), ...)`），
   * 注册在根上下文上的监听器收不到 —— 实测 questionEventCount 一直是 0。
   * 改成把 `userQuestions.ask` 包一层，语义等价且天然可回退。
   */
  function askPhoneFirst(request, fallback) {
    const sid = agentSessionId(request && request.agent)
    const questions = normalizeQuestions(request && request.questions)
    questionEventCount += 1
    lastQuestionDebug = {
      at: Date.now(),
      hasAgent: !!(request && request.agent),
      agentIdType: typeof safe(function () {
        return request.agent.id
      }, undefined),
      sid: sid,
      watched: sid === null ? null : isWatched(sid),
      watchedKeys: watchedKeys(),
      questionCount: questions.length,
      intercepted: !!(sid && questions.length > 0 && isWatched(sid)),
    }
    if (!sid || questions.length === 0 || !isWatched(sid)) return fallback()

    questionSeq += 1
    const id = 'q' + questionSeq
    const entry = {
      id: id,
      sessionId: sid,
      questions: questions,
      createdAt: Date.now(),
      settled: false,
      resolve: null,
    }
    pendingQuestions[id] = entry
    pushQuestion(entry)

    // 调用方的 signal 中止时也交还，避免把瀑布卡死
    safe(function () {
      const sig = request && request.signal
      if (sig && typeof sig.addEventListener === 'function') {
        sig.addEventListener('abort', function () {
          settleQuestion(entry, null, 'aborted')
        })
      }
    })

    return new Promise(function (resolve) {
      entry.resolve = function (answer) {
        delete pendingQuestions[id]
        resolve(answer)
      }
    }).then(function (answer) {
      // null = 没人从手机回答（超时/中止）→ 交回原链路（浏览器 UI）
      return answer === null ? fallback() : answer
    })
  }

  /** 把 userQuestions.ask 包一层；返回可还原的函数。 */
  function installQuestionHook() {
    const uq = ctx.get('userQuestions')
    if (uq === undefined || typeof uq.ask !== 'function') return null
    const original = uq.ask
    const wrapper = function (request) {
      return askPhoneFirst(request, function () {
        return original.call(uq, request)
      })
    }
    try {
      uq.ask = wrapper
    } catch (err) {
      return null
    }
    return function restore() {
      try {
        if (uq.ask === wrapper) uq.ask = original
      } catch (err) {
        /* 已经还原 */
      }
    }
  }

  /* ------------------------------------------------------------------ *
   * 6. SSE：状态推送 + 单会话实时流
   * ------------------------------------------------------------------ */

  const stateClients = {}
  let stateClientSeq = 0
  let activeSessionId = null

  function openSse(req, res) {
    res.writeHead(
      200,
      corsHeaders({
        'content-type': 'text/event-stream; charset=utf-8',
        connection: 'keep-alive',
        'x-accel-buffering': 'no',
      }),
    )
    if (typeof res.flushHeaders === 'function') res.flushHeaders()
    res.write(': dsh-phone-bridge stream open\n\n')
  }

  function sseSend(res, event, payload) {
    try {
      res.write('event: ' + event + '\ndata: ' + JSON.stringify(payload) + '\n\n')
      return true
    } catch (err) {
      return false
    }
  }

  let stateDirty = false
  function broadcastState(force) {
    const ids = Object.keys(stateClients)
    if (ids.length === 0) return
    buildState(60).then(function (state) {
      for (let i = 0; i < ids.length; i += 1) {
        const entry = stateClients[ids[i]]
        if (!entry || entry.res.writableEnded) {
          delete stateClients[ids[i]]
          continue
        }
        sseSend(entry.res, force === true ? 'hello' : 'state', state)
      }
    })
  }

  /** 合并同一轮事件风暴，避免一次工具循环里刷爆手机。 */
  function markStateDirty() {
    stateDirty = true
  }
  every(1000, function () {
    safe(expireQuestions, undefined)
    safe(ensureControlStream, undefined)
    if (!stateDirty) return
    stateDirty = false
    broadcastState(false)
  })

  function handleStateStream(req, res) {
    const id = 's' + (stateClientSeq += 1)
    openSse(req, res)
    stateClients[id] = { res: res }
    buildState(60).then(function (state) {
      if (!res.writableEnded) sseSend(res, 'hello', state)
    })
    function drop() {
      delete stateClients[id]
      try {
        res.end()
      } catch (err) {
        /* 已经关闭 */
      }
    }
    req.on('close', drop)
    req.on('error', drop)
  }

  /** 单会话实时流：转播 follow() 的帧。 */
  function handleSessionStream(req, res, sessionId, maxMessages) {
    if (sc === undefined || typeof sc.follow !== 'function') {
      sendJson(res, 503, { ok: false, error: 'follow unavailable' })
      return
    }
    openSse(req, res)
    // 先补一份当前排队情况：手机上多半是"跑着的时候点进来"的，
    // 光等增量帧会看见一段空白
    sseSend(res, 'queue', { items: queueViews(sessionId) })
    const signal = makeSignal()
    const request = {
      address: { kind: 'session', sessionId: sessionId },
      maxMessages: typeof maxMessages === 'number' && maxMessages > 0 ? maxMessages : 40,
      assistantStream: true,
    }
    let closed = false
    // 只登记推送目标；「是否在看」由心跳决定（见上面的 WATCH_TTL_MS）
    if (!sessionStreamClients[sessionId]) sessionStreamClients[sessionId] = []
    sessionStreamClients[sessionId].push(res)

    function drop() {
      if (closed) return
      closed = true
      const list = sessionStreamClients[sessionId]
      if (list) {
        const idx = list.indexOf(res)
        if (idx >= 0) list.splice(idx, 1)
        if (list.length === 0) delete sessionStreamClients[sessionId]
      }
      signal.abort('client disconnected')
      try {
        res.end()
      } catch (err) {
        /* 已经关闭 */
      }
    }
    req.on('close', drop)
    req.on('error', drop)
    activeSessionId = sessionId

    Promise.resolve()
      .then(function () {
        const iter = sc.follow(request, signal)
        const loop = iter[Symbol.asyncIterator]
          ? iter[Symbol.asyncIterator]()
          : iter[Symbol.iterator]()
        function step() {
          if (closed || signal.aborted) return Promise.resolve()
          return loop.next().then(function (r) {
            if (r.done || closed) {
              if (!res.writableEnded) sseSend(res, 'end', { ok: true })
              return
            }
            const frame = r.value
            if (frame && frame.type === 'snapshot') {
              sseSend(res, 'snapshot', {
                cursor: frame.cursor,
                hasMore: frame.hasMore === true,
                header: frame.header
                  ? {
                      id: String(frame.header.id),
                      cwd: frame.header.cwd || null,
                      agentPreset: frame.header.agentPreset || null,
                    }
                  : null,
                messages: deriveMessages(frame.records),
              })
            } else if (frame && frame.type === 'assistant-stream') {
              const f = frame.frame || {}
              if (f.type === 'chunk') {
                sseSend(res, 'delta', { attemptId: String(f.attemptId || ''), index: f.index, chunk: f.chunk })
              } else if (f.type === 'start') {
                sseSend(res, 'attempt', { phase: 'start', attemptId: String(f.attemptId || ''), turn: f.turn, step: f.step })
              } else if (f.type === 'end') {
                sseSend(res, 'attempt', {
                  phase: 'end',
                  attemptId: String(f.attemptId || ''),
                  outcome: f.outcome ? String(f.outcome.kind) : null,
                  seq: f.outcome && typeof f.outcome.seq === 'number' ? f.outcome.seq : null,
                })
              }
            } else if (frame && frame.type === 'event') {
              const ev = frame.event || {}
              // ★ 进度也从这里记：根监听器（session/event）实测只覆盖**一部分**会话 ——
              //   从 bridge 自己 create 出来的会话，它的事件到不了根。
              //   而这条 follow 流对**任何**会话都成立（聊天页本来就是靠它渲染的），
              //   所以"手机在看哪个会话，那个会话的进度就一定记得上"。
              safe(function () {
                trackEvent(sessionId, ev)
              }, undefined)
              if (ev.type === 'user/message' || ev.type === 'assistant/message' || ev.type === 'tool/call' || ev.type === 'tool/result') {
                sseSend(res, 'messages', { messages: deriveMessages([frame]) })
                markStateDirty()
              } else if (ev.type === 'request/context' || ev.type === 'turn/start' || ev.type === 'step/start') {
                sseSend(res, 'progress', progressView(sessionId) || {})
                if (ev.type === 'turn/start' || ev.type === 'turn/end') {
                  sseSend(res, 'turn', { phase: ev.type === 'turn/start' ? 'start' : 'end', turn: ev.data?.turn })
                }
              } else if (ev.type === 'turn/start' || ev.type === 'turn/end') {
                // 转播出去：App 靠它即时切换"停止按钮"的显隐，
                // 不用等 5 秒一次的状态轮询（那会让按钮该出的时候不出）
                sseSend(res, 'turn', {
                  phase: ev.type === 'turn/start' ? 'start' : 'end',
                  turn: safe(function () {
                    return data.turn
                  }, null),
                  reason: safe(function () {
                    return data.reason ? String(data.reason) : null
                  }, null),
                })
                markStateDirty()
              }
            }
            return step()
          })
        }
        return step()
      })
      .catch(function (err) {
        if (!closed && !res.writableEnded) {
          sseSend(res, 'error', { message: String((err && err.message) || err) })
          try {
            res.end()
          } catch (e) {
            /* 已经关闭 */
          }
        }
      })
  }

  /* ------------------------------------------------------------------ *
   * 6. 写操作：发消息 / 打断 / 新建会话
   * ------------------------------------------------------------------ */

  function requestId() {
    // 只用于同一条用户消息的幂等关联，不需要密码学强度
    const rand = Math.floor(Math.random() * 1e12).toString(36)
    return 'phone-' + Date.now().toString(36) + '-' + rand
  }

  /**
   * 发一条消息。
   *
   * `receiptIds` 是手机先传上来的文件（见 handleUpload）—— 宿主的 prompt 接受
   * `{type:'file', receiptId}` 这种内容块，它会自己去 resolve + bind 那些 receipt
   * （读了 session-controller 的实现确认过，不是猜的）。
   */
  function doPrompt(sessionId, text, receiptIds) {
    if (sc === undefined || typeof sc.prompt !== 'function') {
      return Promise.reject(new Error('sessionController.prompt unavailable'))
    }
    const content = []
    const ids = Array.isArray(receiptIds) ? receiptIds : []
    for (let i = 0; i < ids.length; i += 1) {
      const id = ids[i]
      if (typeof id === 'string' && id) content.push({ type: 'file', receiptId: id })
    }
    if (typeof text === 'string' && text) content.push({ type: 'text', text: text })
    if (content.length === 0) return Promise.reject(new Error('没有内容可发'))
    const request = {
      requestId: requestId(),
      sessionId: sessionId,
      mode: 'queue',
      content: content,
    }
    return sc.prompt(request, makeSignal()).then(function (value) {
      return { accepted: !!(value && value.accepted) }
    })
  }

  function doCancel(sessionId) {
    if (sc === undefined || typeof sc.cancel !== 'function') {
      return Promise.reject(new Error('sessionController.cancel unavailable'))
    }
    // cancel 是同步返回 SessionCancelValue 的，这里统一包成 Promise
    return Promise.resolve()
      .then(function () {
        sc.cancel({ sessionId: sessionId })
        return { cancelled: true }
      })
  }

  /**
   * 新建会话。
   *
   * 关键差别（实测）：带 `workspaceId` 建出来的会话会自动挂进那个工作区，
   * 只带 `cwd` 建的不会 —— 会话能跑，但在工作区列表里看不见。
   * 所以这里先按路径反查工作区，查到就把 workspaceId 一并带上。
   */
  /* --------------------------- 会话/工作区管理 --------------------------- */

  function doSessionRename(sessionId, title) {
    if (sc === undefined || typeof sc.rename !== 'function') {
      return Promise.reject(new Error('sessionController.rename unavailable'))
    }
    const t = typeof title === 'string' ? title.trim() : ''
    if (!t) return Promise.reject(new Error('标题不能为空'))
    return sc.rename({ sessionId: sessionId, title: t }).then(function (v) {
      return { title: String((v && v.title) || t), seq: v && typeof v.seq === 'number' ? v.seq : null }
    })
  }

  /** 分支：从某个位置分叉出一个新会话。不给 atSeq 就是从末尾分。 */
  function doSessionFork(sessionId, atSeq) {
    if (sc === undefined || typeof sc.fork !== 'function') {
      return Promise.reject(new Error('sessionController.fork unavailable'))
    }
    const req = { sessionId: sessionId }
    if (typeof atSeq === 'number' && atSeq >= 0) req.atSeq = atSeq
    return sc.fork(req).then(function (v) {
      return { sessionId: String((v && v.sessionId) || '') }
    })
  }

  /**
   * 归档会话。
   *
   * 注意会话**没有删除** —— 日志是只追加的，harness 提供的是"归档"：
   * 从工作区分组里隐藏，文件与会话日志都保留。
   */
  function doSessionArchive(sessionId) {
    const wc = ctx.get('workspaceController')
    if (wc !== undefined && typeof wc.archiveSession === 'function') {
      return wc.archiveSession({ sessionId: sessionId }).then(function (v) {
        const ids = (v && v.archivedSessionIds) || []
        return { archived: true, total: ids.length }
      })
    }
    if (workspaceSvc !== undefined && typeof workspaceSvc.archiveSession === 'function') {
      return workspaceSvc.archiveSession(sessionId).then(function () {
        return { archived: true, total: null }
      })
    }
    return Promise.reject(new Error('archiveSession unavailable'))
  }

  function doWorkspaceRename(workspaceId, title) {
    const wc = ctx.get('workspaceController')
    if (wc === undefined || typeof wc.rename !== 'function') {
      return Promise.reject(new Error('workspaceController.rename unavailable'))
    }
    const t = typeof title === 'string' ? title.trim() : ''
    if (!t) return Promise.reject(new Error('标题不能为空'))
    return wc.rename({ workspaceId: workspaceId, title: t }).then(function (v) {
      const w = (v && v.workspace) || {}
      return { id: String(w.workspaceId || workspaceId), title: String(w.title || t) }
    })
  }

  /**
   * 删除工作区。
   *
   * 删的是**注册记录**，目录和里面的会话日志都保留 ——
   * 这一点必须让用户知道，所以响应里把它讲清楚。
   */
  function doWorkspaceDelete(workspaceId) {
    const wc = ctx.get('workspaceController')
    if (wc === undefined || typeof wc.delete !== 'function') {
      return Promise.reject(new Error('workspaceController.delete unavailable'))
    }
    return wc.delete({ workspaceId: workspaceId }).then(function () {
      return { deleted: true }
    })
  }

  /* ------------------------------ 文件 ------------------------------ */

  /** 找某个会话的 cwd（相对路径要靠它解析）。 */
  function sessionCwd(sessionId) {
    if (!sessionId || sc === undefined || typeof sc.list !== 'function') return Promise.resolve('')
    return sc.list({}, makeSignal()).then(
      function (value) {
        const items = (value && value.items) || []
        for (let i = 0; i < items.length; i += 1) {
          if (String(items[i].sessionId) === sessionId && typeof items[i].cwd === 'string') {
            return items[i].cwd
          }
        }
        return ''
      },
      function () {
        return ''
      },
    )
  }

  /**
   * 读一个文本文件给手机看。
   *
   * 相对路径按"该会话的 cwd"解析 —— 用户在正文里点的是
   * docs/xxx.md 这种相对写法，不能要求他写全路径。
   * 有大小上限，超了截断并标记，避免一个巨型文件把手机内存吃光。
   */
  function doReadFile(rawPath, sessionId, maxBytes) {
    const fsSvc = ctx.get('fs')
    if (fsSvc === undefined || typeof fsSvc.resolve !== 'function' || typeof fsSvc.readText !== 'function') {
      return Promise.reject(new Error('fs service unavailable'))
    }
    const cap = typeof maxBytes === 'number' && maxBytes > 0 ? Math.min(maxBytes, 2 * 1024 * 1024) : 512 * 1024
    const raw = typeof rawPath === 'string' ? rawPath.trim() : ''
    if (!raw) return Promise.reject(new Error('path required'))

    const use = function (cwd) {
      let abs = safeAbsPath(raw)
      if (abs === null) {
        if (!cwd) return Promise.reject(new Error('相对路径需要会话上下文，或请给出绝对路径'))
        const rel = raw.replace(/^\.\//, '')
        abs = safeAbsPath(cwd.replace(/\/+$/, '') + '/' + rel)
      }
      if (abs === null) return Promise.reject(new Error('路径不合法'))
      return fsSvc.resolve(abs, { signal: makeSignal() }).then(function (t) {
        return fsSvc.stat(t, makeSignal()).then(function (info) {
          if (!info) return Promise.reject(new Error('文件不存在：' + abs))
          if (info.type !== 'file') return Promise.reject(new Error('不是一个普通文件：' + abs))
          return fsSvc.readText(t, makeSignal()).then(function (text) {
            const full = String(text)
            const truncated = full.length > cap
            return {
              ok: true,
              path: abs,
              size: full.length,
              truncated: truncated,
              text: truncated ? full.slice(0, cap) : full,
            }
          })
        })
      })
    }

    if (safeAbsPath(raw) !== null) return use('')
    return sessionCwd(sessionId).then(use)
  }

  /* ------------------------------ 模型 ------------------------------ */

  /** 从 list() 的投影里取该会话当前生效的模型选择。 */
  /**
   * 宿主的 `contextPressure` 投影 —— **电脑端右下角那个上下文表用的就是它**。
   *
   * 它的两个字段与我的"从事件里数"完全不同：
   *   - `pressureTokens` = inputTokens + cacheReadTokens + cacheWriteTokens
   *     （**缓存命中/写入也算进去**！我原来只算了 inputTokens，
   *      所以和电脑显示的差一个数量级）
   *   - `projectedTokens` = pressureTokens + 采样之后的"表面"增减
   *     （回答的是"**下一次**请求会占多少"，而不是上一次请求用了多少）
   * 所以这个投影才是权威，事件推算只是兜底。
   */
  function projectionPressure(summary) {
    return safe(function () {
      const v = summary && summary.projections && summary.projections.values
      const p = v && v.contextPressure
      if (!p) return null
      return {
        contextWindow: typeof p.contextWindow === 'number' && p.contextWindow > 0 ? p.contextWindow : 0,
        pressureTokens: typeof p.pressureTokens === 'number' ? p.pressureTokens : -1,
        projectedTokens: typeof p.projectedTokens === 'number' ? p.projectedTokens : -1,
      }
    }, null)
  }

  function projectionModel(summary) {
    return safe(
      function () {
        const v = summary && summary.projections && summary.projections.values
        const ms = v && v.modelSelection
        if (!ms) return null
        const sel = ms.next || ms.lastUsed
        if (!sel) return null
        return {
          provider: String(sel.provider || ''),
          model: String(sel.model || ''),
          reasoningEffort: sel.reasoningEffort ? String(sel.reasoningEffort) : null,
        }
      },
      null,
    )
  }

  /** 主机侧可路由的模型目录（含每个模型的推理强度选项）。 */
  function listModels(sessionId) {
    if (sc === undefined || typeof sc.modelCatalog !== 'function') {
      return Promise.reject(new Error('sessionController.modelCatalog unavailable'))
    }
    return sc.modelCatalog().then(function (cat) {
      const c = cat || {}
      const model = {
        ok: true,
        default: c.default
          ? {
              provider: String(c.default.provider || ''),
              model: String(c.default.model || ''),
              reasoningEffort: c.default.reasoningEffort ? String(c.default.reasoningEffort) : null,
            }
          : null,
        routable: Array.isArray(c.routableProviders) ? c.routableProviders.map(String) : [],
        groups: (c.groups || []).map(function (g) {
          return {
            id: String(g.id || ''),
            name: String(g.name || ''),
            models: (g.models || []).map(function (m) {
              const out = { id: String(m.id || ''), name: String(m.name || '') }
              if (typeof m.description === 'string') out.description = m.description
              const r = m.reasoning
              if (r && Array.isArray(r.efforts) && r.efforts.length > 0) {
                out.efforts = r.efforts.map(function (e) {
                  return { id: String(e.id || ''), name: String(e.name || '') }
                })
                if (r.defaultEffort) out.defaultEffort = String(r.defaultEffort)
              }
              return out
            }),
          }
        }),
        failures: (c.failures || []).map(function (f) {
          return { id: String(f.id || ''), name: String(f.name || ''), message: String(f.message || '') }
        }),
        current: null,
      }
      if (!sessionId || sc === undefined || typeof sc.list !== 'function') return model
      return sc.list({}, makeSignal()).then(
        function (value) {
          const items = (value && value.items) || []
          for (let i = 0; i < items.length; i += 1) {
            if (String(items[i].sessionId) === sessionId) {
              model.current = projectionModel(items[i])
              break
            }
          }
          return model
        },
        function () {
          return model
        },
      )
    })
  }

  function doSelectModel(sessionId, provider, model, reasoningEffort) {
    if (sc === undefined || typeof sc.selectModel !== 'function') {
      return Promise.reject(new Error('sessionController.selectModel unavailable'))
    }
    if (!sessionId || !provider || !model) {
      return Promise.reject(new Error('需要 sessionId / provider / model'))
    }
    const request = { sessionId: sessionId, provider: provider, model: model }
    if (typeof reasoningEffort === 'string' && reasoningEffort) request.reasoningEffort = reasoningEffort
    return sc.selectModel(request).then(function (value) {
      const sel = (value && value.selected) || {}
      return {
        provider: String(sel.provider || ''),
        model: String(sel.model || ''),
        reasoningEffort: sel.reasoningEffort ? String(sel.reasoningEffort) : null,
      }
    })
  }

  function doCreate(cwd, workspaceId, agentPreset) {
    if (sc === undefined || typeof sc.create !== 'function') {
      return Promise.reject(new Error('sessionController.create unavailable'))
    }
    const request = {}
    if (typeof cwd === 'string' && cwd) request.cwd = cwd
    if (typeof workspaceId === 'string' && workspaceId) request.workspaceId = workspaceId
    if (typeof agentPreset === 'string' && agentPreset) request.agentPreset = agentPreset

    return Promise.resolve()
      .then(function () {
        if (request.workspaceId || !request.cwd) return undefined
        if (workspaceSvc === undefined || typeof workspaceSvc.resolveByPath !== 'function') return undefined
        return workspaceSvc.resolveByPath(request.cwd).then(
          function (w) {
            if (w && w.id) request.workspaceId = String(w.id)
          },
          function () {
            /* 目录还没登记成工作区，保持只带 cwd */
          },
        )
      })
      .then(function () {
        return sc.create(request)
      })
      .then(function (value) {
        return { sessionId: String(value && value.sessionId), workspaceId: request.workspaceId || null }
      })
  }

  /* ------------------------- 工作区 / 目录 ------------------------- */

  /** cwd -> workspaceId 的索引，用于给每个会话标注所属工作区。 */
  function workspaceByPath() {
    const map = {}
    const list = safe(function () {
      return workspaceSvc && typeof workspaceSvc.list === 'function' ? workspaceSvc.list() : []
    }, [])
    for (let i = 0; i < list.length; i += 1) {
      const w = list[i]
      if (w && w.path !== undefined) map[String(w.path)] = String(w.id)
    }
    return map
  }

  /**
   * 工作区列表。
   *
   * 会话归属不靠我们自己算 —— workspace.sessionIds 已经是 harness 按
   * canonical-cwd 索引过滤好的，直接用它，避免两边规则不一致。
   */
  function listWorkspaces() {
    if (workspaceSvc === undefined || typeof workspaceSvc.list !== 'function') {
      return Promise.resolve({ ok: false, error: 'workspaceRegistry unavailable', workspaces: [] })
    }
    const ws = safe(function () {
      return workspaceSvc.list()
    }, [])
    const summaries = {}
    const listPromise =
      sc !== undefined && typeof sc.list === 'function'
        ? sc.list({}, makeSignal()).then(
            function (value) {
              const items = (value && value.items) || []
              for (let i = 0; i < items.length; i += 1) {
                if (items[i]) summaries[String(items[i].sessionId)] = items[i]
              }
            },
            function () {},
          )
        : Promise.resolve()

    return listPromise.then(function () {
      const live = liveSessionMap()
      const out = []
      for (let i = 0; i < ws.length; i += 1) {
        const w = ws[i]
        if (!w) continue
        const ids = (w.sessionIds || []).map(function (x) {
          return String(x)
        })
        let runningCount = 0
        const sessions = []
        for (let k = 0; k < ids.length; k += 1) {
          const sid = ids[k]
          const summary = summaries[sid]
          const running = !!(summary && summary.running)
          if (running) runningCount += 1
          sessions.push({
            id: sid,
            title: titleOf(summary, live[sid]),
            running: running,
            blank: !!(summary && summary.blank),
            updatedAt: summary && typeof summary.updatedAt === 'number' ? summary.updatedAt : 0,
            activity: activityOf(sid),
            progress: progressView(sid),
            cwd: summary && typeof summary.cwd === 'string' ? summary.cwd : String(w.path),
          })
        }
        sessions.sort(function (a, b) {
          return (b.updatedAt || 0) - (a.updatedAt || 0)
        })
        out.push({
          id: String(w.id),
          path: String(w.path || ''),
          title: String(w.title || ''),
          createdAt: w.createdAt ? String(w.createdAt) : null,
          updatedAt: w.updatedAt ? String(w.updatedAt) : null,
          sessionCount: ids.length,
          runningCount: runningCount,
          sessions: sessions,
        })
      }
      return { ok: true, workspaces: out }
    })
  }

  /* ---------------------- 路径校验（手机端输入不可信） ---------------------- */

  /** 只接受绝对路径，且不允许出现 `..` 段。 */
  function safeAbsPath(value) {
    if (typeof value !== 'string') return null
    const v = value.trim()
    if (!v || v.charAt(0) !== '/') return null
    if (v.indexOf('\u0000') >= 0) return null
    const parts = v.split('/')
    for (let i = 0; i < parts.length; i += 1) {
      if (parts[i] === '..') return null
    }
    const trimmed = v.replace(/\/+$/, '')
    return trimmed === '' ? '/' : trimmed
  }

  /** 只接受单个路径段（文件夹名），挡掉 `/`、`.`、`..` 和空白。 */
  function safeSegment(value) {
    if (typeof value !== 'string') return null
    const n = value.trim()
    if (!n || n === '.' || n === '..') return null
    if (n.length > 200) return null
    if (n.indexOf('/') >= 0 || n.indexOf('\u0000') >= 0) return null
    return n
  }

  /** POSIX 单引号转义，用于把路径安全地拼进 shell 命令。 */
  function shellQuote(value) {
    return "'" + String(value).replace(/'/g, "'\\''") + "'"
  }

  function pathJoin(parent, name) {
    return (parent === '/' ? '' : parent) + '/' + name
  }

  function crumbsOf(absPath) {
    const out = [{ name: '/', path: '/' }]
    const parts = String(absPath).split('/')
    let acc = ''
    for (let i = 0; i < parts.length; i += 1) {
      if (!parts[i]) continue
      acc += '/' + parts[i]
      out.push({ name: parts[i], path: acc })
    }
    return out
  }

  /** 家目录：只取一次并缓存（动态宿主里读不到宿主机环境变量）。 */
  let homeCache = ''
  function homeDir() {
    if (homeCache) return Promise.resolve(homeCache)
    const sh = ctx.get('shell')
    if (sh === undefined || typeof sh.resolve !== 'function' || typeof sh.run !== 'function') {
      homeCache = '/'
      return Promise.resolve(homeCache)
    }
    return Promise.resolve()
      .then(function () {
        return sh.run(sh.resolve({ command: 'printf %s "$HOME"', workdir: '/', timeoutMs: 5000 }))
      })
      .then(
        function (r) {
          const v = ((r && r.stdout && r.stdout.text) || '').trim()
          homeCache = v && v.charAt(0) === '/' ? v : '/'
          return homeCache
        },
        function () {
          homeCache = '/'
          return homeCache
        },
      )
  }

  /* --------------------------- 目录浏览 / 新建 --------------------------- */

  function doFsList(path) {
    const fsSvc = ctx.get('fs')
    if (fsSvc === undefined || typeof fsSvc.resolve !== 'function' || typeof fsSvc.listDir !== 'function') {
      return Promise.reject(new Error('fs service unavailable'))
    }
    const wanted = safeAbsPath(path)
    return homeDir().then(function (home) {
      const start = wanted || home
      return fsSvc.resolve(start, { signal: makeSignal() }).then(function (target) {
        return fsSvc.listDir(target, makeSignal()).then(function (entries) {
          const dirs = []
          const list = entries || []
          for (let i = 0; i < list.length; i += 1) {
            const e = list[i]
            if (!e || e.type !== 'directory') continue
            const p = e.target && e.target.displayPath ? String(e.target.displayPath) : ''
            if (!p) continue
            dirs.push({ name: String(e.name || ''), path: p, hidden: String(e.name || '').charAt(0) === '.' })
          }
          dirs.sort(function (a, b) {
            const ah = a.hidden ? 1 : 0
            const bh = b.hidden ? 1 : 0
            if (ah !== bh) return ah - bh
            return a.name.localeCompare(b.name)
          })
          const shown = dirs.slice(0, 400)
          return {
            ok: true,
            path: target.displayPath ? String(target.displayPath) : start,
            home: home,
            truncated: dirs.length > shown.length,
            crumbs: crumbsOf(target.displayPath ? String(target.displayPath) : start),
            entries: shown,
          }
        })
      })
    })
  }

  /**
   * 在电脑上新建文件夹。
   *
   * 优先用 harness 自己的目录选择器后端（它自带校验）；这台机器的组合装的是
   * native 选择器、没有 browse 能力，所以退回用 shell 执行 mkdir。
   * 走 shell 就必须自己把关：路径与名字都先过严格校验，再单引号转义。
   */
  function doFsMkdir(parent, name) {
    const p = safeAbsPath(parent)
    const n = safeSegment(name)
    if (p === null) return Promise.reject(new Error('parent 必须是绝对路径且不含 ..'))
    if (n === null) return Promise.reject(new Error('文件夹名不合法（不能为空、不能含 /、不能是 . 或 ..）'))
    const target = pathJoin(p, n)

    if (dirPicker !== undefined && typeof dirPicker.createDirectory === 'function') {
      return dirPicker.createDirectory(p, n).then(
        function (created) {
          return { ok: true, path: String(created || target), via: 'directoryPicker' }
        },
        function () {
          return mkdirViaShell(target, p)
        },
      )
    }
    return mkdirViaShell(target, p)
  }

  function mkdirViaShell(target, workdir) {
    const sh = ctx.get('shell')
    if (sh === undefined || typeof sh.resolve !== 'function' || typeof sh.run !== 'function') {
      return Promise.reject(new Error('既没有 directoryPicker.createDirectory，也没有 shell 可用'))
    }
    return Promise.resolve()
      .then(function () {
        return sh.run(
          sh.resolve({
            command: 'mkdir -p -- ' + shellQuote(target),
            workdir: workdir,
            timeoutMs: 10000,
            // 必须显式给策略：组合里的 sandbox-policy 默认是 workspace-write
            // 且根是 harness 的 cwd，工作区目录通常落在它之外 —— 那样 mkdir 会被
            // 沙箱静默丢弃（exit 0 但文件没建出来）。这里建的是用户明确要求的目录，
            // 与用户会话自身的 danger-full-access 预设一致。
            sandboxPolicy: { mode: 'danger-full-access', workspaceRoot: workdir },
          }),
        )
      })
      .then(function (r) {
        const code = r && typeof r.exitCode === 'number' ? r.exitCode : null
        if (code !== 0) {
          const err = ((r && r.stderr && r.stderr.text) || '').trim()
          throw new Error('mkdir 失败（exit ' + code + '）：' + err.slice(0, 200))
        }
        // 不信 exitCode：沙箱可能让命令"成功"但写不进去。用 fs 复核。
        return verifyDirectory(target).then(function (exists) {
          if (!exists) {
            const sb = r && r.sandbox ? ' sandbox=' + String(r.sandbox.mode) + '/denied=' + String(r.sandbox.denied) : ''
            throw new Error('mkdir 报告成功但目录不存在（可能被沙箱拦下）' + sb)
          }
          return { ok: true, path: target, via: 'shell' }
        })
      })
  }

  /** 复核目录确实存在且是目录。 */
  function verifyDirectory(absPath) {
    const fsSvc = ctx.get('fs')
    if (fsSvc === undefined || typeof fsSvc.resolve !== 'function' || typeof fsSvc.stat !== 'function') {
      return Promise.resolve(true)
    }
    return fsSvc.resolve(absPath, { signal: makeSignal() }).then(
      function (t) {
        return fsSvc.stat(t, makeSignal()).then(function (info) {
          return !!(info && info.type === 'directory')
        })
      },
      function () {
        return false
      },
    )
  }

  function doWorkspaceCreate(path, title) {
    if (workspaceSvc === undefined || typeof workspaceSvc.create !== 'function') {
      return Promise.reject(new Error('workspaceRegistry.create unavailable'))
    }
    if (typeof path !== 'string' || !path) return Promise.reject(new Error('path required'))
    // create() 会对路径做 realpath，目录不存在会直接 reject —— 所以调用方要先 mkdir
    return workspaceSvc.create(path, typeof title === 'string' && title ? title : undefined).then(function (w) {
      return { ok: true, id: String(w.id), path: String(w.path || ''), title: String(w.title || '') }
    })
  }

  /* ------------------------------------------------------------------ *
   * 6bis. 手机终端（真 PTY）
   *
   * 目标：在手机上对这台电脑敲命令 —— 尤其在会话里复制一段命令后，
   * 切到终端粘上就能跑。
   *
   * 为什么不用 SSH：
   *   - Android 上没有自带 ssh 客户端，得往 APK 里塞一个 Java SSH 实现
   *     （密钥、known_hosts、密码输入全要自己做），体积和出错面都大；
   *   - 还要多开一条通道（adb reverse 22 或 tailscale serve 22）。
   * 这里直接用宿主已有的 `subprocess.spawnTerminal` —— 它是一个**真 PTY**
   * （不是把 bash 挂在管道上），所以提示符、行编辑、Ctrl-C、颜色、
   * vim/htop 这类全屏程序都能用。传输走已有的 /phone/*（同一个端口、同一把令牌），
   * 在家走 adb reverse、在外走 tailnet，都是低延迟路径。
   *
   * 三条纪律：
   *   - 终端属于**这台电脑**，不属于任何会话；插件卸载时必须全部杀掉，
   *     否则 reload 一次就漏一堆 bash 进程；
   *   - 每条连接保留一段输出环形缓冲，手机切走再回来能接上（见 snapshot）；
   *   - 没人看、也没人敲的终端超时回收（宿主没有 setTimeout，用 every() 扫）。
   * ------------------------------------------------------------------ */

  const subprocessSvc = ctx.get('subprocess')
  const terms = {}
  let termSeq = 0
  const TERM_MAX = 4
  const TERM_BUF_MAX = 96 * 1024
  const TERM_IDLE_MS = 30 * 60 * 1000
  const TERM_DEFAULT_COLS = 100
  const TERM_DEFAULT_ROWS = 32

  function clampInt(value, lo, hi, fallback) {
    const n = typeof value === 'number' ? value : parseInt(value, 10)
    if (!isFinite(n)) return fallback
    return Math.max(lo, Math.min(hi, Math.floor(n)))
  }

  /**
   * 收尾：杀掉 PTY、通知所有客户端、从表里删掉。
   *
   * 幂等 —— `done`、超时回收、手机点「关闭」、插件卸载可能同时到达。
   */
  function termClose(id, reason) {
    const t = terms[id]
    if (!t) return
    delete terms[id]
    try {
      if (t.handle && typeof t.handle.terminate === 'function') t.handle.terminate()
    } catch (err) {
      /* 已经退了 */
    }
    t.closed = true
    for (let i = 0; i < t.clients.length; i += 1) {
      const res = t.clients[i]
      try {
        if (!res.writableEnded) {
          sseSend(res, 'closed', { reason: reason || 'closed' })
          res.end()
        }
      } catch (err) {
        /* 忽略 */
      }
    }
    t.clients.length = 0
  }

  /** 输出只留最后一段：手机重连时拿到的是"最近这些"，不是从头开始。 */
  function termAppend(t, text) {
    t.buf += text
    if (t.buf.length > TERM_BUF_MAX) t.buf = t.buf.slice(t.buf.length - TERM_BUF_MAX)
    t.lastOut = Date.now()
  }

  function termBroadcast(t, event, payload) {
    for (let i = t.clients.length - 1; i >= 0; i -= 1) {
      const res = t.clients[i]
      if (!res || res.writableEnded) {
        t.clients.splice(i, 1)
        continue
      }
      sseSend(res, event, payload)
    }
  }

  /**
   * 把 PTY 的输出泵到手机上。
   *
   * 用**同一个** TextDecoder 逐块 decode({stream:true})：一个汉字被切成两半时
   * 才不会变成乱码 —— 中文输出在这个项目里是常态，这个坑必须躲开。
   */
  function termPump(t) {
    const decoder = new TextDecoder('utf-8')
    const stream = t.handle.output
    const iterator = stream[Symbol.asyncIterator] ? stream[Symbol.asyncIterator]() : null
    if (!iterator) return
    function step() {
      iterator.next().then(
        function (r) {
          if (!terms[t.id]) return
          if (r.done) {
            termFinish(t)
            return
          }
          const chunk = r.value
          const text = typeof chunk === 'string' ? chunk : decoder.decode(chunk, { stream: true })
          if (text) {
            termAppend(t, text)
            termBroadcast(t, 'out', { data: text })
          }
          step()
        },
        function () {
          termFinish(t)
        },
      )
    }
    step()
  }

  function termFinish(t) {
    if (t.exited) return
    t.exited = true
    termBroadcast(t, 'exit', { code: t.exitCode, signal: t.exitSignal })
  }

  /** 空闲回收：没人连着、也没人敲，超过 TERM_IDLE_MS 就关掉。 */
  function sweepTerms() {
    const now = Date.now()
    const ids = Object.keys(terms)
    for (let i = 0; i < ids.length; i += 1) {
      const t = terms[ids[i]]
      if (!t) continue
      if (t.exited && t.clients.length === 0) {
        termClose(ids[i], 'exited')
        continue
      }
      if (t.clients.length === 0 && now - (t.lastActive || t.createdAt) > TERM_IDLE_MS) {
        termClose(ids[i], 'idle')
      }
    }
  }
  every(20_000, function () {
    safe(sweepTerms, undefined)
    safe(termHeartbeat, undefined)
  })

  /**
   * 蜂窝网下 SSE 静默太久会被中间设备掐掉（状态流本来就一直在推，不怕；
   * 终端可以几分钟没输出），所以定期打个注释行当心跳。
   */
  function termHeartbeat() {
    const ids = Object.keys(terms)
    for (let i = 0; i < ids.length; i += 1) {
      const t = terms[ids[i]]
      for (let k = t.clients.length - 1; k >= 0; k -= 1) {
        const res = t.clients[k]
        if (!res || res.writableEnded) {
          t.clients.splice(k, 1)
          continue
        }
        try {
          res.write(': ping\n\n')
        } catch (err) {
          t.clients.splice(k, 1)
        }
      }
    }
  }

  /** 默认工作目录：手机上没指定就落到家目录。 */
  function termDefaultCwd() {
    return homeDir().then(
      function (home) {
        return home || '/'
      },
      function () {
        return '/'
      },
    )
  }

  /**
   * 用 `/usr/bin/env VAR=val cmd` 给 shell 补 TERM，而不是往 spec.env 里塞。
   *
   * 原因：`SubprocessTerminalSpawnSpec.env` 的语义是"完全指定环境"
   * （没有默认值可继承），只塞一个 TERM 有把 PATH/HOME 一起抹掉的风险 ——
   * 那就从"没颜色"变成"命令都找不着"了。env(1) 只动指定的变量，其余原样传递，
   * 是唯一不用猜的写法。实测宿主的基线环境是 TERM=dumb + LANG=zh_CN.UTF-8。
   */
  let envBinCache = null
  function resolveEnvBin() {
    if (envBinCache !== null) return Promise.resolve(envBinCache)
    const fsSvc = ctx.get('fs')
    if (fsSvc === undefined || typeof fsSvc.resolve !== 'function' || typeof fsSvc.stat !== 'function') {
      envBinCache = ''
      return Promise.resolve('')
    }
    return Promise.resolve()
      .then(function () {
        return fsSvc.resolve('/usr/bin/env', { signal: makeSignal() })
      })
      .then(
        function (target) {
          return fsSvc.stat(target, makeSignal())
        },
        function () {
          return undefined
        },
      )
      .then(
        function (info) {
          envBinCache = info && info.type === 'file' ? '/usr/bin/env' : ''
          return envBinCache
        },
        function () {
          envBinCache = ''
          return ''
        },
      )
  }

  /** 开一个终端。cols/rows 在分配时就定死了（PTY 尺寸没有 resize 接口）。 */
  function termOpen(body) {
    if (subprocessSvc === undefined || typeof subprocessSvc.spawnTerminal !== 'function') {
      return Promise.reject(new Error('这台电脑的 harness 没挂 subprocess 服务，开不了终端'))
    }
    sweepTerms()
    if (Object.keys(terms).length >= TERM_MAX) {
      return Promise.reject(new Error('终端开太多了（最多 ' + TERM_MAX + ' 个），先关掉一个'))
    }
    const cols = clampInt(body.cols, 20, 400, TERM_DEFAULT_COLS)
    const rows = clampInt(body.rows, 6, 200, TERM_DEFAULT_ROWS)
    const shell = typeof body.shell === 'string' && body.shell.indexOf('/') === 0 ? body.shell : '/bin/bash'
    const wanted = safeAbsPath(body.cwd)
    const id = 't' + (termSeq += 1) + '-' + Date.now().toString(36)
    return Promise.all([
      wanted ? Promise.resolve(wanted) : termDefaultCwd(),
      resolveEnvBin(),
    ]).then(function (pair) {
      const cwd = pair[0]
      const envBin = pair[1]
      // 真终端该有的 TERM；没有 env(1) 就退回裸 bash（功能不受影响，只是没颜色）
      const argv = envBin
        ? [envBin, 'TERM=xterm-256color', 'COLORTERM=truecolor', shell, '-l']
        : [shell, '-l']
      const spec = {
        argv: argv,
        cwd: cwd,
        rows: rows,
        cols: cols,
        graceMs: 3000,
      }
      return subprocessSvc.spawnTerminal(spec).then(function (handle) {
        const t = {
          id: id,
          handle: handle,
          pid: typeof handle.pid === 'number' ? handle.pid : 0,
          buf: '',
          clients: [],
          createdAt: Date.now(),
          lastActive: Date.now(),
          exited: false,
          exitCode: null,
          exitSignal: null,
          cwd: cwd,
          cols: cols,
          rows: rows,
        }
        terms[id] = t
        Promise.resolve(handle.done).then(
          function (outcome) {
            t.exitCode = outcome && typeof outcome.exitCode === 'number' ? outcome.exitCode : null
            t.exitSignal = outcome && outcome.signal ? String(outcome.signal) : null
            termFinish(t)
          },
          function () {
            termFinish(t)
          },
        )
        termPump(t)
        return { ok: true, id: id, pid: t.pid, cwd: cwd, cols: cols, rows: rows }
      })
    })
  }

  /** 手机侧的输入。data 里可以带 \r（回车）、\u0003（Ctrl-C）等控制字符。 */
  function termWrite(id, data) {
    const t = terms[id]
    if (!t) return Promise.reject(new Error('终端不存在或已关闭'))
    if (t.exited) return Promise.reject(new Error('终端已经退出了'))
    t.lastActive = Date.now()
    return Promise.resolve(t.handle.write(data)).then(function () {
      return { ok: true }
    })
  }

  /** Ctrl-C 之类：交给 PTY 的前台进程组，不是 kill 掉这个终端。 */
  function termSignal(id, name) {
    const t = terms[id]
    if (!t) return Promise.reject(new Error('终端不存在或已关闭'))
    const allowed = ['SIGINT', 'SIGTERM', 'SIGKILL', 'SIGTSTP', 'SIGHUP']
    if (allowed.indexOf(name) < 0) return Promise.reject(new Error('不允许的信号：' + name))
    t.lastActive = Date.now()
    return Promise.resolve(t.handle.signalForeground(name)).then(function (pgid) {
      return { ok: true, pgid: typeof pgid === 'number' ? pgid : 0 }
    })
  }

  /** 单终端实时流：先补一段缓冲（切走再回来能接上），再接增量。 */
  function handleTermStream(req, res, id) {
    const t = terms[id]
    if (!t) {
      sendJson(res, 404, { ok: false, error: '终端不存在或已关闭' })
      return
    }
    openSse(req, res)
    t.clients.push(res)
    t.lastActive = Date.now()
    sseSend(res, 'hello', {
      id: id,
      pid: t.pid,
      cwd: t.cwd,
      cols: t.cols,
      rows: t.rows,
      buffered: t.buf,
      exited: t.exited,
      exitCode: t.exitCode,
    })
    function drop() {
      const idx = t.clients.indexOf(res)
      if (idx >= 0) t.clients.splice(idx, 1)
      try {
        res.end()
      } catch (err) {
        /* 已经关了 */
      }
    }
    req.on('close', drop)
    req.on('error', drop)
  }

  function termList() {
    const ids = Object.keys(terms)
    const out = []
    for (let i = 0; i < ids.length; i += 1) {
      const t = terms[ids[i]]
      out.push({
        id: t.id,
        pid: t.pid,
        cwd: t.cwd,
        cols: t.cols,
        rows: t.rows,
        clients: t.clients.length,
        buffered: t.buf.length,
        exited: t.exited,
        exitCode: t.exitCode,
        idleMs: Date.now() - (t.lastActive || t.createdAt),
      })
    }
    return out
  }

  /** 插件卸载 / 更新：一个 PTY 都不许留。 */
  function termKillAll() {
    const ids = Object.keys(terms)
    for (let i = 0; i < ids.length; i += 1) termClose(ids[i], 'plugin unload')
  }

  /* ------------------------------------------------------------------ *
   * 6ter. 排队消息
   *
   * 场景：harness 正在跑（在思考、在调工具），手机又发了一条 —— 它进的是
   * 会话的**待处理队列**，不是立刻执行。用户必须能看见"排了几条"，
   * 并且能改、能撤（发错了、想补一句、想改个措辞都要能收回来）。
   *
   * 数据来源是宿主的 `sessionController.control()`：它先给一份**全量基线**
   * （queues/jobs/projections），之后推增量帧。桥接把 queues 镜像下来，
   * 一份用于 GET 查询，一份直接推给正在看这个会话的手机（事件名 `queue`）。
   *
   * 为什么不让手机自己去调 control()：那是一条长期流，手机一断线就丢状态；
   * 而桥接本来就常驻，镜像一份最省事，也避免多个手机各拉一条流。
   * ------------------------------------------------------------------ */

  const queues = {}
  let controlRunning = false
  let controlRetryAt = 0
  let controlFrames = 0

  /** 队列项里真正要显示的文字（content 是 ContentBlock 数组，只取 text 块）。 */
  function queueItemText(item) {
    const msg = item && item.message ? item.message : {}
    const content = Array.isArray(msg.content) ? msg.content : []
    const parts = []
    for (let i = 0; i < content.length; i += 1) {
      const b = content[i]
      if (b && b.type === 'text' && typeof b.text === 'string') parts.push(b.text)
    }
    return parts.join('\n')
  }

  /** 转成手机要的形状；不把宿主的活对象原样丢出去。 */
  function queueViews(sessionId) {
    const list = queues[sessionId]
    if (!Array.isArray(list)) return []
    const out = []
    for (let i = 0; i < list.length; i += 1) {
      const it = list[i]
      if (!it) continue
      out.push({
        id: String(it.id || ''),
        placement: typeof it.placement === 'string' ? it.placement : 'queued',
        text: queueItemText(it),
      })
    }
    return out
  }

  function notifyQueue(sessionId) {
    if (!sessionId) return
    sessionStreamSend(sessionId, 'queue', { items: queueViews(sessionId) })
  }

  function applyControlFrame(frame) {
    if (!frame || typeof frame !== 'object') return
    controlFrames += 1
    if (frame.type === 'baseline') {
      const value = frame.value || {}
      const next = value.queues && typeof value.queues === 'object' ? value.queues : {}
      const ids = Object.keys(queues)
      for (let i = 0; i < ids.length; i += 1) delete queues[ids[i]]
      const keys = Object.keys(next)
      for (let i = 0; i < keys.length; i += 1) {
        queues[keys[i]] = Array.isArray(next[keys[i]]) ? next[keys[i]] : []
      }
      for (let i = 0; i < keys.length; i += 1) notifyQueue(keys[i])
      return
    }
    if (frame.type === 'queue') {
      const sid = String(frame.sessionId || '')
      if (!sid) return
      queues[sid] = Array.isArray(frame.items) ? frame.items : []
      notifyQueue(sid)
    }
  }

  /**
   * 常驻消费 control() 流。
   *
   * 宿主没有 setTimeout，所以重连不自己排定时器，而是挂在那条 1 秒的
   * `every()` 上（见下面的 ensureControlStream），失败后 5 秒再试。
   */
  function startControlStream() {
    if (controlRunning) return
    if (sc === undefined || typeof sc.control !== 'function') return
    controlRunning = true
    const signal = makeSignal()
    function giveUp() {
      controlRunning = false
      controlRetryAt = Date.now() + 5000
    }
    Promise.resolve()
      .then(function () {
        return sc.control(signal)
      })
      .then(
        function (iter) {
          const it = iter && iter[Symbol.asyncIterator] ? iter[Symbol.asyncIterator]() : iter
          if (!it || typeof it.next !== 'function') {
            giveUp()
            return
          }
          function step() {
            it.next().then(
              function (r) {
                if (r.done) {
                  giveUp()
                  return
                }
                safe(function () {
                  applyControlFrame(r.value)
                }, undefined)
                step()
              },
              function () {
                giveUp()
              },
            )
          }
          step()
        },
        function () {
          giveUp()
        },
      )
  }

  function ensureControlStream() {
    if (controlRunning) return
    if (Date.now() < controlRetryAt) return
    startControlStream()
  }

  /**
   * 清除当前目标。
   *
   * 为什么需要：宿主把**已完成**的目标留在注册表里当墓碑（`create` 的说明里写着
   * "已完成的目标可以被替换，其它阶段必须先清除或恢复"）—— 不清就一直显示。
   * clear 只清"当前目标"，会话日志里的历史仍在（宿主自己的说明：
   * "retaining a durable tombstone and history"）。
   *
   * ref 由服务端现取，不信客户端传的 revision：手机那边显示的可能已经过期，
   * 过期就会被宿主的乐观并发挡下来，反而让用户莫名其妙地清不掉。
   */
  function doGoalClear(sessionId) {
    if (!goalsSvc || typeof goalsSvc.clear !== 'function' || !agentsSvc || typeof agentsSvc.get !== 'function') {
      return Promise.reject(new Error('这个 harness 版本不支持清除目标'))
    }
    return Promise.resolve()
      .then(function () {
        const agent = agentsSvc.get(sessionId)
        if (!agent) return Promise.reject(new Error('会话不在活动状态（冷会话没有 live Agent）'))
        const g = goalsSvc.get(agent)
        if (!g) return Promise.reject(new Error('这个会话当前没有目标'))
        return goalsSvc.clear(agent, { id: g.id, revision: g.revision })
      })
      .then(function () {
        markStateDirty()
        return { ok: true }
      })
  }

  /* ---------------------- 手机往会话里传文件 ---------------------- */

  const UPLOAD_MAX_BYTES = 64 * 1024 * 1024

  /**
   * `POST /phone/upload?sessionId=<id>&name=<文件名>`，请求体就是原始字节。
   *
   * 走宿主的 `fileUploads.uploadStream`：它按会话落盘并给一张 **receipt**，
   * 之后发消息时把这个 receipt 放进 `content` 的 `{type:'file', receiptId}` 块就行
   * （`sessionController.prompt` 会自己 resolve + bind，读了实现确认过）。
   *
   * 两个刻意的选择：
   *   - **不把文件拼成一整块再交出去**：`req` 本身就是 AsyncIterable<Uint8Array>，
   *     直接喂给宿主，几十兆的附件不会在桥接里翻倍占内存；
   *   - 先看 `content-length` 再收：超限直接 413，不白写一遍磁盘。
   */
  function handleUpload(req, res, query) {
    const uploads = ctx.get('fileUploads')
    if (uploads === undefined || typeof uploads.uploadStream !== 'function') {
      sendJson(res, 503, { ok: false, error: '这台电脑的 harness 没挂 fileUploads 服务，传不了文件' })
      req.resume()
      return
    }
    const sessionId = typeof query.sessionId === 'string' ? query.sessionId : ''
    if (!sessionId) {
      sendJson(res, 400, { ok: false, error: '缺少 sessionId' })
      req.resume()
      return
    }
    const declared = parseInt(String(req.headers['content-length'] || '0'), 10)
    if (isFinite(declared) && declared > UPLOAD_MAX_BYTES) {
      sendJson(res, 413, {
        ok: false,
        error: '文件太大（上限 ' + Math.round(UPLOAD_MAX_BYTES / 1024 / 1024) + ' MB）',
      })
      req.resume()
      return
    }
    const name = typeof query.name === 'string' && query.name ? query.name : 'upload.bin'
    safe(function () {
      return Promise.resolve()
        .then(function () {
          return uploads.uploadStream({
            sessionId: sessionId,
            data: req,
            name: name,
            signal: makeSignal(),
          })
        })
        .then(
          function (value) {
            const file = (value && value.file) || {}
            console.log('[' + NAME + '] upload ' + name + ' → ' + String(file.bytes || 0) + ' bytes')
            sendJson(res, 200, {
              ok: true,
              receiptId: String((value && value.receiptId) || ''),
              name: String(file.name || name),
              bytes: typeof file.bytes === 'number' ? file.bytes : 0,
            })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
    }, undefined)
  }

  /** 改/撤一条还没轮到执行的排队消息。 */
  function doQueueUpdate(sessionId, itemId, body) {
    if (sc === undefined || typeof sc.updateQueue !== 'function') {
      return Promise.reject(new Error('这个 harness 版本没有 updateQueue'))
    }
    if (!itemId) return Promise.reject(new Error('缺少排队项 id'))
    const kind = String((body && body.action) || '')
    let action
    if (kind === 'remove') {
      action = { kind: 'remove' }
    } else if (kind === 'steer') {
      action = { kind: 'steer' }
    } else if (kind === 'edit') {
      const text = body && typeof body.text === 'string' ? body.text : ''
      if (!text.trim()) return Promise.reject(new Error('改成空的内容没有意义'))
      action = { kind: 'edit', content: [{ type: 'text', text: text }] }
    } else {
      return Promise.reject(new Error('不认识的队列操作：' + kind))
    }
    return Promise.resolve()
      .then(function () {
        return sc.updateQueue({ sessionId: sessionId, itemId: itemId, action: action })
      })
      .then(function (value) {
        // 本地先乐观更新一次，界面上按钮点下去立刻有反馈；
        // 权威值随后由 control() 推回来覆盖。
        const list = queues[sessionId]
        if (Array.isArray(list)) {
          if (kind === 'remove') {
            queues[sessionId] = list.filter(function (it) {
              return String(it && it.id) !== itemId
            })
            notifyQueue(sessionId)
          }
        }
        return { ok: true, accepted: !!(value && value.accepted) }
      })
  }

  /* ------------------------------------------------------------------ *
   * 7. 路由
   * ------------------------------------------------------------------ */

  function handle(req, res) {
    let parsed
    try {
      parsed = parseUrl(req.url)
    } catch (err) {
      sendText(res, 400, 'bad url')
      return
    }
    const path = parsed.path
    const query = parsed.query

    if (path.indexOf(BASE) !== 0) {
      sendText(res, 404, 'not a bridge route')
      return
    }
    if (req.method === 'OPTIONS') {
      res.writeHead(204, corsHeaders())
      res.end()
      return
    }
    if (!authorized(req, query)) {
      sendJson(res, 401, { ok: false, error: 'unauthorized' })
      return
    }

    const seg = segments(path)

    // GET /phone/health
    if (seg.length === 1 && seg[0] === 'health') {
      sendJson(res, 200, { ok: true, name: NAME, version: VERSION, ts: Date.now() })
      return
    }

    // GET /phone/state?watching=<sessionId>
    // App 每次轮询顺便报一次"我还开着这个会话"，这就是心跳
    if (seg.length === 1 && seg[0] === 'state') {
      if (query.watching) touchWatch(query.watching)
      buildState(query.max ? parseInt(query.max, 10) : 60).then(function (state) {
        sendJson(res, 200, state)
      })
      return
    }

    // GET /phone/events  (SSE)
    if (seg.length === 1 && seg[0] === 'events') {
      handleStateStream(req, res)
      return
    }

    // POST /phone/new   body: { cwd?, workspaceId?, agentPreset? }
    if (seg.length === 1 && seg[0] === 'new') {
      parseBody(req).then(function (body) {
        doCreate(body.cwd, body.workspaceId, body.agentPreset).then(
          function (value) {
            markStateDirty()
            sendJson(res, 200, { ok: true, sessionId: value.sessionId })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
      })
      return
    }

    // GET /phone/diag   内部状态自检（排查"手机上没收到提问"这类问题）
    if (seg.length === 1 && seg[0] === 'diag') {
      sendJson(res, 200, {
        ok: true,
        watchedSessions: watchedKeys(),
        watchTtlMs: WATCH_TTL_MS,
        sessionStreams: Object.keys(sessionStreamClients),
        pendingQuestions: Object.keys(pendingQuestions),
        questionEventCount: questionEventCount,
        sessionEventCount: sessionEventCount,
        backfill: { done: backfillDone, sessions: backfillCount, tracked: Object.keys(activity).length },
        lastSessionEvent: lastSessionEventDebug,
        lastQuestion: lastQuestionDebug,
        stateClients: Object.keys(stateClients).length,
        terminals: termList(),
        queue: { controlRunning: controlRunning, frames: controlFrames, sessions: Object.keys(queues).length },
        progress: {
          sessions: Object.keys(progress).length,
          modelWindows: modelWindows,
          // 事件类型直方图：排查"某个事件到底有没有到桥接"时最有用 ——
          // 实测根监听器只覆盖一部分会话，这份计数一眼就能看出来
          eventTypes: eventTypeCounts,
          lastContextEvent: lastContextEventDebug,
        },
      })
      return
    }

    // POST /phone/upload?sessionId=<id>&name=<文件名>   手机把文件传进会话
    if (seg.length === 1 && seg[0] === 'upload' && req.method === 'POST') {
      handleUpload(req, res, query)
      return
    }

    // POST /phone/sessions/<id>/goal/clear   清掉当前目标（已完成的目标会一直留着）
    if (seg.length === 4 && seg[0] === 'sessions' && seg[2] === 'goal' && seg[3] === 'clear' && req.method === 'POST') {
      doGoalClear(seg[1]).then(
        function (value) {
          sendJson(res, 200, value)
        },
        function (err) {
          sendJson(res, 400, { ok: false, error: String((err && err.message) || err) })
        },
      )
      return
    }

    // GET /phone/sessions/<id>/queue   还没轮到执行的排队消息
    // ★ 段数按剥掉 BASE 之后算：/phone/sessions/<id>/queue → 3 段
    if (seg.length === 3 && seg[0] === 'sessions' && seg[2] === 'queue' && req.method === 'GET') {
      sendJson(res, 200, { ok: true, items: queueViews(seg[1]) })
      return
    }

    // POST /phone/sessions/<id>/queue/<itemId>  { action: 'remove'|'steer'|'edit', text? }
    if (seg.length === 4 && seg[0] === 'sessions' && seg[2] === 'queue' && req.method === 'POST') {
      parseBody(req).then(function (body) {
        return doQueueUpdate(seg[1], seg[3], body).then(
          function (value) {
            markStateDirty()
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 400, { ok: false, error: String((err && err.message) || err) })
          },
        )
      })
      return
    }

    // GET /phone/term/list   当前活着的终端（排查"命令卡住了"这类问题）
    if (seg.length === 2 && seg[0] === 'term' && seg[1] === 'list') {
      sendJson(res, 200, { ok: true, terminals: termList() })
      return
    }

    // POST /phone/term/open  { cols?, rows?, cwd?, shell? }  → 开一个真 PTY
    if (seg.length === 2 && seg[0] === 'term' && seg[1] === 'open') {
      parseBody(req).then(function (body) {
        return termOpen(body).then(
          function (value) {
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
      })
      return
    }

    // GET /phone/term/<id>/stream   输出实时流（SSE，先给缓冲再接增量）
    if (seg.length === 3 && seg[0] === 'term' && seg[2] === 'stream') {
      handleTermStream(req, res, seg[1])
      return
    }

    // POST /phone/term/<id>/input  { data }   键盘输入（可含 \r 与控制字符）
    if (seg.length === 3 && seg[0] === 'term' && seg[2] === 'input') {
      parseBody(req).then(function (body) {
        const data = typeof body.data === 'string' ? body.data : ''
        if (!data) {
          sendJson(res, 400, { ok: false, error: 'data required' })
          return
        }
        return termWrite(seg[1], data).then(
          function (value) {
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 404, { ok: false, error: String((err && err.message) || err) })
          },
        )
      })
      return
    }

    // POST /phone/term/<id>/signal { signal: 'SIGINT' | ... }   Ctrl-C 等
    if (seg.length === 3 && seg[0] === 'term' && seg[2] === 'signal') {
      parseBody(req).then(function (body) {
        return termSignal(seg[1], String(body.signal || 'SIGINT')).then(
          function (value) {
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 400, { ok: false, error: String((err && err.message) || err) })
          },
        )
      })
      return
    }

    // POST /phone/term/<id>/close   关掉这个终端的 shell
    if (seg.length === 3 && seg[0] === 'term' && seg[2] === 'close') {
      termClose(seg[1], 'phone request')
      sendJson(res, 200, { ok: true })
      return
    }

    // GET /phone/file?path=<p>&sessionId=<id>   读一个文本文件（给文档阅读页用）
    if (seg.length === 1 && seg[0] === 'file') {
      const max = query.max ? parseInt(query.max, 10) : undefined
      doReadFile(query.path, query.sessionId, max).then(
        function (value) {
          sendJson(res, 200, value)
        },
        function (err) {
          sendJson(res, 404, { ok: false, error: String((err && err.message) || err) })
        },
      )
      return
    }

    // GET  /phone/models?sessionId=<id>   模型目录 + 该会话当前选择
    // POST /phone/model  { sessionId, provider, model, reasoningEffort? }
    //
    // ★ 单复数两条都要收：契约（README / App / 本文件上面的注释）写的是
    //   /phone/model，而这里一度只实现了 /phone/models —— 于是 App 切模型
    //   永远 404「unknown bridge route」。这种"两边各自看着都对"的偏差，
    //   只有真发一次请求才发现，所以离线测试里专门钉了一条。
    if (seg.length === 1 && (seg[0] === 'models' || seg[0] === 'model')) {
      if (req.method === 'POST') {
        parseBody(req).then(function (body) {
          doSelectModel(body.sessionId, body.provider, body.model, body.reasoningEffort).then(
            function (selected) {
              markStateDirty()
              sendJson(res, 200, { ok: true, selected: selected })
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      listModels(query.sessionId).then(
        function (value) {
          sendJson(res, 200, value)
        },
        function (err) {
          sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
        },
      )
      return
    }

    // POST /phone/answer   body: { id, answers:[{id,selected,custom?}] }
    if (seg.length === 1 && seg[0] === 'answer' && req.method === 'POST') {
      parseBody(req).then(function (body) {
        const entry = pendingQuestions[String(body.id || '')]
        if (!entry || entry.settled) {
          sendJson(res, 409, { ok: false, error: '这个问题已经结束或不存在' })
          return
        }
        const answers = normalizeAnswers(body.answers)
        if (answers.length === 0) {
          sendJson(res, 400, { ok: false, error: 'answers 为空' })
          return
        }
        settleQuestion(entry, { answers: answers }, 'answered')
        sendJson(res, 200, { ok: true })
      })
      return
    }

    // GET /phone/workspaces        历史 + 当前的工作区（含各自会话）
    // POST /phone/workspaces/create { path, title? }
    if (seg.length >= 1 && seg[0] === 'workspaces') {
      const action = seg.length >= 2 ? seg[1] : ''
      const reply = function (promise) {
        promise.then(
          function (value) {
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
      }
      if (action === 'create' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          reply(
            doWorkspaceCreate(body.path, body.title).then(function (value) {
              markStateDirty()
              return value
            }),
          )
        })
        return
      }
      if (action === '' || action === 'list') {
        reply(listWorkspaces())
        return
      }
      if (seg.length >= 3 && seg[2] === 'rename' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          doWorkspaceRename(seg[1], body.title).then(
            function (value) {
              markStateDirty()
              sendJson(res, 200, { ok: true, id: value.id, title: value.title })
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      if (seg.length >= 3 && seg[2] === 'delete' && req.method === 'POST') {
        doWorkspaceDelete(seg[1]).then(
          function (value) {
            markStateDirty()
            sendJson(res, 200, { ok: true, deleted: value.deleted })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
        return
      }
      sendJson(res, 404, { ok: false, error: 'unknown workspaces action: ' + action })
      return
    }

    // GET  /phone/fs/list?path=<abs>      浏览电脑上的目录（用于选/建项目文件夹）
    // POST /phone/fs/mkdir { parent, name }  在电脑上新建文件夹
    if (seg.length === 2 && seg[0] === 'fs') {
      if (seg[1] === 'list') {
        doFsList(query.path).then(
          function (value) {
            sendJson(res, 200, value)
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
        return
      }
      if (seg[1] === 'mkdir' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          doFsMkdir(body.parent, body.name).then(
            function (value) {
              sendJson(res, 200, value)
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      sendJson(res, 404, { ok: false, error: 'unknown fs action: ' + seg[1] })
      return
    }

    // GET /phone/sessions/<id>[/history|/live]
    if (seg.length >= 2 && seg[0] === 'sessions') {
      const sessionId = seg[1]
      const action = seg.length >= 3 ? seg[2] : 'history'
      const max = query.max ? parseInt(query.max, 10) : 40

      if (action === 'live') {
        handleSessionStream(req, res, sessionId, max)
        return
      }
      if (action === 'history') {
        const beforeSeq = query.beforeSeq ? parseInt(query.beforeSeq, 10) : 0
        loadHistory(sessionId, max, beforeSeq).then(
          function (value) {
            const msgs = value.messages || []
            let oldest = 0
            for (let i = 0; i < msgs.length; i += 1) {
              const q = typeof msgs[i].seq === 'number' ? msgs[i].seq : 0
              if (q > 0 && (oldest === 0 || q < oldest)) oldest = q
            }
            sendJson(res, 200, {
              ok: true,
              sessionId: sessionId,
              cursor: value.cursor,
              header: value.header,
              hasMore: value.hasMore === true,
              oldestSeq: oldest,
              messages: msgs,
            })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
        return
      }
      if (action === 'prompt' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          const text = typeof body.text === 'string' ? body.text : ''
          const receiptIds = Array.isArray(body.receiptIds) ? body.receiptIds : []
          // 允许"只发附件、不写字"（转发个文件本来就是常见动作）
          if (!text.trim() && receiptIds.length === 0) {
            sendJson(res, 400, { ok: false, error: 'empty text' })
            return
          }
          doPrompt(sessionId, text, receiptIds).then(
            function (value) {
              markStateDirty()
              sendJson(res, 200, { ok: true, accepted: value.accepted })
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      if (action === 'cancel' && req.method === 'POST') {
        doCancel(sessionId).then(
          function (value) {
            markStateDirty()
            sendJson(res, 200, { ok: true, cancelled: value.cancelled })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
        return
      }
      if (action === 'rename' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          doSessionRename(sessionId, body.title).then(
            function (value) {
              markStateDirty()
              sendJson(res, 200, { ok: true, title: value.title })
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      if (action === 'fork' && req.method === 'POST') {
        parseBody(req).then(function (body) {
          const at = typeof body.atSeq === 'number' ? body.atSeq : undefined
          doSessionFork(sessionId, at).then(
            function (value) {
              markStateDirty()
              sendJson(res, 200, { ok: true, sessionId: value.sessionId })
            },
            function (err) {
              sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
            },
          )
        })
        return
      }
      if (action === 'archive' && req.method === 'POST') {
        doSessionArchive(sessionId).then(
          function (value) {
            markStateDirty()
            sendJson(res, 200, { ok: true, archived: value.archived })
          },
          function (err) {
            sendJson(res, 500, { ok: false, error: String((err && err.message) || err) })
          },
        )
        return
      }
      if (action === 'select' && req.method === 'POST') {
        activeSessionId = sessionId
        sendJson(res, 200, { ok: true, activeSessionId: sessionId })
        return
      }
      sendJson(res, 404, { ok: false, error: 'unknown session action: ' + action })
      return
    }

    sendJson(res, 404, { ok: false, error: 'unknown bridge route', path: path })
  }

  /* ------------------------------------------------------------------ *
   * 8. 挂载
   * ------------------------------------------------------------------ */

  const disposers = []
  disposers.push(webServer.register({ kind: 'prefix', path: BASE, handler: handle }))

  // 状态失效信号：任何会话事件都标记脏，由 1s 合并器统一推送
  if (typeof ctx.on === 'function') {
    const mark = function (session, event) {
      markStateDirty()
      sessionEventCount += 1
      safe(function () {
        trackEvent(session && session.id !== undefined ? String(session.id) : null, event)
      }, undefined)
      lastSessionEventDebug = {
        at: Date.now(),
        sessionKind: typeof session,
        hasId: !!(session && session.id !== undefined),
        sid: safe(function () { return String(session.id) }, null),
        eventType: safe(function () { return String(event && event.type) }, null),
        seq: safe(function () { return typeof event.seq === 'number' ? event.seq : null }, null),
      }
    }
    disposers.push(ctx.on('session/event', mark))
    // 用户提问：把 ask 包一层，只有手机正开着该会话时才接（见第 5 节）
    const restoreAsk = installQuestionHook()
    if (typeof restoreAsk === 'function') disposers.push(restoreAsk)
    disposers.push(ctx.on('api-session/added', mark))
    disposers.push(ctx.on('api-session/updated', mark))
  }

  safe(function () {
    Promise.resolve().then(backfillActivity)
  }, undefined)

  // 立刻开始镜像排队消息：等那 1 秒的 tick 也行，但手机一进来就该看到队列
  safe(startControlStream, undefined)

  console.log('[' + NAME + '] mounted on ' + BASE + ' (v' + VERSION + ', token=' + (TOKEN ? 'on' : 'off') + ')')

  return function dispose() {
    // 终端是这台电脑上的真进程：插件卸载/更新时必须全部杀掉，
    // 否则 reload 一次就在宿主里漏一堆 bash
    safe(termKillAll, undefined)
    const qids = Object.keys(queues)
    for (let i = 0; i < qids.length; i += 1) delete queues[qids[i]]
    const pids = Object.keys(progress)
    for (let i = 0; i < pids.length; i += 1) delete progress[pids[i]]
    const mks = Object.keys(modelWindows)
    for (let i = 0; i < mks.length; i += 1) delete modelWindows[mks[i]]
    const ids = Object.keys(stateClients)
    for (let i = 0; i < ids.length; i += 1) {
      try {
        stateClients[ids[i]].res.end()
      } catch (err) {
        /* 忽略 */
      }
      delete stateClients[ids[i]]
    }
    for (let i = 0; i < disposers.length; i += 1) {
      try {
        disposers[i]()
      } catch (err) {
        /* 忽略 */
      }
    }
  }
}
