import com.dsh.phone.AnsiSpan
import com.dsh.phone.CursorKind
import com.dsh.phone.HarnessState
import com.dsh.phone.JobInfo
import com.dsh.phone.SessionActivity
import com.dsh.phone.SessionInfo
import com.dsh.phone.SubagentInfo
import com.dsh.phone.TermState
import com.dsh.phone.sessionActivity
import com.dsh.phone.cursorKind
import com.dsh.phone.lastNonBlankRange
import com.dsh.phone.linesText
import com.dsh.phone.lineText
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

    /* ---------- 复制：选择范围取文本 ---------- */

    run {
        val lines = listOf(
            listOf(AnsiSpan("hello"), AnsiSpan(" world   ")),
            listOf(AnsiSpan("   ")),                       // 全空白行
            listOf(AnsiSpan("中文"), AnsiSpan("abc")),
        )
        check("去行尾空白、留行内空格", lineText(lines[0]) == "hello world", "得到=${lineText(lines[0])}")
        check("全空白行变空串", lineText(lines[1]) == "", "得到=${lineText(lines[1])}")
        check("跨 span 拼接", lineText(lines[2]) == "中文abc", "得到=${lineText(lines[2])}")

        check("取一段", linesText(lines, 0, 1) == "hello world\n", "得到=${linesText(lines, 0, 1).replace("\n","\\n")}")
        check("倒着选也对（先点下面再点上面）",
            linesText(lines, 2, 0) == linesText(lines, 0, 2), linesText(lines, 2, 0).replace("\n","\\n"))
        check("越界被夹住", linesText(lines, -5, 99) == linesText(lines, 0, 2))
        check("空列表不炸", linesText(emptyList(), 0, 3) == "")
        check("单行不带换行", !linesText(lines, 1, 1).contains("\n"))
    }

    /* ---------- "最近 N 行"要跳过屏幕下方的空白 ---------- */

    run {
        val screen = listOf(
            listOf(AnsiSpan("one")),
            listOf(AnsiSpan("two")),
            listOf(AnsiSpan("three")),
            emptyList(), listOf(AnsiSpan("   ")), emptyList(),   // 屏幕下方那些空行
        )
        val r = lastNonBlankRange(screen, 3)
        check("最近 3 行 = 最近 3 行内容（不是 3 个空行）",
            r != null && linesText(screen, r.first, r.second) == "one\ntwo\nthree",
            r?.let { linesText(screen, it.first, it.second).replace("\n", "|") } ?: "null")
        check("要多于现有内容时给全部", lastNonBlankRange(screen, 99)!!.first == 0)
        check("n=0 返回 null", lastNonBlankRange(screen, 0) == null)
        check("全空屏幕返回 null", lastNonBlankRange(listOf(emptyList(), emptyList()), 2) == null)
        val tail = lastNonBlankRange(screen, 1)
        check("最近 1 行是 three", tail != null && linesText(screen, tail.first, tail.second) == "three")
    }

    /* ---------- 会话里的"后台在跑什么"：谁是我的任务/子代理 ---------- */

    run {
        fun sess(id: String, parent: String?, running: Boolean) = SessionInfo(
            id = id, title = id, running = running, blank = false, updatedAt = 0L,
            cwd = null, origin = if (parent != null) "subagent" else null, parent = parent, live = running,
            jobs = emptyList(), goal = null,
        )
        fun job(id: String, owner: String?, status: String) = JobInfo(
            id = id, kind = "bash", label = "任务$id", status = status,
            startedAt = 0L, finishedAt = null, detail = null, owner = owner,
        )
        val state = HarnessState(
            ok = true, ts = 0L, model = null,
            sessions = listOf(
                sess("me", null, true),
                sess("other", null, false),
                sess("child1", "me", true),
                sess("child2", "me", false),
                sess("grand", "child1", true),      // 孙代理
                sess("stranger", "other", true),    // 别人的子代理
            ),
            jobs = listOf(
                job("j1", "me", "running"),
                job("j2", "me", "completed"),
                job("j3", "other", "running"),      // 别人的任务
                job("j4", null, "running"),         // 没主的任务
            ),
            error = null, totalSessions = 6,
        )
        val a = sessionActivity(state, "me")
        check("只算自己名下的任务", a.jobs.map { it.id } == listOf("j1", "j2"), a.jobs.map { it.id }.toString())
        check("在跑的任务只算 running/stopping", a.runningJobs.map { it.id } == listOf("j1"))
        check("子代理含后代（孙代理也在）", a.subagents.map { it.id }.toSet() == setOf("child1", "child2", "grand"),
            a.subagents.map { it.id }.toString())
        check("别人的子代理不算进来", a.subagents.none { it.id == "stranger" })
        check("层级：孙代理 depth=2", a.subagents.first { it.id == "grand" }.depth == 2)
        check("在跑的子代理排前面", a.subagents.first().id == "child1", a.subagents.map { "${it.id}:${it.running}" }.toString())
        check("在跑的数得对", a.runningSubagents.map { it.id }.toSet() == setOf("child1", "grand"))
        check("没有子代理时不报 null", a.subagents.isNotEmpty())
        check("空会话 id 返回空", sessionActivity(state, "").isEmpty)
        check("没有任何关系的会话是空的", sessionActivity(state, "nobody").isEmpty)

        // 值不值得显示：有在跑就显示；都结束了就看是不是"刚结束"
        val now = 1_000_000_000L
        check("有在跑就显示", a.worthShowing(now))
        val idle = SessionActivity(
            jobs = listOf(JobInfo("j", "bash", "老任务", "completed", 0L, now - 3 * 3600_000L, null, "me")),
            subagents = listOf(SubagentInfo("s", "老子代理", false, 1, now - 3 * 3600_000L)),
        )
        check("几小时前的记录不显示", !idle.worthShowing(now))
        val fresh = idle.copy(subagents = listOf(SubagentInfo("s", "刚跑完", false, 1, now - 60_000L)))
        check("刚结束的还显示一会儿", fresh.worthShowing(now))
    }

    println("\n==== UI 纯逻辑验收（状态文案 / 光标 / 复制 / 会话活动）：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}
