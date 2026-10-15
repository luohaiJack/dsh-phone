import com.dsh.phone.TermState

/**
 * 终端状态文案的验收。
 *
 * 为什么值得单独测：这里出过一次"文案骗人"的事故 ——
 * 用户离开终端页（我们主动断开输出流，电脑上的 shell 还活着），
 * 状态页却显示"连接中断，重连中…"，看起来就是**终端坏了**。
 * 从此"挂起"和"断线"必须分开说。
 */
var pass = 0
var fail = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) { pass++; println("✓  $name $extra") } else { fail++; println("✗  $name $extra") }
}

fun main() {
    check("未打开", TermState().statusText == "未打开", TermState().statusText)
    check("正在开", TermState(opening = true).statusText == "正在电脑上开终端…")
    check("已连接", TermState(id = "t1", connected = true).statusText == "已连接")
    check("重连中（真的断了）",
        TermState(id = "t1", connected = false, detached = false).statusText == "连接中断，重连中…",
        TermState(id = "t1").statusText)
    // ★ 事故现场：离开页面只是"挂起"，电脑上的 shell 还在
    check("挂起 ≠ 断线",
        TermState(id = "t1", connected = false, detached = true).statusText == "已挂起 · 电脑上还开着",
        TermState(id = "t1", detached = true).statusText)
    check("退出带 code", TermState(id = "t1", exited = true, exitCode = 137).statusText.contains("137"))
    check("错误优先显示",
        TermState(id = "t1", error = "电脑上的终端已经关掉了").statusText == "电脑上的终端已经关掉了")
    check("挂起时也算活着（不用重开）", TermState(id = "t1", detached = true).alive)
    check("退出后不算活着", !TermState(id = "t1", exited = true).alive)
    check("挂起可续（进来直接接上）", TermState(id = "t1", detached = true).resumable)

    println("\n==== 终端状态文案验收：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}
