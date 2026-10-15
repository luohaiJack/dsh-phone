/**
 * 离线验收：**提问（ask_user_question）到手机的那条链路**。
 *
 * 事故现场（2026-10-15，用户报"手机上的新会话把选择让我在电脑上做"）：
 *   会话里 agent 调了 `ask_user_question`，桥接把它"判给"了手机
 *   （`isWatched` 为真），但问题被推进了一条**没有订阅者**的会话流
 *   —— 手机上一直什么都不显示，桥接还死等（最多 120 秒）才交还电脑。
 *   用户看到的就是"手机一直不回复，电脑上却弹出了选择"。
 *
 * 根因是"判定能不能送达"用的是**心跳**（`/phone/state?watching=`，15 秒 TTL），
 * 而**投递**只走会话流这一条会断的通道；两者不是一回事：
 * 心跳新鲜 ≠ 会话流真的连着。
 *
 * 现在的契约：
 *   1. 只要心跳说"我在看这个会话"，**状态轮询**里就必须带上这个问题
 *      （每 3~5 秒一次，是"一定会送到"的那条路）；
 *   2. 会话流连上时**补推**一次（覆盖断线重连、App 重建）；
 *   3. 手机不看这个会话了（心跳停了 3 秒）就立刻交还电脑 —— 不能让电脑干等；
 *   4. 手机一直没回答时，最多 120 秒也要交还；
 *   5. 反复 reload 不许把 `userQuestions.ask` 的钩子套成一层又一层。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []
const SID = 'session-under-test'

/* ------------------------------------------------------------------ *
 * 假宿主
 * ------------------------------------------------------------------ */

/** 被包裹后的 `userQuestions.ask`（桥接在 apply 时会替换掉原函数）。 */
const userQuestions = {
  ask: () => Promise.reject(new Error('原始 ask 不该被直接调用')),
}
/** 电脑端那条原链路被调用的次数 —— "交还给电脑"就看它。 */
let pcAsked = 0
let pcAnswer = null
const originalAsk = () => {
  pcAsked += 1
  return Promise.resolve(pcAnswer)
}
userQuestions.ask = originalAsk

const sessionController = {
  list: () => Promise.resolve({
    items: [{
      sessionId: SID,
      updatedAt: Date.now(),
      running: true,
      blank: false,
      cwd: '/tmp',
      projections: { values: { modelSelection: { lastUsed: { provider: 'p', model: 'm' } } } },
    }],
  }),
  // 会话流用：只推一个 snapshot 就挂着，够注册成"有订阅者"即可
  follow: () => ({
    [Symbol.asyncIterator]() {
      let sent = false
      return {
        next: () => {
          if (sent) return new Promise(() => {})
          sent = true
          return Promise.resolve({
            done: false,
            value: { type: 'snapshot', cursor: 1, hasMore: false, header: { id: SID, cwd: '/tmp' }, records: [] },
          })
        },
      }
    },
  }),
}

const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'sessionController') return sessionController
    if (name === 'userQuestions') return userQuestions
    // 真实定时器：超时交还那条逻辑要靠它跑
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
    return undefined
  },
  on() { return () => {} },
  effect() {},
}

