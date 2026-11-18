/**
 * 离线验收：会话里的图片 / 文件 / present 交付物。
 *
 * 这里故意打真 HTTP，不只测 helper：历史响应必须只带附件元数据，字节按需从
 * /phone/attachment 或 /phone/file/raw 取；两条路都要有令牌、大小上限和正确 MIME。
 */
import http from 'node:http'
import { createBridge } from '../../bridge/bridge-core.js'

const TOKEN = 'test-token'
const H = { 'x-phone-token': TOKEN }
const routes = []
const imageReads = []
const fileReads = []
const binaryPaths = []
const imageBytes = Uint8Array.from([137, 80, 78, 71, 13, 10, 26, 10])
const fileBytes = Uint8Array.from([1, 2, 3, 4, 5])

const imageRef = {
  attachmentId: 'img-user-1', mediaType: 'image/png', bytes: imageBytes.length,
  width: 2, height: 3, name: '手机上传.png',
}
const imageRef2 = { ...imageRef, attachmentId: 'img-tool-1', name: '读取结果.png' }
const fileRef = { attachmentId: 'file-user-1', name: '说明.txt', bytes: fileBytes.length }

const baseRecords = [
  {
    seq: 10, time: 1000, type: 'user/message', data: {
      message: {
        content: [
          { type: 'text', text: '看这两份附件' },
          { type: 'image', attachment: imageRef },
          { type: 'file', attachment: fileRef },
        ],
      },
    },
  },
  {
    seq: 10.5, time: 1000.5, type: 'assistant/message', data: {
      message: {
        content: [
          { type: 'text', text: '附件如下' },
          { type: 'image', attachment: imageRef },
          { type: 'file', attachment: fileRef },
        ],
      },
    },
  },
  {
    seq: 11, time: 1001, type: 'tool/result', data: {
      message: {
        content: [
          { type: 'text', text: 'Image: /work/docs/chart.png (2×3)' },
          { type: 'image', attachment: imageRef2 },
        ],
      },
    },
  },
]
const deliveredFrame = {
  type: 'event', event: {
    seq: 12, time: 1002, type: 'deliverables/presented', data: {
      turn: 1, callId: 'present-1',
      files: [
        { path: 'docs/chart.png', description: '结果图' },
        { path: 'docs/report.pdf', description: '完整报告' },
      ],
    },
  },
}
const records = [...baseRecords, deliveredFrame.event]

const sessionController = {
  list: () => Promise.resolve({ items: [{ sessionId: 's1', cwd: '/work' }] }),
  follow(request) {
    let step = 0
    const iterator = {
      next() {
        step += 1
        if (step === 1) {
          return Promise.resolve({
            done: false,
            value: {
              type: 'snapshot', cursor: 12, hasMore: false,
              header: { id: 's1', cwd: '/work' },
              // 实时流把 present 放在下一帧，顺便验证增量事件不会被过滤。
              records: request.assistantStream ? baseRecords : records,
            },
          })
        }
        if (step === 2 && request.assistantStream) return Promise.resolve({ done: false, value: deliveredFrame })
        return new Promise(() => {})
      },
      return: () => Promise.resolve({ done: true }),
    }
    return { [Symbol.asyncIterator]: () => iterator }
  },
}

const attachments = {
  readImage(ref) {
    imageReads.push(ref)
    return Promise.resolve({ ref, data: imageBytes })
  },
  readFileStream(ref) {
    fileReads.push(ref)
    return (async function * () {
      yield fileBytes.slice(0, 2)
      yield fileBytes.slice(2)
    })()
  },
}
const fs = {
  resolve: (abs) => Promise.resolve(abs),
  stat: () => Promise.resolve({ type: 'file' }),
  readBytes(target, _signal, maxBytes) {
    binaryPaths.push({ target, maxBytes })
    return Promise.resolve(target.endsWith('.png') ? imageBytes : Uint8Array.from([37, 80, 68, 70]))
  },
}

