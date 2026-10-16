/**
 * 离线验收：**新建会话的定位契约**（workspaceId 与 cwd 二选一）。
 *
 * 事故现场（2026-10-15，用户报"手机新建工作区失败了"）：
 *   手机上"新建工作区 → 开会话"每次都失败，报
 *   `session.create accepts workspaceId or cwd, not both`；
 *   工作区倒是建出来了（所以列表里看得见），会话一个都没有。
 *
 * 根因是桥接**两个字段一起发**：`doCreate` 先带 cwd，再从 cwd 反查到
 * workspaceId **加**上去 —— 两个都在，宿主直接 400。
 * 而宿主的契约是（`dsh-api-session-controller` 的 `create`）：
 *
 *     if (workspaceId !== undefined && cwd !== undefined) throw ...
 *     const cwd = workspace?.path ?? request.cwd ?? defaultCwd
 *     if (workspace) await workspace.attachSession(sessionId)
 *
 * 也就是说：**给了 workspaceId 反而更好** —— 宿主自己拿 workspace.path 当 cwd，
 * 还会把会话 attach 进工作区（那才是它出现在工作区树里的原因）。
 * "两个都带"不只是报错，连 attach 都丢了。
 *
 * 这个用例的假宿主**逐字实现上面那段契约**，所以桥接只要再犯同样的错，
 * 这里就红 —— 而不是等用户再点一次。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []

/* ------------------------------------------------------------------ *
 * 假宿主：会话控制器 + 工作区注册表
 * ------------------------------------------------------------------ */

/** 电脑上"已经存在"的目录（realpath 只能解析出这些）。 */
const existingDirs = new Set(['/home/u', '/home/u/Documents', '/home/u/Documents/新项目'])

const sessions = []          // {id, cwd, workspaceId}
const workspaces = []        // {id, path, title, sessionIds}
/** 假宿主收到的越界请求（同时带 workspaceId 与 cwd）—— 必须始终为 0。 */
const violations = []
let seq = 0

const cloneWorkspace = (w) => ({
  id: w.id,
  path: w.path,
  title: w.title,
  sessionIds: w.sessionIds.slice(),
})

const sessionController = {
  // 必须把已建的会话列出来：桥接要**按会话逐个**问后台任务
  // （宿主 list(caller) 空参只返回无主任务 —— 见 projectJobs 的注释）
  list: () =>
    Promise.resolve({
      items: sessions.map((x) => ({
        sessionId: x.id,
        updatedAt: Date.now(),
        running: false,
        blank: false,
        cwd: x.cwd,
        projections: { values: { modelSelection: { lastUsed: { provider: 'p', model: 'm' } } } },
      })),
    }),
  /**
   * 逐字照抄宿主的契约（见文件头引的那几行）。
   * 顺序很重要：先查"两个都给"，再解析 cwd，再 attach。
   */
  create(request) {
    if (request.workspaceId !== undefined && request.cwd !== undefined) {
      violations.push({ ...request })
      return Promise.reject(
        new Error('session.create accepts workspaceId or cwd, not both'),
      )
    }
    const workspace = request.workspaceId === undefined
      ? undefined
      : workspaces.find((w) => w.id === request.workspaceId)
    if (request.workspaceId !== undefined && workspace === undefined) {
      return Promise.reject(new Error(`workspace "${request.workspaceId}" not found`))
    }
    const cwd = workspace ? workspace.path : (request.cwd ?? '/home/u')
    seq += 1
    const sessionId = seq === 1 ? SID_FOR_JOBS : `session-fake-${seq}`
    const rec = { id: sessionId, cwd, workspaceId: workspace ? workspace.id : null }
    sessions.push(rec)
    if (workspace) workspace.sessionIds.push(sessionId)
    return Promise.resolve({ sessionId })
  },
}

/**
 * 假 jobs 服务 —— **逐字照抄宿主 `dsh-jobs-local` 的 `list(caller)` 语义**：
 *
 *     [...store.values()].filter((job) => job.owner === void 0 || job.owner.id === caller)
 *
 * 桥接原来空参调用（只拿到无主任务），而后台任务基本都是有主的 ——
 * 于是 `state.jobs` 永远是空数组，手机上"看不到有后台任务的会话"。
 * owner 还是个**对象**（`job.owner.id`），桥接原来读的 `j.ownerSession` 也不存在。
 */
