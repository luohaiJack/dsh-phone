/**
 * dsh-phone-bridge —— 静态宿主插件入口（生产路径）。
 *
 * 与动态插件共用同一份 bridge-core.js，区别只在外壳：
 *  - 静态：真正的 Node 进程内，随 `dsh web` 一起启动，持久生效
 *  - 动态：cordis_define 的 code.host 把同一个函数体内联进去，进程内即时生效、重启即失效
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'

import { createBridge } from '../bridge-core.js'

export const name = 'dsh-phone-bridge'

// 宿主平面硬依赖：没有 webServer 就没地方挂路由，没有 sessionController 就没有会话可桥接
export const inject = ['webServer', 'sessionController']

/**
 * 令牌解析：patch 里填了就用 patch 里的，没填就落到 `bridge/.token`。
 *
 * 为什么不把令牌写进 cordis.patch.yml 提交上去：那是要公开的仓库。
 * 空令牌 + tailscale serve = 任何人都能操作这台电脑，所以**不允许**空着跑，
 * 第一次挂载时就地生成一个 48 位十六进制令牌，权限 0600。
 * 手机 App 那边用 `scripts/install-to-phone.sh` 灌进去（它读同一个文件）。
 */
function resolveToken(config) {
  const configured = typeof config?.token === 'string' ? config.token.trim() : ''
  if (configured) return configured

  const tokenFile = join(dirname(fileURLToPath(import.meta.url)), '..', '.token')
  if (existsSync(tokenFile)) {
    const saved = readFileSync(tokenFile, 'utf8').trim()
    if (saved) return saved
  }

  const token = randomBytes(24).toString('hex')
  try {
    writeFileSync(tokenFile, token + '\n', { mode: 0o600 })
    console.log(`[dsh-phone-bridge] 已生成访问令牌 → ${tokenFile}（手机端用 scripts/install-to-phone.sh 灌入）`)
  } catch (err) {
    console.error(`[dsh-phone-bridge] 令牌文件写不了（${String(err?.message || err)}），本次使用内存令牌`)
  }
  return token
}

export function apply(ctx, config) {
  ctx.effect(function mountBridge() {
    return createBridge(ctx, { ...(config || {}), token: resolveToken(config) })
  })
}

export default { name, inject, apply }
