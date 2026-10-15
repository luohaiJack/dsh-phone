/**
 * 离线验收：排队消息（改 / 撤 / 插话）与 control() 镜像。
 *
 * 宿主那边 control() 是一条长期流，离线没法用真的 —— 所以这里用桩：
 * 手推一帧 baseline、一帧增量，看桥接有没有镜像对、有没有推给手机、
 * 改/撤时发给 updateQueue 的形状对不对。
 *
 * 为什么要测形状：QueueAction 是宿主定的联合类型
 * （{kind:'remove'} / {kind:'edit',content} / {kind:'steer'}），
 * 拼错一个字段名不会报错，只会静默不生效 —— 那是最难查的一类 bug。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []

/* ---------------- 桩 sessionController ---------------- */
const updateCalls = []
const frameQueue = []
let wake = null

function nextFrame() {
  return new Promise((resolve) => {
    if (frameQueue.length) { resolve(frameQueue.shift()); return }
    wake = resolve
  })
}

/** 推一帧给桥接：有人在等就直接喂过去，否则入队。 */
function pushFrame(frame) {
  if (wake) {
    const w = wake
    wake = null
    w(frame)
    return
  }
  frameQueue.push(frame)
}

const sessionController = {
  // 永不结束的控制流：先给基线，之后按测试推进来推帧
  control() {
    return {
      [Symbol.asyncIterator]() {
        return {
          next: () => nextFrame().then((value) => (value === null ? { done: true } : { done: false, value })),
        }
      },
    }
  },
  // 会话实时流也用得到：给一个永不产出的迭代器，好让 SSE 客户端保持注册
  follow() {
    return {
      [Symbol.asyncIterator]() {
        return { next: () => new Promise(() => {}) }
      },
    }
  },
  list() {
    return Promise.resolve({ items: [] })
  },
  updateQueue(request) {
    updateCalls.push(request)
    return Promise.resolve({ accepted: true })
  },
}

const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'sessionController') return sessionController
    if (name === 'timer') {
      return {
        interval: (fn, ms) => { const h = setInterval(fn, ms); return () => clearInterval(h) },
        timeout: (fn, ms) => { const h = setTimeout(fn, ms); return () => clearTimeout(h) },
      }
    }
    return undefined
  },
  on() { return () => {} },
  effect() {},
}

createBridge(ctx, { token: TOKEN })

