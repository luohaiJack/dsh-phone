#!/usr/bin/env node
/**
 * 把 bridge-core.js 内联成「动态 Cordis 插件」的 code.host。
 *
 * 用途：在**不重启 harness** 的前提下把桥接挂进正在运行的进程调试。
 * 生产不要用这条路径 —— 动态插件重启即失效，正式装载见 scripts/enable-bridge.sh。
 *
 * 用法：
 *   node bridge/tools/gen-dynamic.mjs                 # 打印到 stdout
 *   node bridge/tools/gen-dynamic.mjs --out /tmp/x.js # 写入文件
 *   node bridge/tools/gen-dynamic.mjs --token abc123  # 给桥接设一个令牌
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const corePath = join(here, '..', 'bridge-core.js')

const args = process.argv.slice(2)
const outIdx = args.indexOf('--out')
const tokIdx = args.indexOf('--token')
const outFile = outIdx >= 0 ? args[outIdx + 1] : null

/**
 * 令牌优先级：命令行 > bridge/.token > 现场生成一个并写回 .token。
 * 仓库里**不放**任何令牌（要公开），所以默认这条链一定能拿到一个。
 */
const tokenFile = join(here, '..', '.token')
function resolveToken() {
  if (tokIdx >= 0 && args[tokIdx + 1]) return args[tokIdx + 1]
  if (existsSync(tokenFile)) {
    const saved = readFileSync(tokenFile, 'utf8').trim()
    if (saved) return saved
  }
  const generated = randomBytes(24).toString('hex')
  writeFileSync(tokenFile, generated + '\n', { mode: 0o600 })
  console.error(`已生成令牌并写入 ${tokenFile}`)
  return generated
}
const token = resolveToken()
const minify = args.includes('--minify')

let source = readFileSync(corePath, 'utf8')

// 去掉 ESM 导出关键字，让函数体可以直接内联进动态宿主的函数作用域
source = source.replace(/^export\s+function\s+createBridge/m, 'function createBridge')

// 动态宿主没有 import/require，做一次硬校验，避免把坏代码送进去
const forbidden = [/^\s*import\s/m, /\brequire\s*\(/, /\bprocess\./, /\bBuffer\./, /\bfetch\s*\(/, /\bsetTimeout\s*\(/, /\bsetInterval\s*\(/]
for (const re of forbidden) {
  if (re.test(source)) {
    console.error(`bridge-core.js 含有动态宿主不可用的构造：${re}`)
    process.exit(1)
  }
}

let body = source

if (minify) {
  // 只做两件安全的事：去掉块注释、压掉多余空行。
  // 不去行注释、不动字符串 —— 正则去 // 会误伤 URL 与路径。
  const before = body.length
  body = body.replace(/\/\*[\s\S]*?\*\//g, '')
  // JS 不依赖缩进，只需保留换行（ASI）；本文件不含模板字面量，去掉行首空白是安全的
  body = body.replace(/^[ \t]+/gm, '')
  body = body.replace(/[ \t]+$/gm, '')
  body = body.replace(/\n{2,}/g, '\n')
  console.error(`minified: ${before} -> ${body.length} bytes`)
}

const config = JSON.stringify({ token })

const hostCode = `${body}

return {
  inject: ['webServer', 'sessionController'],
  apply(ctx) {
    ctx.effect(function mountPhoneBridge() {
      return createBridge(ctx, ${config})
    })
  },
}
`

if (outFile) {
  writeFileSync(outFile, hostCode, 'utf8')
  console.error(`wrote ${outFile} (${hostCode.length} bytes)`)
} else {
  process.stdout.write(hostCode)
}