// 心跳 TTL 调到 400ms：这样"心跳过期后交还电脑"这条规则几秒内就能验完，
// 而不用真等 15 秒（TTL 本身是桥接的可注入参数，见 WATCH_TTL_MS）
const WATCH_TTL_MS = 400
createBridge(ctx, { token: TOKEN, watchTtlMs: WATCH_TTL_MS })

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
const getState = async (watching) => {
  const q = watching ? `?watching=${encodeURIComponent(watching)}` : ''
  return (await fetch(`${base}/state${q}`, { headers: H })).json()
}
const post = async (path, body) => {
  const res = await fetch(base + path, { method: 'POST', headers: H, body: JSON.stringify(body ?? {}) })
  return { status: res.status, json: await res.json() }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const QUESTIONS = [{
  id: 'direction_name',
  header: '方向定名',
  question: '方向 id 与显示名怎么定？',
  options: [
    { label: 'id=kfmem，名：卡尔曼滤波×RL 的 agent 记忆（Recommended）', description: '工单前缀 NK-KF' },
    { label: 'id=kfrl', description: '更短但偏泛' },
  ],
}]

const askApi = (agentId) => userQuestions.ask({
  agent: { id: agentId },
  questions: QUESTIONS,
  signal: { addEventListener: () => {} },
})

/* ------------------------------------------------------------------ *
 * 1. 没人在看 → 立刻走电脑那条原链路，不扣着问题
 * ------------------------------------------------------------------ */
{
  const p = askApi(SID)
  const settled = await Promise.race([p.then(() => 'resolved'), sleep(200).then(() => 'pending')])
  check('没人看这个会话 → 立刻交还电脑（不扣住）', settled === 'resolved' && pcAsked === 1, `pcAsked=${pcAsked}`)
}

/* ------------------------------------------------------------------ *
 * 2. ★ 心跳新鲜但**会话流没人连着** —— 这就是出事的那种组合
 * ------------------------------------------------------------------ */
{
  const st = await getState(SID)          // 顺手把"我在看"的心跳打上去
  check('状态里没有问题时是 null（不是空对象）', st.question === null, JSON.stringify(st.question))

  const p = askApi(SID)
  const settled = await Promise.race([p.then(() => 'resolved'), sleep(200).then(() => 'pending')])
  check('有人看 → 扣住问题等手机回答', settled === 'pending')
  check('扣住时没有走电脑那条链路', pcAsked === 1, `pcAsked=${pcAsked}`)

  // ★ 核心断言：快通道（会话流）没连上，但**稳定通道**里必须有
  const st2 = await getState(SID)
  check('★ 状态轮询里带着这个问题（快通道断了也不会丢）', !!st2.question, JSON.stringify(st2.question))
  check('问题的形状对得上 App 的解析', st2.question?.id === 'q1' && st2.question?.sessionId === SID, JSON.stringify(st2.question))
  check('题目内容原样带过去', st2.question?.questions?.[0]?.question === QUESTIONS[0].question)
  check('选项与描述都在', st2.question?.questions?.[0]?.options?.length === 2 &&
    st2.question.questions[0].options[0].description === '工单前缀 NK-KF')
  check('diag 里能看到"当时会话流有几个人连着"', await (async () => {
    const d = await (await fetch(`${base}/diag`, { headers: H })).json()
    return d.lastQuestion?.streamClients === 0 && d.lastQuestion?.watched === true
  })())

  // 别的会话来问问题：不该出现在这条会话的状态里
  const other = await getState('session-some-other')
  check('问的是别的会话时，不会串到这条会话上', other.question === null, JSON.stringify(other.question))

  /* 手机从 /phone/answer 回答 → 问题结算，状态里随之消失 */
  const ans = await post('/answer', {
    id: 'q1',
    answers: [{ id: 'direction_name', selected: [QUESTIONS[0].options[0].label] }],
  })
  check('手机回答被受理', ans.json.ok === true, JSON.stringify(ans.json))
  const answer = await p
  check('原链路拿到的是手机给的答案', answer?.answers?.[0]?.selected?.[0] === QUESTIONS[0].options[0].label,
    JSON.stringify(answer))
  check('回答后不再走电脑那条链路', pcAsked === 1, `pcAsked=${pcAsked}`)
  const st3 = await getState(SID)
  check('结算后状态里不再带这个问题', st3.question === null, JSON.stringify(st3.question))
}

/* ------------------------------------------------------------------ *
 * 3. 会话流连上时补推（覆盖断线重连 / App 重建）
 * ------------------------------------------------------------------ */
{
  await getState(SID)
  const p = askApi(SID)
  await sleep(100)

  // 手机（重）连会话流 —— 桥接应当马上把挂着的问题补推给它
  const frames = []
  const ac = new AbortController()
  const stream = fetch(`${base}/sessions/${SID}/live`, { headers: H, signal: ac.signal })
  stream.then(async (res) => {
    const reader = res.body.getReader()
    const dec = new TextDecoder()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      frames.push(dec.decode(value, { stream: true }))
    }
  }).catch(() => {})
  await sleep(400)
  ac.abort()

  const text = frames.join('')
  check('★ 会话流一开就补推了待答问题', /event: question/.test(text), text.slice(0, 120).replace(/\n/g, '\\n'))
  check('补推的内容里有题面', text.includes('方向 id 与显示名怎么定'))

  await post('/answer', { id: 'q2', answers: [{ id: 'direction_name', selected: ['id=kfrl'] }] })
  await p
}