const server = http.createServer((req, res) => {
  const route = routes.find((r) => req.url.split('?')[0].startsWith(r.path))
  if (!route) { res.writeHead(404); res.end('no route'); return }
  route.handler(req, res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const base = `http://127.0.0.1:${server.address().port}/phone`
const H = { 'content-type': 'application/json', 'x-phone-token': TOKEN }

const get = async (p) => {
  const r = await fetch(base + p, { headers: { 'x-phone-token': TOKEN } })
  return { status: r.status, json: await r.json() }
}
const post = async (p, body) => {
  const r = await fetch(base + p, { method: 'POST', headers: H, body: JSON.stringify(body || {}) })
  return { status: r.status, json: await r.json() }
}

let pass = 0, fail = 0
function check(name, ok, extra) {
  if (ok) { pass += 1; console.log('✓ ', name, extra ?? '') }
  else { fail += 1; console.log('✗ ', name, extra ?? '') }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ---------------- 1. baseline 被镜像 ---------------- */
pushFrame({
  type: 'baseline',
  value: {
    queues: {
      'session-a': [
        { id: 'msg-1', placement: 'queued', message: { id: 'msg-1', content: [{ type: 'text', text: '第一句排队的' }] } },
      ],
      'session-b': [],
    },
    jobs: {},
    projections: {},
  },
})
await sleep(1300) // 等 1 秒那次 tick 把流拉起来

let q = await get('/sessions/session-a/queue')
check('baseline 里的排队项被镜像', q.json.items.length === 1 && q.json.items[0].text === '第一句排队的',
  JSON.stringify(q.json.items))

/* ---------------- 2. 增量帧替换那一条会话 ---------------- */
pushFrame({
  type: 'queue',
  sessionId: 'session-a',
  items: [
    { id: 'msg-1', placement: 'queued', message: { id: 'msg-1', content: [{ type: 'text', text: '第一句排队的' }] } },
    { id: 'msg-2', placement: 'queued', message: { id: 'msg-2', content: [{ type: 'text', text: '第二句' }, { type: 'text', text: '换行也算同一句' }] } },
  ],
})
await sleep(200)
q = await get('/sessions/session-a/queue')
check('增量帧后是两条', q.json.items.length === 2, `items=${q.json.items.length}`)
check('多段 text 会拼起来', q.json.items[1].text === '第二句\n换行也算同一句', JSON.stringify(q.json.items[1].text))

/* ---------------- 3. 改 / 撤 / 插话的形状 ---------------- */
updateCalls.length = 0
await post('/sessions/session-a/queue/msg-1', { action: 'remove' })
check('remove 发出去的是 {kind:remove}',
  updateCalls.length === 1 && updateCalls[0].action.kind === 'remove' &&
  updateCalls[0].sessionId === 'session-a' && updateCalls[0].itemId === 'msg-1',
  JSON.stringify(updateCalls[0]))

await post('/sessions/session-a/queue/msg-2', { action: 'edit', text: '改过的内容' })
const edit = updateCalls[updateCalls.length - 1]
check('edit 带 text-only content',
  edit.action.kind === 'edit' && edit.action.content[0].type === 'text' && edit.action.content[0].text === '改过的内容',
  JSON.stringify(edit.action))

const empty = await post('/sessions/session-a/queue/msg-2', { action: 'edit', text: '   ' })
check('edit 成空白被拒', empty.status === 400, `status=${empty.status}`)

await post('/sessions/session-a/queue/msg-2', { action: 'steer' })
check('steer 形状', updateCalls[updateCalls.length - 1].action.kind === 'steer')

const bogus = await post('/sessions/session-a/queue/msg-2', { action: 'nuke' })
check('未知动作被拒', bogus.status === 400, `status=${bogus.status}`)

/* ---------------- 4. 撤回之后本地镜像立刻少一条 ---------------- */
q = await get('/sessions/session-a/queue')
check('remove 之后镜像里少了一条', q.json.items.length === 1 && q.json.items[0].id === 'msg-2',
  JSON.stringify(q.json.items.map((i) => i.id)))

/* ---------------- 5. 排队变化会推给正在看这个会话的手机 ---------------- */
{
  const events = []
  const ac = new AbortController()
  const done = (async () => {
    const r = await fetch(`${base}/sessions/session-a/live`, {
      headers: { 'x-phone-token': TOKEN }, signal: ac.signal,
    })
    const reader = r.body.getReader()
    const dec = new TextDecoder()
    let buf = ''
    try {
      for (;;) {
        const { value, done: d } = await reader.read()
        if (d) break
        buf += dec.decode(value, { stream: true })
        let i
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const frame = buf.slice(0, i); buf = buf.slice(i + 2)
          const ev = /^event: (.*)$/m.exec(frame)
          const da = /^data: (.*)$/m.exec(frame)
          if (ev && da) events.push({ event: ev[1], data: JSON.parse(da[1]) })
        }
      }
    } catch { /* aborted */ }
  })()
  await sleep(600)
  const first = events.find((e) => e.event === 'queue')
  check('连上实时流就先收到一份队列', !!first && Array.isArray(first.data.items),
    first ? `items=${first.data.items.length}` : 'no queue event')

  // 再推一帧，看有没有转发
  const before = events.filter((e) => e.event === 'queue').length
  pushFrame({ type: 'queue', sessionId: 'session-a', items: [] })
  await sleep(400)
  const after = events.filter((e) => e.event === 'queue').length
  check('队列变化被转发到实时流', after > before, `queue 事件 ${before} → ${after}`)
  ac.abort()
  await done
}

/* ---------------- 6. diag 能看到流状态 ---------------- */
const diag = await get('/diag')
check('diag 里有队列镜像状态', diag.json.queue && diag.json.queue.controlRunning === true,
  JSON.stringify(diag.json.queue))

console.log(`\n==== 排队消息离线验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
