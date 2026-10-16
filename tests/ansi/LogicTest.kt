import com.dsh.phone.AnsiSpan
import com.dsh.phone.ChatMessage
import com.dsh.phone.mergeProgress
import com.dsh.phone.questionToShow
import com.dsh.phone.hasNewRepliesInHistory
import com.dsh.phone.looksLikeError
import com.dsh.phone.PendingAttachment
import com.dsh.phone.shortFileName
import com.dsh.phone.formatAttachList
import com.dsh.phone.parseAttachList
import com.dsh.phone.appendErrorLog
import com.dsh.phone.formatLogTime
import com.dsh.phone.planEntryScroll
import com.dsh.phone.SavedScroll
import com.dsh.phone.ActivityInfo
import com.dsh.phone.PendingQuestion
import com.dsh.phone.QuestionItem
import com.dsh.phone.overlayProgress
import com.dsh.phone.formatTokens
import com.dsh.phone.contextPercent
import com.dsh.phone.progressFromMessages
import com.dsh.phone.CursorKind
import com.dsh.phone.GoalInfo
import com.dsh.phone.HarnessState
import com.dsh.phone.goalWorthShowing
import com.dsh.phone.formatQueueEditDraft
import com.dsh.phone.hookKeystrokes
import com.dsh.phone.parseQueueEditDraft
import com.dsh.phone.JobInfo
import com.dsh.phone.SessionActivity
import com.dsh.phone.SessionInfo
import com.dsh.phone.SessionProgress
import com.dsh.phone.SubagentInfo
import com.dsh.phone.TermState
import com.dsh.phone.sessionActivity
import com.dsh.phone.cursorKind
import com.dsh.phone.lastNonBlankRange
import com.dsh.phone.lastUserIndexBefore
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

    /* ---------- 仪表盘：进度合并、从历史推、上下文比例 ---------- */

    run {
        // 桥接刚重载 → 它的镜像可能是空壳；历史里的轮号是真的；窗口只有实时事件才有
        val fromBridge = SessionProgress(turn = 1, step = 1, turns = 1, contextUsed = 144)
        val fromHistory = SessionProgress(turn = 42, step = 3, turns = 42, contextUsed = 90000)
        val fromLive = SessionProgress(contextWindow = 1_000_000)
        val m = mergeProgress(fromBridge, fromHistory, fromLive)
        check("逐字段取最全（不是整体覆盖）", m.turn == 42 && m.step == 3 && m.turns == 42, m.toString())
        check("窗口从实时那份来", m.contextWindow == 1_000_000)
        check("上下文取靠后那个非零值", m.contextUsed == 90000, m.contextUsed.toString())
        check("0 不覆盖已有的值", mergeProgress(SessionProgress(contextUsed = 500), SessionProgress(contextUsed = 0)).contextUsed == 500)
        // ★ 这条是"手机上显示的和电脑上不一样"的同族坑：上下文被压缩（compaction）后会变小，
        //   取最大值就会永远卡在旧的大数字上
        check("上下文被压缩后跟着变小",
            mergeProgress(SessionProgress(contextUsed = 617879), SessionProgress(contextUsed = 20000)).contextUsed == 20000,
            mergeProgress(SessionProgress(contextUsed = 617879), SessionProgress(contextUsed = 20000)).contextUsed.toString())
        check("空输入不炸", mergeProgress(null, null) == SessionProgress())
        // 只有"窗口大小"没有意义：那种情况下仪表盘没什么可说的，不该占一行
        check("只知道窗口不算有内容", !mergeProgress(fromLive).hasAnything)

        /* ---------- 权威/兜底两层：桥接那个数就是电脑右下角的数 ---------- */

        // 权威 = 桥接（宿主的 contextPressure 投影，含缓存命中）；兜底 = 老口径只算 input
        val auth = SessionProgress(turn = 74, step = 10, turns = 74, contextWindow = 1_000_000, contextUsed = 617879)
        val fb = SessionProgress(turn = 74, step = 10, turns = 74, contextUsed = 257)
        val o = overlayProgress(auth, fb)
        check("权威有值时压过兜底（257 不许顶上来）", o.contextUsed == 617879, o.contextUsed.toString())
        check("轮/步照样取最全", o.turn == 74 && o.step == 10 && o.turns == 74)
        val cold = overlayProgress(SessionProgress(), SessionProgress(turn = 3, step = 1, turns = 3, contextWindow = 1_000_000, contextUsed = 30000))
        check("权威还是空的时用兜底（冷会话）", cold.contextUsed == 30000 && cold.contextWindow == 1_000_000 && cold.turns == 3)
        check("权威窗口未知时用兜底窗口", overlayProgress(SessionProgress(contextUsed = 5), SessionProgress(contextWindow = 999)).contextWindow == 999)
        check("两边都空不炸", overlayProgress(SessionProgress(), SessionProgress()) == SessionProgress())

        // 历史推进度：最大 turn = 累计轮数
        val msgs = listOf(
            ChatMessage(1, 0, "user", "a", turn = 1),
            ChatMessage(2, 0, "assistant", "b", turn = 1, step = 3, contextUsed = 5000),
            ChatMessage(3, 0, "user", "c", turn = 2),
            ChatMessage(4, 0, "assistant", "d", turn = 2, step = 2, contextUsed = 8000),
        )
        val hp = progressFromMessages(msgs)!!
        check("累计轮数 = 最大 turn", hp.turns == 2 && hp.turn == 2, hp.toString())
        check("当前步取最新那条", hp.step == 2, hp.step.toString())
        check("上下文用量取最新那次", hp.contextUsed == 8000, hp.contextUsed.toString())
        check("空历史返回 null", progressFromMessages(emptyList()) == null)
        check("没有 turn 的消息不瞎算", progressFromMessages(listOf(ChatMessage(1, 0L, "user", "x")))!!.turns == 0)

        // 上下文比例：窗口未知就不给比例（宁可只显示数字，也不画瞎猜的进度条）
        check("知道窗口才算比例", SessionProgress(contextUsed = 250_000, contextWindow = 1_000_000).contextRatio == 0.25f)
        check("窗口未知 → null", SessionProgress(contextUsed = 250_000).contextRatio == null)
        check("超了也不超过 1", SessionProgress(contextUsed = 9_000_000, contextWindow = 1_000_000).contextRatio == 1f)

        // 文案
        check("文案：轮+步+累计",
            SessionProgress(turn = 3, step = 7, turns = 12).text == "第 3 轮 · 第 7 步 · 累计 12 轮",
            SessionProgress(turn = 3, step = 7, turns = 12).text)
        check("第一轮不写累计", SessionProgress(turn = 1, step = 1, turns = 1).text == "第 1 轮 · 第 1 步")
        check("啥都没有时说清楚", SessionProgress().text == "还没开始跑")
    }

    /* ---------- "跳到上一条我的提问"的判据 ---------- */

    run {
        // 一条提问后面跟着一堆工具消息 —— 这正是真实会话的样子
        val list = listOf(
            ChatMessage(1, 0L, "user", "第一问"),
            ChatMessage(2, 0L, "assistant", "答", turn = 1),
            ChatMessage(3, 0L, "tool", "bash", name = "bash"),
            ChatMessage(4, 0L, "tool-result", "结果"),
            ChatMessage(5, 0L, "assistant", "再答", turn = 1),
            ChatMessage(6, 0L, "user", "第二问"),
            ChatMessage(7, 0L, "assistant", "答", turn = 2),
            ChatMessage(8, 0L, "tool", "bash", name = "bash"),
        )
        check("从末尾往回找 = 第二问", lastUserIndexBefore(list, list.size) == 5,
            lastUserIndexBefore(list, list.size).toString())
        check("从第二问之前找 = 第一问", lastUserIndexBefore(list, 5) == 0,
            lastUserIndexBefore(list, 5).toString())
        check(
            "全是工具消息时找不到",
            lastUserIndexBefore(
                listOf(
                    ChatMessage(3, 0L, "tool", "bash", name = "bash"),
                    ChatMessage(4, 0L, "tool-result", "结果"),
                    ChatMessage(8, 0L, "tool", "bash", name = "bash"),
                ),
                3,
            ) == -1,
        )
        check("limit 超过长度不炸", lastUserIndexBefore(list, 999) == 5)
        check("limit=0 返回 -1", lastUserIndexBefore(list, 0) == -1)
        check("空列表返回 -1", lastUserIndexBefore(emptyList(), 10) == -1)
    }

    /* ---------- 目标该不该显示（已完成的不该一直挂着） ---------- */

    run {
        fun goal(phase: String, updatedAt: Long) = GoalInfo(
            objective = "把 X 做完", phase = phase, roundsStarted = 2, maxGoalRounds = 10,
            blockedReason = null, id = "g1", revision = 3, createdAt = 0L, updatedAt = updatedAt,
        )
        val now = 1_000_000_000L
        check("进行中要显示", goalWorthShowing(goal("active", now - 3600_000), now))
        check("受阻要显示（藏起来最糟）", goalWorthShowing(goal("blocked", now - 3600_000), now))
        check("已暂停要显示（它在等人恢复）", goalWorthShowing(goal("paused", now - 3600_000), now))
        check("刚完成还露一下脸", goalWorthShowing(goal("complete", now - 60_000), now))
        check("几天前完成的就别挂了", !goalWorthShowing(goal("complete", now - 3 * 24 * 3600_000L), now))
        check("completed 这种写法也认", !goalWorthShowing(goal("completed", now - 3600_000), now))
        check("没有目标就不显示", !goalWorthShowing(null, now))
    }

    /* ---------- 终端输入挂钩：回车必须真的发出去 ---------- */

    run {
        // ★ 这就是用户报的那个 bug：手机键盘回车"没反应"。
        //   修好之后回车是一句换行符 —— 必须 send 出去，不许被当空内容吞掉
        val enter = hookKeystrokes("\n", composing = false, ctrlLatch = false)
        check("回车要发出去（不是吞掉）", enter.send == "\n", enter.toString())
        check("发出去的不留在框里", !enter.keepInField)

        val a = hookKeystrokes("a", composing = false, ctrlLatch = false)
        check("普通字符照发", a.send == "a" && !a.keepInField)

        val composing = hookKeystrokes("nihao", composing = true, ctrlLatch = false)
        check("组词中先不发（中文要能选词）", composing.send == null && composing.keepInField)

        val empty = hookKeystrokes("", composing = false, ctrlLatch = false)
        check("删空了不发", empty.send == null && empty.keepInField)

        // Ctrl 锁存：点亮后按一个键 = 那个控制字符
        val ctrl = hookKeystrokes("c", composing = false, ctrlLatch = true)
        check("Ctrl 锁存把 c 变成 0x03（Ctrl-C）", ctrl.send == "\u0003", ctrl.send?.let { it[0].code.toString() } ?: "null")
        check("Ctrl 锁存用完就消费掉", ctrl.consumeLatch)
        val ctrlLong = hookKeystrokes("abc", composing = false, ctrlLatch = true)
        check("多字符提交不消费锁存（留给下一个键）", ctrlLong.send == "abc" && !ctrlLong.consumeLatch)
    }

    /* ---------- 排队消息"改到一半"的草稿：正文能带换行 ---------- */

    run {
        val one = formatQueueEditDraft("msg-1", "改过的内容")
        check("单行：编解码往返", parseQueueEditDraft(one) == ("msg-1" to "改过的内容"), parseQueueEditDraft(one).toString())

        // ★ 关键：正文自己带换行（用户改的常常是一段多行命令）——
        //   只能按**第一个**换行切分；按最后一个切、或者 split 全部，多行就被切坏了
        val multi = formatQueueEditDraft("msg-2", "第一行\n第二行\n第三行")
        val back = parseQueueEditDraft(multi)
        check("多行正文不切坏", back == ("msg-2" to "第一行\n第二行\n第三行"), back?.second?.replace("\n", "|") ?: "null")
        check("多行正文的行数没变", back!!.second.count { it == '\n' } == 2)

        check("没有换行的脏数据 → null", parseQueueEditDraft("msg-3") == null)
        check("空串 → null", parseQueueEditDraft("") == null)
        check("null → null", parseQueueEditDraft(null) == null)
        check("只有换行 → null", parseQueueEditDraft("\n正文") == null)
    }

    /* ---------- token 缩写 / 百分比：必须和电脑端右下角**一字不差** ---------- */

    run {
        // 电脑端 locale：number.thousand = "{value}K"、number.million = "{value}M"；
        // 缩放后 ≥100 取整，否则留一位小数
        check("1000 以下原样", formatTokens(257) == "257", formatTokens(257))
        check("千级留一位小数", formatTokens(15400) == "15.4K", formatTokens(15400))
        check("99.9K", formatTokens(99900) == "99.9K", formatTokens(99900))
        check("到 100 就取整", formatTokens(100000) == "100K", formatTokens(100000))
        check("61.8 万取整成 618K（和电脑一样舍精度）", formatTokens(617879) == "618K", formatTokens(617879))
        check("正好一千 = 1K（去掉 .0）", formatTokens(1000) == "1K", formatTokens(1000))
        check("百万级用 M", formatTokens(1000000) == "1M", formatTokens(1000000))
        check("1048576 → 1M（1.048 缩到一位小数就是 1）", formatTokens(1048576) == "1M", formatTokens(1048576))

        check("百分比取整", contextPercent(617879, 1000000) == 62, contextPercent(617879, 1000000).toString())
        check("不知道用量/窗口就不给百分比", contextPercent(0, 1000000) == null && contextPercent(5, 0) == null)
        check("超了封顶 100", contextPercent(9_000_000, 1_000_000) == 100, contextPercent(9_000_000, 1_000_000).toString())
    }

    /* ---------- 提问：状态轮询里那份该不该显示（2026-10-15 事故） ---------- */

    run {
        val open = "session-open"
        val mine = PendingQuestion("q1", listOf(QuestionItem("a", "题面", null, null, emptyList(), false)), open)
        val other = PendingQuestion("q2", listOf(QuestionItem("a", "别的会话的问题", null, null, emptyList(), false)), "session-other")

        // ★ 核心：会话流（快通道）断了的时候，问题只能靠状态轮询送来 ——
        //   这条规则错了，用户在手机上就永远看不到问题
        check("状态里有本会话的问题 → 显示", questionToShow(null, mine, open) === mine)
        check("★ 状态里的问题属于别的会话 → 不显示", questionToShow(null, other, open) == null)
        check("还没打开任何会话 → 不显示", questionToShow(null, mine, "") == null)

        // 只增不清：这一条防的是"卡片闪一下没了"
        check("★ 这次快照没带问题 → 保持原样（不许清）", questionToShow(mine, null, open) === mine)
        check("同一个问题重复到达 → 不重建", questionToShow(mine, mine, open) === mine)
        check("换了一个新问题 → 换成新的", questionToShow(mine, PendingQuestion("q9", mine.questions, open), open)?.id == "q9")
    }

    /* ---------- 进会话落在哪儿：有新回复→最新；没有→上次的位置 ---------- */

    run {
        val mid = SavedScroll(index = 42, offset = 7, followTail = false)

        // ★ 用户明确要求：在「未读回复」区里的会话，点进去就该看到最新内容
        val unread = planEntryScroll(hasNewReplies = true, saved = mid)
        check("★ 有新回复 → 直奔最新（跟随尾部）", unread.followTail && unread.index == 0 && unread.offset == 0,
            unread.toString())

        // 没有新回复 → 回到上次看的地方；哪怕它已被挤出「最近会话」前 6 条
        // （位置按会话 id 存，这里没有"列表顺序"这个输入，天然与列表无关）
        val back = planEntryScroll(hasNewReplies = false, saved = mid)
        check("★ 没有新回复 → 回到上次的位置", !back.followTail && back.index == 42 && back.offset == 7,
            back.toString())

        check("上次就在尾部 → 仍然跟随最新",
            planEntryScroll(false, SavedScroll(9, 3, followTail = true)).followTail)
        check("没有存过位置 → 看最新", planEntryScroll(false, null).followTail)
        check("存的是「第一条」（index=0）→ 当跟随处理",
            planEntryScroll(false, SavedScroll(0, 0, followTail = false)).followTail)
        // 有新回复时，"上次位置"不该有任何残留（否则会先跳旧位置再回尾部）
        check("有新回复时不保留旧索引",
            planEntryScroll(true, SavedScroll(99, 5, false)).let { it.followTail && it.index == 0 })

        // 旧规则是 saved?.takeIf { !it.followTail && it.index > 0 } —— 它只管"上次停哪"，
        // 不看有没有新回复，所以点进未读会话会把用户丢回第 42 条、让他自己往下翻。
        // 这条断言把"新旧确实不同"钉住，防止以后改回去。
        val oldRule = mid.takeIf { !it.followTail && it.index > 0 }
        check("★ 旧规则会回到第 42 条（正是要修掉的行为）",
            oldRule != null && oldRule.index == 42 && unread.followTail)

        // 判据本身：看**加载到的历史**里最后一条助手消息，而不是会话摘要缓存
        // （冷启动时缓存是空的 —— 真机验证就是栽在这里）
        fun msg(seq: Int, role: String) = ChatMessage(seq, 0L, role, "x", turn = 1)
        val hist = listOf(msg(80, "user"), msg(90, "assistant"), msg(95, "user"))
        check("历史里最后一条助手消息比水位线新 → true", hasNewRepliesInHistory(hist, 85L, false))
        check("水位线已到最新 → false", !hasNewRepliesInHistory(hist, 90L, false))
        check("水位线更超前（比如别处已标已读）→ false", !hasNewRepliesInHistory(hist, 200L, false))
        check("历史里没有助手消息 → false", !hasNewRepliesInHistory(listOf(msg(9, "user")), 0L, false))
        check("空历史 → false", !hasNewRepliesInHistory(emptyList(), 0L, false))
        check("★ 会话还在跑 → 一律看最新（哪怕历史还没定稿）",
            hasNewRepliesInHistory(hist, 999L, running = true))
        check("★ 冷启动也准：不依赖 activity 缓存", hasNewRepliesInHistory(hist, 0L, false))
    }

    /* ---------- 错误记录：只记"看起来是错"的，最新的在最上面 ---------- */

    run {
        check("上传失败 → 记", looksLikeError("传文件失败：timeout"))
        check("上传没拿到凭据 → 记", looksLikeError("上传没有拿到凭据，可能没传成功"))
        check("连接问题 → 记", looksLikeError("连不上 harness，重试中"))
        check("归档失败 → 记", looksLikeError("归档失败：这个会话还在电脑上跑着，先停止它再归档"))
        check("成功提示 → 不记", !looksLikeError("已归档：已从手机列表里移除（电脑上的会话日志仍然保留）"))
        check("普通提示 → 不记", !looksLikeError("已创建文件夹：nmmiw卡尔曼滤波rl"))
        check("附件成功提示 → 不记", !looksLikeError("已传到电脑：up-test.txt（33 B）"))
        check("空串 → 不记", !looksLikeError(""))

        var log = emptyList<String>()
        log = appendErrorLog(log, "10-16 10:21:03  传文件失败：timeout")
        log = appendErrorLog(log, "10-16 10:21:05  刷新失败：连接超时")
        check("★ 最新的在最上面", log.first().contains("刷新失败") && log.last().contains("传文件失败"), log.toString())
        check("空白行不进记录", appendErrorLog(log, "   ").size == log.size)

        // 上限：只留最近 keep 条（久了不删会把 prefs 撑大）
        var many = emptyList<String>()
        for (i in 1..60) many = appendErrorLog(many, "第 $i 条", keep = 50)
        check("★ 最多留 50 条", many.size == 50, many.size.toString())
        check("★ 留下的是最新的那批（第 60 条在最上面）", many.first() == "第 60 条" && many.last() == "第 11 条",
            "${many.first()} .. ${many.last()}")

        // 时间戳：带日期，跨天也能分清
        val ts = formatLogTime(1792117309624L)
        check("时间戳形如 MM-dd HH:mm:ss", Regex("\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(ts), ts)
    }

    /* ---------- 待发附件：跟草稿一起落盘（选中只落手机，发送才传） ---------- */

    run {
        val a1 = PendingAttachment(path = "/data/cache/draft/1234.bin", name = "Screenshot_10-05.jpg", bytes = 516275)
        val a2 = PendingAttachment(
            path = "/data/cache/draft/1235.bin",
            name = "报告 v2.pdf",
            bytes = 2048,
            receiptId = "sha256:abcdef",
        )
        val list = listOf(a1, a2)
        val text = formatAttachList(list)
        val back = parseAttachList(text)
        check("往返不丢条目", back.size == 2, back.size.toString())
        check("★ 名字里的中文与空格原样保留", back[1].name == "报告 v2.pdf", back[1].name)
        check("★ 已拿到的凭据也存下来（重发时不用再传一遍）", back[1].receiptId == "sha256:abcdef", back[1].receiptId ?: "null")
        check("没传过的凭据是 null", back[0].receiptId == null, back[0].receiptId.toString())
        check("字节数保真", back[0].bytes == 516275 && back[1].bytes == 2048)
        check("路径保真", back[0].path == a1.path)
        check("localId 取文件名（UI 用它当稳定标识）", back[0].localId == "1234.bin", back[0].localId)

        // 文件名里可能有制表符/换行 —— 不能把存档格式冲垮
        val nasty = PendingAttachment(path = "/x/1.bin", name = "a\tb\nc", bytes = 1)
        val round = parseAttachList(formatAttachList(listOf(nasty)))
        check("★ 文件名里的制表符/换行被换成空格（不破坏存档格式）",
            round.size == 1 && round[0].name == "a b c", round.firstOrNull()?.name ?: "空")
        check("空列表 → 空文本 → 空列表", formatAttachList(emptyList()) == "" && parseAttachList("").isEmpty())
        check("脏数据不炸", parseAttachList("乱七八糟\n只有\t两列").isEmpty())
    }

    /* ---------- 长文件名缩写（chip 里必须留得下 ✕） ---------- */

    run {
        val long = "Screenshot_2026-10-16-10-07-42-951_com.ss.android.ugc.aweme.jpg"
        val short = shortFileName(long)
        check("★ 长名字被缩短", short.length <= 30, short.length.toString())
        check("★ 保留开头（时间戳最有信息量）", short.startsWith("Screenshot_2026-10"), short)
        check("★ 保留扩展名（尾部截断会把 .jpg 吃掉）", short.endsWith(".jpg"), short)
        check("有省略号", short.contains("…"))
        check("短名字原样返回", shortFileName("a.jpg") == "a.jpg")
        check("正好到上限不改", shortFileName("x".repeat(30)) == "x".repeat(30))
        check("中文名一样处理", shortFileName("截屏 " + "字".repeat(40) + ".png").contains("…"))
    }

    println("\n==== UI 纯逻辑验收（状态文案 / 光标 / 复制 / 会话活动 / 仪表盘）：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}