/* ------------------------------------------------------------------ *
 * 4. 手机不看了 → 3 秒后交还电脑（不能让电脑干等）
 * ------------------------------------------------------------------ */
{
  await getState(SID)                      // 心跳：我在看
  const p = askApi(SID)
  let outcome = null
  p.then((v) => { outcome = v })

  const early = await Promise.race([p.then(() => 'resolved'), sleep(1).then(() => 'pending')])
  check('还在看的时候不交还', early === 'pending', String(outcome))

  // 之后不再打心跳：TTL(400ms) 过期 + 3 秒宽限 → 应当结算为 expired 并交还电脑
  pcAnswer = { answers: [{ id: 'direction_name', selected: ['电脑上选的'] }] }
  const late = await Promise.race([p.then(() => 'resolved'), sleep(WATCH_TTL_MS + 4500).then(() => 'pending')])
  check('★ 心跳过期后交还电脑（不干等到底）', late === 'resolved', late)
  check('交还后电脑那条链路真的被调用', pcAsked === 2, `pcAsked=${pcAsked}`)
  check('电脑拿到的答案透传回来', outcome?.answers?.[0]?.selected?.[0] === '电脑上选的', JSON.stringify(outcome))
  check('diag 记下了结算原因（expired 一眼看出是手机没答）', await (async () => {
    const d = await (await fetch(`${base}/diag`, { headers: H })).json()
    return d.lastQuestion?.settledReason === 'expired' && d.lastQuestion.settledAgeMs >= WATCH_TTL_MS
  })())
  const st = await getState(SID)
  check('交还后状态里也不留这个问题', st.question === null, JSON.stringify(st.question))
}

/* ------------------------------------------------------------------ *
 * 5. 反复 reload 不许把钩子套成"层层 120 秒"
 * ------------------------------------------------------------------ */
{
  // 真再挂一个实例（这就是一次 reload：同一个 userQuestions 服务上再装一次钩子）
  const routes2 = []
  const ctx2 = {
    get(name) {
      if (name === 'webServer') return { register: (r) => { routes2.push(r); return () => {} } }
      if (name === 'sessionController') return sessionController
      if (name === 'userQuestions') return userQuestions
      if (name === 'timer') return { interval: () => () => {}, timeout: () => () => {} }
      return undefined
    },
    on() { return () => {} },
    effect() {},
  }
  createBridge(ctx2, { token: TOKEN, watchTtlMs: WATCH_TTL_MS })

  let nested = 0
  let cur = userQuestions.ask
  while (typeof cur === 'function' && cur.__dshPhoneOriginal) {
    nested += 1
    cur = cur.__dshPhoneOriginal
  }
  // 注意：这条只对新代码成立（旧代码没有那个标记，链条根本走不通），
  // 所以它是"结构不变量"，不是"旧代码会红"的反向用例。
  check('★ 再挂一次（reload）之后钩子仍只有一层', nested === 1, `nested=${nested}`)
  check('剥到的是真正的原始函数', cur === originalAsk, cur === originalAsk ? 'yes' : 'no')
}

console.log(`\n==== 提问投递验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
