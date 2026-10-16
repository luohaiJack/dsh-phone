/**
 * 兼容转发：真正的入口是 `entry.js`（package.json 的 main/exports 指向它）。
 *
 * 留着这个文件是因为早期版本指过它，直接删掉会让"旧解析结果 + 新目录"出现 404 式的怪问题。
 * 新代码请改 `mount.js`（挂载逻辑）或 `bridge-core.js`（桥接实现）。
 */
export { name, inject, apply, default } from './entry.js'