const SID_FOR_JOBS = 'session-under-test'
const jobStore = [
  { id: 'job-owned', kind: 'bash', label: 'npm run dev', status: 'running', owner: { id: SID_FOR_JOBS }, startedAt: 100, finishedAt: null, detail: null },
  { id: 'job-unowned', kind: 'bash', label: '无主任务', status: 'running', owner: undefined, startedAt: 200, finishedAt: null, detail: null },
  { id: 'job-other', kind: 'bash', label: '别人会话的任务', status: 'running', owner: { id: 'session-other' }, startedAt: 300, finishedAt: null, detail: null },
]
const jobsSvc = {
  // list(caller) 的过滤语义 + **view 的投影语义**（owner 是字符串，无主则不带该字段）
  list: (caller) =>
    jobStore
      .filter((j) => j.owner === undefined || j.owner.id === caller)
      .map((j) => {
        const v = { id: j.id, kind: j.kind, label: j.label, status: j.status, startedAt: j.startedAt, finishedAt: j.finishedAt, detail: j.detail }
        if (j.owner !== undefined) v.owner = j.owner.id
        return v
      }),
}

const workspaceRegistry = {
  /**
   * 归档集（宿主是 registry 上的一个 getter）。
   *
   * 宿主自己的语义：`archiveSession` 只往这个集合里加 id，
   * **工作区的 sessionIds 不动**（"Archiving never touches workspace accounting"），
   * 隐藏是消费方的事 —— 桥接原来没做，于是手机上"归档了还在列表里"。
   */
  archivedSessionIds: [],
  list: () => workspaces.map(cloneWorkspace),
  get: (id) => {
    const w = workspaces.find((x) => x.id === id)
    return w ? cloneWorkspace(w) : undefined
  },
  /** 宿主的 create 会 realpath，目录不存在就 reject。 */
  create(path, title) {
    if (!existingDirs.has(path)) {
      return Promise.reject(new Error(`cannot create a workspace at '${path}': no such directory`))
    }
    const found = workspaces.find((w) => w.path === path)
    if (found) return Promise.resolve(cloneWorkspace(found))
    const w = { id: `ws-${workspaces.length + 1}`, path, title: title || path.split('/').pop(), sessionIds: [] }
    workspaces.unshift(w)
    return Promise.resolve(cloneWorkspace(w))
  },
  resolveByPath: (path) => {
    const w = workspaces.find((x) => x.path === path)
    return Promise.resolve(w ? cloneWorkspace(w) : undefined)
  },
}

/** `fs` + `shell`：够 `/phone/fs/mkdir` 用（这条路径本身就是"新建文件夹"）。 */
const createdDirs = []
const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'sessionController') return sessionController
    if (name === 'workspaceRegistry') return workspaceRegistry
    if (name === 'jobs') return jobsSvc
    // 真定时器：心跳那条规则要靠它跑（空实现会让心跳永远不触发）
    if (name === 'timer') {
      return {
        interval: (fn, ms) => {
          const h = setInterval(fn, ms)
          return () => clearInterval(h)
        },
        timeout: (fn, ms) => {
          const h = setTimeout(fn, ms)
          return () => clearTimeout(h)
        },
      }
    }
    if (name === 'directoryPickerController') {
      return {
        // 手机上的"在此新建文件夹"走这里
        createDirectory: (parent, n) => {
          const p = `${parent}/${n}`
          existingDirs.add(p)
          createdDirs.push(p)
          return Promise.resolve(p)
        },
      }
    }
    if (name === 'fs') {
      return {
        resolve: (p) => Promise.resolve({ displayPath: p }),
        listDir: () => Promise.resolve([]),
      }
    }
    return undefined
  },
  on() { return () => {} },
  effect() {},
}

// 心跳间隔压到 150ms，好在 1 秒内验完"注释行确实发出去了"
const HEARTBEAT_MS = 150
createBridge(ctx, { token: TOKEN, heartbeatMs: HEARTBEAT_MS })

