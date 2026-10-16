/**
 * dsh-phone-bridge —— 挂载层（会被 entry.js 用带版本号的动态 import 加载）。
 *
 * 这里放"怎么挂桥接"：解析令牌、动态 import bridge-core.js（带版本号 → 热更）、
 * 登记 effect 与 disposer。**它可以随时改，改完开关一次插件就生效**（见 §61）。
 */
import { existsSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath, pathToFileURL } from 'node:url'

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

const CORE_PATH = join(dirname(fileURLToPath(import.meta.url)), '..', 'bridge-core.js')

/**
 * 加载 bridge-core.js —— **动态 import + 版本号**，这就是"改完不用重启"的关键。
 *
 * 背景（§50/§61）：静态 `import` 的模块在 Node 的 ESM 缓存里只实例化一次，
 * 所以改完核心代码必须重启 `dsh web` 才生效 —— 而那会打断用户正在跑的会话。
 * 而 Node 的模块缓存是**按完整 URL**（含 query）索引的：
 * 把核心文件的 mtime+大小 拼进 query，重新 apply 时就会拿到一份**全新的实例**，
 * 老实例由上面的 disposer 正常卸载（终端、SSE、监听全清）。
 *
 * 为什么还要拼随机数：同一毫秒内改两次（或被 touch）时 mtime 不变 ——
 * 版本号只用来"区分这一次加载"，不需要稳定，随机数最省事也最不会出错。
 */
function loadCore() {
  let stamp = 'dev'
  try {
    const st = statSync(CORE_PATH)
    stamp = String(st.mtimeMs) + '-' + String(st.size)
  } catch (err) {
    /* 读不到就用随机数，下面照样能 import（报错由调用方打印） */
  }
  const url = pathToFileURL(CORE_PATH)
  url.searchParams.set('v', stamp + '-' + randomBytes(4).toString('hex'))
  return import(url.href)
}

/**
 * 挂载桥接。
 *
 * 刻意**同步注册 effect**、把动态 import 放在 effect 内部：
 * 这样不管宿主是否 await `apply` 的返回值，生命周期都是对的
 * （effect 已经登记好；disposer 同步返回，且在加载完成前被卸载也能取消）。
 */
/**
 * 挂载桥接，返回 disposer。
 *
 * 注意这里**不再自己注册 effect** —— 那是入口（entry.js）的事：
 * 入口已经用带版本号的动态 import 保证"这一份 mount.js 是新的"，
 * 再把 effect 登记在入口的作用域里，卸载语义才只有一个主人。
 */
export function apply(ctx, config) {
  const state = { dispose: null, cancelled: false }
  loadCore().then(
    function (mod) {
      if (state.cancelled) return
      state.dispose = mod.createBridge(ctx, { ...(config || {}), token: resolveToken(config) })
    },
    function (err) {
      console.error(`[dsh-phone-bridge] 加载 bridge-core.js 失败：${String(err?.message || err)}`)
    },
  )
  return function dispose() {
    state.cancelled = true
    if (typeof state.dispose === 'function') {
      try {
        state.dispose()
      } catch (err) {
        console.error(`[dsh-phone-bridge] 卸载时的清理出错（忽略）：${String(err?.message || err)}`)
      }
    }
  }
}

export default { name, inject, apply }
