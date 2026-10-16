/**
 * 热重载机制验收 —— "改完桥接不用重启 dsh web"。
 *
 * 这条链的风险全在**一个假设**上：Node 的 ESM 模块缓存按完整 URL（含 query）索引，
 * 所以 `import('...bridge-core.js?v=xxx')` 能拿到新代码，而静态 `import` 只有第一次生效。
 * 假设错了的表现是"改完没反应、还以为生效了"——最难发现的那种错，
 * 所以这里用真文件、真 import 把它钉住。
 */
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

let passed = 0
let failed = 0
function check(name, ok, extra) {
  if (ok) {
    passed += 1
    console.log(`✓  ${name}${extra ? ' : ' + extra : ''}`)
  } else {
    failed += 1
    console.log(`✗  ${name}${extra ? ' : ' + extra : ''}`)
  }
}

const dir = mkdtempSync(join(tmpdir(), 'dsh-reload-'))
const file = join(dir, 'core.mjs')
const write = (v) => writeFileSync(file, `export const MARK = ${JSON.stringify(v)}\n`)
const url = (q) => pathToFileURL(file).href + (q ? '?v=' + q : '')

write('first')
const a1 = await import(url('1'))
check('第一次加载拿到当前内容', a1.MARK === 'first', a1.MARK)

write('second')
const a2 = await import(url('1'))
check('★ 同一 URL 命中缓存（这就是"改完没反应"的原因）', a2.MARK === 'first', a2.MARK)

const a3 = await import(url('2'))
check('★ 换 query 就是新实例 → 拿到新代码', a3.MARK === 'second', a3.MARK)

// 桥接入口必须真的用这个机制（不是只写在文档里）
// 三层结构：package.json → entry.js（薄壳，永不再改）→ mount.js（挂载层）→ bridge-core.js（实现）
const fs = await import('node:fs')
const read = (rel) => fs.readFileSync(new URL(rel, import.meta.url), 'utf8')
const entrySrc = read('../../bridge/lib/entry.js')
const mountSrc = read('../../bridge/lib/mount.js')
const pkg = JSON.parse(read('../../bridge/package.json'))

const entry = await import('../../bridge/lib/entry.js')
check('入口导出了 apply/name/inject', typeof entry.apply === 'function' && !!entry.name && Array.isArray(entry.inject))
check('★ package.json 指向薄壳 entry.js（以后改 mount.js 就行）',
  pkg.main === 'lib/entry.js' && pkg.exports['.'] === './lib/entry.js', String(pkg.main))
check('★ 薄壳用动态 import 加载 mount（不是静态 import）',
  /return import\(url\.href\)/.test(entrySrc) && !/^import .* from '\.\/mount\.js'/m.test(entrySrc))
check('★ 薄壳里只有加载逻辑，没有业务代码（改业务不用碰它）',
  entrySrc.length < 3500 && !/createBridge|resolveToken/.test(entrySrc), entrySrc.length + ' 字节')
check('★ query 里带版本号（mtime/size + 随机数）',
  /searchParams\.set\('v', stamp/.test(entrySrc) && /searchParams\.set\('v', stamp/.test(mountSrc))
check('★ 薄壳同步注册 effect + 同步 disposer（不依赖宿主 await apply）',
  /ctx\.effect\(function mountBridge\(\) \{/.test(entrySrc) && /return function dispose\(\) \{/.test(entrySrc))
check('unmount 能取消尚未完成的加载', /state\.cancelled = true/.test(entrySrc) && /state\.cancelled = true/.test(mountSrc))
check('mount.js 里把 createBridge 的 disposer 交回薄壳（effect 只有一个主人）',
  /state\.dispose = mod\.createBridge/.test(mountSrc) && !/ctx\.effect/.test(mountSrc))
check('旧路径 lib/index.js 仍能解析（兼容转发）', /from '\.\/entry\.js'/.test(read('../../bridge/lib/index.js')))

console.log(`\n==== 热重载机制验收：${passed} 项通过，${failed} 项失败 ====`)
process.exit(failed === 0 ? 0 : 1)