const server = http.createServer((req, res) => {
  const route = routes.find((r) => req.url.split('?')[0].startsWith(r.path))
  if (!route) { res.writeHead(404); res.end('no route'); return }
  route.handler(req, res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const base = `http://127.0.0.1:${server.address().port}/phone`
const H = { 'x-phone-token': TOKEN, 'content-type': 'application/json' }

let pass = 0, fail = 0
function check(name, ok, extra) {
  if (ok) { pass += 1; console.log('✓ ', name, extra ?? '') }
  else { fail += 1; console.log('✗ ', name, extra ?? '') }
}
const post = async (path, body) => {
  const res = await fetch(base + path, { method: 'POST', headers: H, body: JSON.stringify(body ?? {}) })
  return { status: res.status, json: await res.json() }
}

/* ------------------------------------------------------------------ *
 * 1. 手机上那条真实路径：新建文件夹 → 建工作区 → 开会话
 * ------------------------------------------------------------------ */
const mk = await post('/fs/mkdir', { parent: '/home/u/Documents', name: '新项目' })
check('新建文件夹成功', mk.json.ok === true, JSON.stringify(mk.json))

const ws = await post('/workspaces/create', { path: '/home/u/Documents/新项目', title: '新项目' })
check('建工作区成功', ws.json.ok === true, JSON.stringify(ws.json))

// ★ 这一步就是用户点的那一下：App 会同时带 cwd 和 workspaceId
const made = await post('/new', { cwd: '/home/u/Documents/新项目', workspaceId: ws.json.id })
check('★ 建会话成功（不再 400 "not both"）', made.json.ok === true, JSON.stringify(made.json))
check('★ 宿主没有收到"两个定位都给"的请求', violations.length === 0, JSON.stringify(violations))
check(
  '★ 会话被 attach 进工作区（否则工作区树里看不到它）',
  sessions.length === 1 && workspaceRegistry.get(ws.json.id).sessionIds.length === 1,
  JSON.stringify(workspaceRegistry.get(ws.json.id).sessionIds),
)
check('会话的 cwd 取自工作区路径', sessions[0] && sessions[0].cwd === '/home/u/Documents/新项目', sessions[0]?.cwd)

/* ------------------------------------------------------------------ *
 * 2. 只给 cwd，但那个目录已经是工作区 → 桥接应换成 workspaceId（仍然只给一个）
 * ------------------------------------------------------------------ */
const made2 = await post('/new', { cwd: '/home/u/Documents/新项目' })
check('只给 cwd 也能建会话', made2.json.ok === true, JSON.stringify(made2.json))
check('已登记的目录：会话同样 attach 进工作区', workspaceRegistry.get(ws.json.id).sessionIds.length === 2,
  JSON.stringify(workspaceRegistry.get(ws.json.id).sessionIds))
check('两次都没有越界', violations.length === 0, JSON.stringify(violations))

/* ------------------------------------------------------------------ *
 * 3. 没登记成工作区的目录：老老实实只带 cwd（不能瞎编 workspaceId）
 * ------------------------------------------------------------------ */
const made3 = await post('/new', { cwd: '/home/u' })
check('未登记的目录：只带 cwd 建会话', made3.json.ok === true, JSON.stringify(made3.json))
check('未登记的目录：cwd 原样传下去', sessions[2] && sessions[2].cwd === '/home/u', sessions[2]?.cwd)
check('未登记的目录：没有 attach（workspaceId 为空）', sessions[2] && sessions[2].workspaceId === null)

/* ------------------------------------------------------------------ *
 * 4. 只给 workspaceId / 什么都不给
 * ------------------------------------------------------------------ */
const made4 = await post('/new', { workspaceId: ws.json.id })
check('只给 workspaceId 照常可用', made4.json.ok === true, JSON.stringify(made4.json))

const made5 = await post('/new', {})
check('什么都不给 → 用宿主的默认目录', made5.json.ok === true, JSON.stringify(made5.json))
check('默认目录没被写成空串', sessions[4] && sessions[4].cwd === '/home/u', sessions[4]?.cwd)

/* ------------------------------------------------------------------ *
 * 5. 不存在的 workspaceId 要如实报错，不能悄悄降级
 * ------------------------------------------------------------------ */
const bad = await post('/new', { workspaceId: 'ws-nope' })
check('不存在的 workspaceId → 报错而不是静默成功', bad.json.ok === false && /not found/.test(bad.json.error || ''),
  JSON.stringify(bad.json))

/* ------------------------------------------------------------------ *
 * 6. 归档的会话必须从工作区树里消失（2026-10-16 用户报的）
 * ------------------------------------------------------------------ */
{
  const before = await fetch(`${base}/workspaces`, { headers: H }).then((r) => r.json())
  const w0 = (before.workspaces || []).find((w) => w.id === ws.json.id)
  const sid = sessions[0].id
  check('归档前：会话在工作区树里', !!w0 && w0.sessions.some((s) => s.id === sid),
    JSON.stringify((w0?.sessions || []).map((s) => s.id)))

  // 宿主归档：只往归档集加 id，工作区记账**不动**
  workspaceRegistry.archivedSessionIds.push(sid)
  const after = await fetch(`${base}/workspaces`, { headers: H }).then((r) => r.json())
  const w1 = (after.workspaces || []).find((w) => w.id === ws.json.id)
  check('★ 归档后：会话从工作区树里消失', !!w1 && !w1.sessions.some((s) => s.id === sid),
    JSON.stringify((w1?.sessions || []).map((s) => s.id)))
  check('★ 但工作区本身的记账没被改（宿主语义：取消归档要能归位）',
    workspaceRegistry.get(ws.json.id).sessionIds.includes(sid))
  const diag = await (await fetch(`${base}/diag`, { headers: H })).json()
  check('diag 里能看到归档集大小', diag.archived === 1, String(diag.archived))

  workspaceRegistry.archivedSessionIds.length = 0
  const back = await fetch(`${base}/workspaces`, { headers: H }).then((r) => r.json())
  const w2 = (back.workspaces || []).find((w) => w.id === ws.json.id)
  check('取消归档后会话回来（位置没丢）', !!w2 && w2.sessions.some((s) => s.id === sid),
    JSON.stringify((w2?.sessions || []).map((s) => s.id)))
}

check('全程零越界请求', violations.length === 0, JSON.stringify(violations))

/* ------------------------------------------------------------------ *
 * 6b. 后台任务必须报出来（手机上"看不到有后台任务的会话"就是这个）
 * ------------------------------------------------------------------ */
{
  const d = await (await fetch(`${base}/state`, { headers: H })).json()
  const jobs = d.jobs || []
  const ids = jobs.map((j) => j.id)
  check('★ 有主任务被报出来（不能因为空参调用而永远为空）', ids.includes('job-owned'), JSON.stringify(ids))
  check('★ 无主任务也报（owner=null）', ids.includes('job-unowned') &&
    (jobs.find((j) => j.id === 'job-unowned') || {}).owner === null, JSON.stringify(jobs.find((j) => j.id === 'job-unowned')))
  check('owner 填的是会话 id（宿主那边 owner 是对象，不是 ownerSession）',
    (jobs.find((j) => j.id === 'job-owned') || {}).owner === SID_FOR_JOBS,
    JSON.stringify(jobs.find((j) => j.id === 'job-owned')))
  check('没列出来的会话的任务不会混进来', !ids.includes('job-other'), JSON.stringify(ids))

  // ★ list(caller) 会把无主任务返回给**每一个** caller —— 不能因此重复报
  const unownedCount = ids.filter((x) => x === 'job-unowned').length
  check('★ 无主任务只报一次（去重）', unownedCount === 1, '出现 ' + unownedCount + ' 次')
  check('全部任务里没有重复 id', ids.length === new Set(ids).size, JSON.stringify(ids))

  const sess = (d.sessions || []).find((x) => x.id === SID_FOR_JOBS)
  check('★ 会话自己的 jobs 里有它', !!sess && (sess.jobs || []).some((j) => j.id === 'job-owned'),
    JSON.stringify((sess && sess.jobs) || []))
  check('会话的 jobs 不夹带别人的', !!sess && !(sess.jobs || []).some((j) => j.id === 'job-other'))
}

/* ------------------------------------------------------------------ *
 * 7. SSE 心跳：没有它，半死的长连接会永远等下去（App 永远不会重连）
 * ------------------------------------------------------------------ */
{
  const ac = new AbortController()
  const frames = []
  const stream = fetch(`${base}/events`, { headers: { 'x-phone-token': TOKEN }, signal: ac.signal })
  stream.then(async (res) => {
    const reader = res.body.getReader()
    const dec = new TextDecoder()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      frames.push(dec.decode(value, { stream: true }))
    }
  }).catch(() => {})
  await new Promise((r) => setTimeout(r, HEARTBEAT_MS * 4 + 400))
  ac.abort()
  const text = frames.join('')
  check('★ SSE 会定期发心跳注释行（客户端才能用有界读超时）', /: ping \d+/.test(text),
    text.slice(0, 90).replace(/\n/g, '\\n'))
  check('心跳是注释行（合规解析器会忽略，不会当成事件）', !/event: ping/.test(text))
}

console.log(`\n==== 新建会话定位契约验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
