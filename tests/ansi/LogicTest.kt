import com.dsh.phone.AnsiSpan
import com.dsh.phone.CursorKind
import com.dsh.phone.TermState
import com.dsh.phone.cursorKind
import com.dsh.phone.renderCells

/**
 * 终端两处"纯逻辑、但错了很难看出来"的地方：
 *   1. 状态文案（挂起 ≠ 断线）
 *   2. 光标格子的列号（一个汉字占两列，列号 ≠ 字符数）
 *
 * 都是**看不见的错**：文案错了用户以为坏了；光标偏一格，肉眼很难发现，
 * 但在 vim 里编辑就会一直插错位置。
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

    /* ---------- 光标格子：位置必须按"列"算 ---------- */

    fun cursorColOf(line: AnsiSpan, col: Int?): Int? =
        renderCells(listOf(line), col).firstOrNull { it.onCursor }?.col

    run {
        val line = AnsiSpan("abcdef")
        check("光标在行内", cursorColOf(line, 3) == 3, "得到=${cursorColOf(line, 3)}")
        check("光标在行首", cursorColOf(line, 0) == 0)
        check("行尾之后补空格再放光标", cursorColOf(line, 8) == 8, "得到=${cursorColOf(line, 8)}")
        val cells = renderCells(listOf(line), 8)
        check("补出来的空格数量对", cells.size == 9, "格子数=${cells.size}")
        check("空行也能放光标", renderCells(emptyList(), 0).single().onCursor)
        check("没有光标时不画", renderCells(listOf(line), null).none { it.onCursor })
    }

    run {
        // ★ 中文：两个汉字 = 4 列，列号和字符数差一倍
        val line = AnsiSpan("中文x")
        val cells = renderCells(listOf(line), 4)
        check("中文行里光标按列落在 x 上", cells.first { it.onCursor }.ch == 'x',
            "光标格=${cells.firstOrNull { it.onCursor }?.ch}")
        check("中文占两列", cells.filter { it.ch == '中' }.single().col == 0 &&
            cells.filter { it.ch == '文' }.single().col == 2,
            cells.joinToString("|") { "${it.ch}@${it.col}" })
        check("中文行里光标在第二个汉字里",
            renderCells(listOf(line), 3).first { it.onCursor }.ch == '文')
        check("中文行尾之后的光标列号对",
            renderCells(listOf(line), 6).first { it.onCursor }.col == 6,
            "得到=${renderCells(listOf(line), 6).first { it.onCursor }.col}")
    }

    /* ---------- 光标该不该画（决定"vim 里看不见光标"会不会再发生） ---------- */

    check("可见 → 实心", cursorKind(3, 3, fullScreen = false, visible = true) == CursorKind.SOLID)
    check("非光标行不画", cursorKind(2, 3, fullScreen = false, visible = true) == CursorKind.NONE)
    check("没有光标行不画", cursorKind(0, null, fullScreen = false, visible = true) == CursorKind.NONE)
    check("命令行模式隐藏时真的不画",
        cursorKind(3, 3, fullScreen = false, visible = false) == CursorKind.NONE)
    // ★ 事故现场：vim 报告隐藏光标时也必须留个位置提示，否则是盲操作
    check("全屏程序隐藏时画淡的",
        cursorKind(3, 3, fullScreen = true, visible = false) == CursorKind.DIM)
    check("全屏程序可见时是实心",
        cursorKind(3, 3, fullScreen = true, visible = true) == CursorKind.SOLID)

    println("\n==== 终端状态文案 + 光标格子验收：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}
