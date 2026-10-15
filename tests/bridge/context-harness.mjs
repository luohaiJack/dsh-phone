/**
 * 离线验收：上下文窗口**从日志里学**，而不是靠"恰好收到那条实时事件"。
 *
 * 起因：手机上偶尔显示"窗口未知"，用户问"窗口大小怎么可能未知？同步 harness 上
 * 显示的不就行了"。他说得对 —— harness 自己的界面就是从会话日志里翻最后一条
 * `request/context`。所以桥接也要这么做：读历史时顺手扫一遍，
 * 并按**模型**缓存住（窗口是模型的属性，不是会话的）。
 *
 * 这个用例就是钉住这条：
 *   1. 会话自己的事件到不了根监听器（模拟桥接 create 出来的会话）；
 *   2. 只读一次历史（里面带 request/context），窗口就该学到；
 *   3. 另一个同型号的会话，状态里也必须直接有窗口。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []

/** 一条 request/context 记录（历史里就是长这样）。 */
const contextRecord = (window, provider, model, seq) => ({
  type: 'event',
  event: {
    type: 'request/context',
    seq,
    time: 1,
    data: {
      provider,
      model,
      ...(window === undefined ? {} : { contextWindow: window }),
    },
  },
})

const modelSel = (provider, model) => ({
  values: { modelSelection: { lastUsed: { provider, model } } },
})
const summaries = {
  's1': { sessionId: 's1', updatedAt: Date.now(), running: false, blank: false, projections: modelSel('deepseek-official', 'deepseek-flash') },
  // 同型号的另一条会话：它自己没被读过日志，应该靠"按模型缓存"直接拿到窗口
  's2': { sessionId: 's2', updatedAt: Date.now(), running: false, blank: false, projections: modelSel('deepseek-official', 'deepseek-flash') },
  // 谁都没见过的型号：**不许**拿别的模型的窗口顶上 —— 不知道就说不知道
  's3': { sessionId: 's3', updatedAt: Date.now(), running: false, blank: false, projections: modelSel('some-vendor', 'brand-new-model') },
}

const sessionController = {
  list: () => Promise.resolve({ items: Object.values(summaries) }),
  // 每个会话的日志内容按会话给（真实情况就是各是各的）：
  // s1/s2 的日志里带着 deepseek-flash 的 request/context；s3 用的是没见过的型号，
  // 它那条 request/context **没有** contextWindow（适配器不认识这个模型）。
  follow: (request) => {
    const sid = request?.address?.sessionId || 's1'
    const records = sid === 's3'
      ? [contextRecord(undefined, 'some-vendor', 'brand-new-model', 5)]
      : [contextRecord(1000000, 'deepseek-official', 'deepseek-flash', 5)]
    return {
      [Symbol.asyncIterator]() {
        let sent = false
        return {
          next: () => {
            if (sent) return new Promise(() => {})
            sent = true
            return Promise.resolve({
              done: false,
              value: {
                type: 'snapshot',
                cursor: 10,
                hasMore: false,
                header: { id: sid, cwd: '/tmp' },
                records,
              },
            })
          },
        }
      },
    }
  },
  page: () => Promise.resolve({ records: [], hasMore: false }),
}

const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'sessionController') return sessionController
    if (name === 'timer') return { interval: () => () => {}, timeout: () => () => {} }
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
const H = { 'x-phone-token': TOKEN }

let pass = 0, fail = 0
function check(name, ok, extra) {
  if (ok) { pass += 1; console.log('✓ ', name, extra ?? '') }
  else { fail += 1; console.log('✗ ', name, extra ?? '') }
}
const stateProgress = async (sid) => {
  const d = await (await fetch(`${base}/state?max=80`, { headers: H })).json()
  return (d.sessions || []).find((s) => s.id === sid)?.progress
}

// 1. 挂载后**什么都不用做**：回填读日志时就顺手把窗口学了（用户要的就是这个）
await new Promise((r) => setTimeout(r, 300))
const after = await stateProgress('s1')
check('挂载后窗口就已经学到了（不靠实时事件）', after && after.contextWindow === 1000000,
  JSON.stringify(after?.contextWindow))

// 1b. 显式读一次历史也不该把它弄丢
await fetch(`${base}/sessions/s1/history?max=40`, { headers: H })
await new Promise((r) => setTimeout(r, 200))
const afterRead = await stateProgress('s1')
check('读历史后窗口仍在', afterRead && afterRead.contextWindow === 1000000, JSON.stringify(afterRead?.contextWindow))

// 3. 同型号的另一个会话：不需要自己读历史，也该直接有窗口（按模型缓存）
const s2 = await stateProgress('s2')
check('同型号的另一会话直接有窗口', s2 && s2.contextWindow === 1000000, JSON.stringify(s2?.contextWindow))

// 3b. 没见过的型号：老老实实显示未知，不拿别的模型顶上
const s3 = await stateProgress('s3')
check('没见过的型号不瞎填', s3 && s3.contextWindow === 0, JSON.stringify(s3?.contextWindow))

// 4. 窗口是模型的属性：diag 里能看到学到了哪些
const diag = await (await fetch(`${base}/diag`, { headers: H })).json()
check('diag 里能看到模型窗口表',
  diag.progress?.modelWindows?.['deepseek-official/deepseek-flash'] === 1000000,
  JSON.stringify(diag.progress?.modelWindows))

console.log(`\n==== 上下文窗口学习验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
