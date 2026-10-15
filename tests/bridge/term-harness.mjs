/**
 * 离线验收：把 bridge-core 挂在一个假 ctx 上，用真 `script`(PTY) 当 subprocess 后端，
 * 走真实 HTTP 打一遍终端接口。不需要 harness、不需要手机。
 */
import http from 'node:http'
import { spawn, execFileSync } from 'node:child_process'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const routes = []

/** 真 PTY：script(1) 分配一个 pty 再跑 bash，stdin/stdout 通过管道转接。 */
function spawnTerminal(spec) {
  const cmd = spec.argv.map((a) => `'${a.replace(/'/g, `'\\''`)}'`).join(' ')
  const child = spawn('script', ['-qfc', cmd, '/dev/null'], {
    cwd: spec.cwd,
    env: { ...process.env, TERM: 'xterm-256color' },
    stdio: ['pipe', 'pipe', 'pipe'],
  })
  return Promise.resolve({
    pid: child.pid,
    output: child.stdout,
    done: new Promise((resolve) => {
      child.on('exit', (code, signal) => resolve({ exitCode: code, signal }))
    }),
    write: (data) => new Promise((resolve, reject) => {
      child.stdin.write(data, (err) => (err ? reject(err) : resolve()))
    }),
    signalForeground: (sig) => {
      // 真终端里 Ctrl-C 是"往 PTY 写 \x03"，由行规程把它变成前台进程组的 SIGINT。
      // script(1) 会把 stdin 原样转进 PTY，所以这样模拟才是对语义的。
      const ctrl = { SIGINT: '\u0003', SIGTSTP: '\u001a', SIGTERM: '\u0003' }[sig] || '\u0003'
      child.stdin.write(ctrl)
      return Promise.resolve(child.pid)
    },
    terminate: () => {
      try { child.kill('SIGKILL') } catch { /* 已退出 */ }
    },
  })
}

const stops = []
const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'subprocess') return { spawnTerminal }
    if (name === 'timer') return { interval: (fn, ms) => { const h = setInterval(fn, ms); return () => clearInterval(h) } }
    return undefined
  },
  on() { return () => {} },
  effect() {},
}

const dispose = createBridge(ctx, { token: TOKEN })
stops.push(dispose)

const server = http.createServer((req, res) => {
  const route = routes.find((r) => req.url.split('?')[0].startsWith(r.path))
  if (!route) { res.writeHead(404); res.end('no route'); return }
  route.handler(req, res)
})
await new Promise((r) => server.listen(0, '127.0.0.1', r))
const base = `http://127.0.0.1:${server.address().port}/phone`

const H = { 'content-type': 'application/json', 'x-phone-token': TOKEN }
const post = async (p, body) => {
  const r = await fetch(base + p, { method: 'POST', headers: H, body: JSON.stringify(body || {}) })
  return { status: r.status, json: await r.json() }
}
const get = async (p) => {
  const r = await fetch(base + p, { headers: { 'x-phone-token': TOKEN } })
  return { status: r.status, json: await r.json() }
}

let pass = 0, fail = 0
function check(name, ok, extra) {
  if (ok) { pass += 1; console.log('✓ ', name, extra === undefined ? '' : extra) }
  else { fail += 1; console.log('✗ ', name, extra === undefined ? '' : extra) }
}

/* ---------- 0. 鉴权 ---------- */
{
  const r = await fetch(base + '/term/list')
  check('无令牌被拒（401）', r.status === 401, `status=${r.status}`)
}

/* ---------- 1. 开终端 ---------- */
const opened = await post('/term/open', { cols: 100, rows: 30, cwd: '/tmp' })
check('open 返回 id', opened.status === 200 && opened.json.ok === true, JSON.stringify(opened.json))
const id = opened.json.id

/* ---------- 2. SSE 收到 hello（含缓冲字段） ---------- */
function openStream() {
  const events = []
  const ac = new AbortController()
  const done = (async () => {
    const r = await fetch(`${base}/term/${id}/stream`, {
      headers: { 'x-phone-token': TOKEN },
      signal: ac.signal,
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
          const frame = buf.slice(0, i)
          buf = buf.slice(i + 2)
          const ev = /^event: (.*)$/m.exec(frame)
          const da = /^data: (.*)$/m.exec(frame)
          if (ev && da) events.push({ event: ev[1], data: JSON.parse(da[1]) })
        }
      }
    } catch { /* aborted */ }
  })()
  return { events, stop: () => ac.abort(), done }
}

const stream = openStream()
await new Promise((r) => setTimeout(r, 800))
check('SSE 首帧是 hello', stream.events[0] && stream.events[0].event === 'hello',
  stream.events[0] ? JSON.stringify(Object.keys(stream.events[0].data)) : 'no events')

/* ---------- 3. 敲一条命令 ---------- */
await post(`/term/input`, {}) // 错误的 id 路径（应 404/400）
const bad = await post(`/term/nope/input`, { data: 'x' })
check('不存在的终端返回 404', bad.status === 404, `status=${bad.status}`)

await post(`/term/${id}/input`, { data: 'echo MARKER-ABC\n' })
await new Promise((r) => setTimeout(r, 1200))
let out = stream.events.filter((e) => e.event === 'out').map((e) => e.data.data).join('')
check('命令输出回来了', out.includes('MARKER-ABC'), JSON.stringify(out.slice(-80)))

/* ---------- 4. 中文 + 多字节边界 ---------- */
const cn = '你好世界·终端测试·汉子'
await post(`/term/${id}/input`, { data: `printf '%s\\n' '${cn}' | cat\n` })
await new Promise((r) => setTimeout(r, 1200))
out = stream.events.filter((e) => e.event === 'out').map((e) => e.data.data).join('')
check('中文没错码（无 U+FFFD）', out.includes(cn) && !out.includes('\uFFFD'),
  `含原串=${out.includes(cn)} 含替换符=${out.includes('\uFFFD')}`)

/* ---------- 5. 大输出：验证 UTF-8 分块解码 ---------- */
const beforeLen = out.length
await post(`/term/${id}/input`, { data: `seq 1 400 | sed 's/$/-中文尾巴/' | tail -50\n` })
await new Promise((r) => setTimeout(r, 1800))
out = stream.events.filter((e) => e.event === 'out').map((e) => e.data.data).join('')
const big = out.slice(beforeLen)
check('大段输出无替换符且成行', !big.includes('\uFFFD') && big.includes('400-中文尾巴'),
  `片段长=${big.length}`)

/* ---------- 6. Ctrl-C 打断前台进程 ---------- */
await post(`/term/${id}/input`, { data: 'sleep 30\n' })
await new Promise((r) => setTimeout(r, 700))
const sig = await post(`/term/${id}/signal`, { signal: 'SIGINT' })
await new Promise((r) => setTimeout(r, 900))
const afterSig = stream.events.filter((e) => e.event === 'out').map((e) => e.data.data).join('')
check('SIGINT 被接受', sig.status === 200 && sig.json.ok === true, JSON.stringify(sig.json))
await post(`/term/${id}/input`, { data: 'echo AFTER-SIGINT\n' })
await new Promise((r) => setTimeout(r, 900))
const afterSig2 = stream.events.filter((e) => e.event === 'out').map((e) => e.data.data).join('')
check('打断后终端仍可用', afterSig2.includes('AFTER-SIGINT'),
  `sig 前有提示符=${afterSig.length > 0}`)

/* ---------- 7. 非法信号被拒 ---------- */
const badSig = await post(`/term/${id}/signal`, { signal: 'SIGUSR1' })
check('非法信号被拒（400）', badSig.status === 400, `status=${badSig.status}`)

/* ---------- 8. list 能看到它 ---------- */
const list = await get('/term/list')
check('list 里有这条终端', list.json.terminals.some((t) => t.id === id),
  JSON.stringify(list.json.terminals.map((t) => ({ id: t.id, clients: t.clients, buf: t.buffered }))))

/* ---------- 9. 重连拿到缓冲（切走再回来） ---------- */
stream.stop()
await new Promise((r) => setTimeout(r, 300))
await post(`/term/${id}/input`, { data: 'echo WHILE-AWAY\n' })
await new Promise((r) => setTimeout(r, 900))
const re = openStream()
await new Promise((r) => setTimeout(r, 900))
const hello = re.events[0]
check('重连 hello 里带上了离开期间的输出',
  hello && hello.event === 'hello' && String(hello.data.buffered).includes('WHILE-AWAY'),
  hello ? `buffered=${String(hello.data.buffered).length}字符` : 'no hello')
re.stop()

/* ---------- 10. close 杀掉真进程 ---------- */
const pid = opened.json.pid
await post(`/term/${id}/close`)
await new Promise((r) => setTimeout(r, 600))
let alive = true
try { execFileSync('kill', ['-0', String(pid)]); } catch { alive = false }
check('close 之后进程没了', !alive, `pid=${pid}`)
const after = await post(`/term/${id}/input`, { data: 'x\n' })
check('关掉后输入被拒（404）', after.status === 404, `status=${after.status}`)

/* ---------- 11. 插件卸载杀掉所有 PTY ---------- */
const t2 = await post('/term/open', { cwd: '/tmp' })
const pid2 = t2.json.pid
stops[0]()
await new Promise((r) => setTimeout(r, 600))
let alive2 = true
try { execFileSync('kill', ['-0', String(pid2)]); } catch { alive2 = false }
check('dispose 之后 PTY 全被杀掉', !alive2, `pid=${pid2}`)

console.log(`\n==== 终端离线验收：${pass} 项通过，${fail} 项失败 ====`)
server.close()
process.exit(fail === 0 ? 0 : 1)
