/**
 * dsh-phone-bridge —— 进程入口（薄壳，**刻意做成永远不用改**）。
 *
 * 它只做一件事：把 `mount.js` 用"带版本号的动态 import"加载进来。
 *
 * 为什么要有这层壳（§61）：
 *  - Node 的 ESM 缓存按**完整 URL**（含 query）索引 → 换 query 就是新实例；
 *  - 但**进程入口自己不换 URL 就永远是缓存里那一份** —— 所以入口里绝不能放
 *    业务逻辑，否则"改完不用重启"这件事每次都要先重启一次才能成立（先有鸡后有蛋）；
 *  - 于是：入口只负责"按 mtime+size 拼 query 去 import mount.js"，
 *    以后 mount.js / bridge-core.js 随便改，**开关一次插件就生效**；
 *  - 真到了必须改入口的那天（极少），换一个新文件名、把 package.json 指过去即可。
 */
import { statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const name = 'dsh-phone-bridge'

// 宿主平面硬依赖：没有 webServer 就没地方挂路由，没有 sessionController 就没有会话可桥接
export const inject = ['webServer', 'sessionController']

const MOUNT_PATH = join(dirname(fileURLToPath(import.meta.url)), 'mount.js')

function loadMount() {
  let stamp = 'dev'
  try {
    const st = statSync(MOUNT_PATH)
    stamp = String(st.mtimeMs) + '-' + String(st.size)
  } catch (err) {
    /* 读不到就用随机数，import 自己会报错 */
  }
  const url = pathToFileURL(MOUNT_PATH)
  url.searchParams.set('v', stamp + '-' + randomBytes(4).toString('hex'))
  return import(url.href)
}

/**
 * 同步注册 effect、把加载放进 effect 内部：
 * 不管宿主是否 await `apply` 的返回值，生命周期都对
 * （effect 已登记；disposer 同步返回，且在加载完成前被卸载也能取消）。
 */
export function apply(ctx, config) {
  ctx.effect(function mountBridge() {
    const state = { dispose: null, cancelled: false }
    loadMount().then(
      function (mod) {
        if (state.cancelled) return
        state.dispose = mod.apply(ctx, config)
      },
      function (err) {
        console.error(`[dsh-phone-bridge] 加载 mount.js 失败：${String(err?.message || err)}`)
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
  })
}

export default { name, inject, apply }