const ctx = {
  get(name) {
    if (name === 'webServer') return { register: (r) => { routes.push(r); return () => {} } }
    if (name === 'timer') return { interval: () => () => {}, timeout: () => () => {} }
    if (name === 'sessionController') return sessionController
    if (name === 'attachments') return attachments
    if (name === 'fs') return fs
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
function check(name, ok, extra = '') {
  if (ok) { pass += 1; console.log('✓ ', name, extra) }
  else { fail += 1; console.log('✗ ', name, extra) }
}
const sameBytes = (actual, expected) => Buffer.from(actual).equals(Buffer.from(expected))
const attachmentQuery = (kind, ref) => {
  const q = new URLSearchParams({
    kind, attachmentId: ref.attachmentId, name: ref.name, bytes: String(ref.bytes),
  })
  if (kind === 'image') {
    q.set('mediaType', ref.mediaType)
    q.set('width', String(ref.width))
    q.set('height', String(ref.height))
  }
  return q
}

try {
  const history = await (await fetch(`${base}/sessions/s1/history?max=20`, { headers: H })).json()
  const messages = history.messages || []
  const user = messages.find((m) => m.role === 'user')
  const assistant = messages.find((m) => m.role === 'assistant')
  const tool = messages.find((m) => m.role === 'tool-result')
  const delivered = messages.find((m) => m.role === 'deliverable')
  check('历史保留用户/助手图片与文件元数据', user?.attachments?.length === 2 && assistant?.attachments?.length === 2)
  check('历史不再用占位符替代有效附件', !user?.text?.includes('[图片]') && !user?.text?.includes('[文件]') && !assistant?.text?.includes('[图片]') && !assistant?.text?.includes('[文件]'), `${user?.text} / ${assistant?.text}`)
  check('read_image 的工具结果也保留图片', tool?.attachments?.[0]?.attachmentId === 'img-tool-1')
  check('present 交付物成为独立文件卡', delivered?.attachments?.length === 2, JSON.stringify(delivered?.attachments))
  check('present 图片路径与说明没有丢', delivered?.attachments?.[0]?.path === 'docs/chart.png' && delivered?.attachments?.[0]?.description === '结果图')
  check('历史响应不塞 base64 字节', !JSON.stringify(history).includes(Buffer.from(imageBytes).toString('base64')))

  {
    const res = await fetch(`${base}/attachment?${attachmentQuery('image', imageRef)}`, { headers: H })
    const body = new Uint8Array(await res.arrayBuffer())
    check('图片附件按需返回原始字节', res.status === 200 && sameBytes(body, imageBytes), `HTTP ${res.status}`)
    check('图片 MIME 与长度正确', res.headers.get('content-type') === 'image/png' && res.headers.get('content-length') === String(imageBytes.length))
    check('图片读取使用完整 durable ref', imageReads.at(-1)?.width === 2 && imageReads.at(-1)?.height === 3 && imageReads.at(-1)?.mediaType === 'image/png')
  }
  {
    const res = await fetch(`${base}/attachment?${attachmentQuery('file', fileRef)}`, { headers: H })
    const body = new Uint8Array(await res.arrayBuffer())
    check('文件附件跨 chunk 拼回同一组字节', res.status === 200 && sameBytes(body, fileBytes), `HTTP ${res.status}`)
    check('文件流读取使用 id/name/bytes', fileReads.at(-1)?.attachmentId === 'file-user-1' && fileReads.at(-1)?.name === '说明.txt' && fileReads.at(-1)?.bytes === 5)
  }
  {
    const before = fileReads.length
    const q = attachmentQuery('file', { ...fileRef, bytes: 32 * 1024 * 1024 + 1 })
    const res = await fetch(`${base}/attachment?${q}`, { headers: H })
    check('超过 32 MB 在读取前拒绝', res.status === 413 && fileReads.length === before, `HTTP ${res.status}`)
  }
  {
    const res = await fetch(`${base}/file/raw?${new URLSearchParams({ path: 'docs/chart.png', sessionId: 's1' })}`, { headers: H })
    const body = new Uint8Array(await res.arrayBuffer())
    check('present 相对路径按会话 cwd 读取', res.status === 200 && binaryPaths.at(-1)?.target === '/work/docs/chart.png', JSON.stringify(binaryPaths.at(-1)))
    check('路径图片返回字节且使用 32 MB 上限', sameBytes(body, imageBytes) && binaryPaths.at(-1)?.maxBytes === 32 * 1024 * 1024)
  }
  {
    const res = await fetch(`${base}/attachment?${attachmentQuery('image', imageRef)}`)
    check('二进制附件无令牌仍是一律 401', res.status === 401, `HTTP ${res.status}`)
    const missing = await fetch(`${base}/attachment?kind=file&attachmentId=`, { headers: H })
    check('缺 attachmentId 是 400 而不是路由 404', missing.status === 400, `HTTP ${missing.status}`)
  }

  // 实时流：snapshot 里没有 present，下一条 event 才出现；必须真的推给 App。
  {
    const response = await fetch(`${base}/sessions/s1/live?max=20`, { headers: H })
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let wire = ''
    const deadline = Date.now() + 3000
    while (!wire.includes('deliverable') && Date.now() < deadline) {
      const next = await reader.read()
      if (next.done) break
      wire += decoder.decode(next.value, { stream: true })
    }
    check('present 增量事件实时推到会话流', wire.includes('event: messages') && wire.includes('"role":"deliverable"'))
    await reader.cancel()
  }
} finally {
  server.close()
}

console.log(`\n==== 附件预览验收：${pass} 项通过，${fail} 项失败 ====`)
process.exit(fail === 0 ? 0 : 1)
