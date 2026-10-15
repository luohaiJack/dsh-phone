/**
 * 离线验收：路由契约。
 *
 * 起因是一个真实事故：App 调 `POST /phone/model`，桥接只实现了 `POST /phone/models`，
 * README 和代码注释写的又都是单数 —— **两边各自看着都对**，
 * 结果"手机上切模型"永远是 404，直到有人在真机上点了一下才发现。
 *
 * 这类偏差用类型、编译、代码审查都拦不住，只有"真发一次请求"能拦。
 * 所以这里把文档里承诺的每个 (方法, 路径) 都真打一遍，
 * 断言它**不是**"路由不存在"。至于业务上返回 400/500 都无所谓 ——
 * 那说明路由到了，参数/环境的问题另有测试管。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []

const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'timer') return { interval: () => () => {}, timeout: () => () => {} }
    // 依赖一律不挂：这样每个路由都会走到自己的错误分支，
    // 但**路由本身**必须认得出来，不会掉进兜底的 404
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

let pass = 0, fail = 0
function check(name, ok, extra) {
  if (ok) { pass += 1; console.log('✓ ', name, extra ?? '') }
  else { fail += 1; console.log('✗ ', name, extra ?? '') }
}

/** 文档（README 接口表 / App 客户端 / 桥接注释）里承诺过的全部非 SSE 路由。 */
const documented = [
  ['GET', '/health'],
  ['GET', '/diag'],
  ['GET', '/state'],
  ['GET', '/models'],
  ['POST', '/model', { provider: 'x', model: 'y' }],   // ★ 事故现场：单数
  ['POST', '/models', { provider: 'x', model: 'y' }],  // 复数也保留
  ['GET', '/workspaces'],
  ['GET', '/fs/list'],
  ['POST', '/fs/mkdir', { parent: '/tmp', name: 'x' }],
  ['POST', '/new', {}],
  ['GET', '/file'],
  ['GET', '/sessions/s1/history'],
  ['POST', '/sessions/s1/prompt', { text: 'hi' }],
  ['POST', '/sessions/s1/cancel', {}],
  ['POST', '/sessions/s1/rename', { title: 'x' }],
  ['POST', '/sessions/s1/fork', {}],
  ['POST', '/sessions/s1/archive', {}],
  ['GET', '/sessions/s1/queue'],
  ['POST', '/sessions/s1/queue/m1', { action: 'remove' }],
  ['POST', '/sessions/s1/goal/clear', {}],
  ['POST', '/term/open', { cols: 80, rows: 24 }],
  ['GET', '/term/list'],
  ['POST', '/term/t1/input', { data: 'x' }],
  ['POST', '/term/t1/signal', { signal: 'SIGINT' }],
  ['POST', '/term/t1/close', {}],
]

for (const [method, path, body] of documented) {
  const res = await fetch(base + path, {
    method,
    headers: { 'x-phone-token': TOKEN, 'content-type': 'application/json' },
    ...(method === 'POST' ? { body: JSON.stringify(body ?? {}) } : {}),
  })
  const text = await res.text()
  const unknown = text.includes('unknown bridge route')
  check(`${method} ${path}`, !unknown, unknown ? '→ 路由不存在！' : `→ HTTP ${res.status}`)
}

/* 令牌仍然管用：没令牌一律 401（新增路由不能漏掉鉴权） */
{
  const res = await fetch(base + '/model', {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}',
  })
  check('无令牌 POST /model 被拒', res.status === 401, `status=${res.status}`)
}

/* 兜底还在：错路径必须报"路由不存在"，不能悄悄当成功 */
{
  const res = await fetch(base + '/nope', { headers: { 'x-phone-token': TOKEN } })
  const text = await res.text()
  check('不存在的路径仍然是 404', res.status === 404 && text.includes('unknown bridge route'), `status=${res.status}`)
}

console.log(`\n==== 路由契约验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
