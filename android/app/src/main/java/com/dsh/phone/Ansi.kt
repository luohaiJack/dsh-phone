package com.dsh.phone

/**
 * 手机端终端的文本层：把 PTY 出来的原始字节流变成"一行行带样式的文本"。
 *
 * 这里**不是**一个终端模拟器，而是一个"够用的折中"：
 *
 * - **认** SGR（粗体 / 暗色 / 下划线 / 反显，16 色、256 色、真彩色）——
 *   因为 `ls --color`、`git status`、提示符的可读性全靠它；
 * - **丢** 光标移动、清屏、OSC 标题、私有模式（`?2004h` 之类）——
 *   全屏程序（vim/htop）因此会显示成重绘残影，这是已知取舍（见文档）；
 * - `\r` 当作"重写当前行"（进度条、readline 重绘都靠它），
 *   `\b` 退一格，`\t` 按 8 列制表位展开。
 *
 * 样式状态是**跨行保留**的 —— 终端本来就这样（`echo -e '\e[31m'` 之后
 * 后续所有输出都变红，直到 reset）。
 */

/** 一段同样式的文本。null 表示"用主题默认色"。 */
data class AnsiSpan(
    val text: String,
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
)

/** 一行 = 若干段。 */
typealias AnsiLine = List<AnsiSpan>

/**
 * 增量解析器。
 *
 * 关键点：**转义序列可能被 chunk 切断**（一次 read 刚好切在 `\e[38;2;` 中间），
 * 所以半截序列必须留着，等下一块数据到了再接上继续解析。
 */
class AnsiParser {

    private val pending = StringBuilder()
    private var inPending = false

    // 当前 SGR 状态
    private var fg: Int? = null
    private var bg: Int? = null
    private var bold = false
    private var dim = false
    private var underline = false
    private var inverse = false

    /** 有样式状态（给"当前行"用）。 */
    fun reset() {
        pending.setLength(0)
        inPending = false
        fg = null; bg = null
        bold = false; dim = false; underline = false; inverse = false
    }

    private fun style() = AnsiSpan("", fg, bg, bold, dim, underline, inverse)

    /**
     * 喂一块输出，返回它对**当前行**的编辑动作。
     *
     * 不在这里切行 —— 切行和"当前行"由 [TermBuffer] 管，这里只管字符与样式。
     */
    fun consume(
        text: String,
        onText: (String, AnsiSpan) -> Unit,
        onNewline: () -> Unit,
        onCarriageReturn: () -> Unit,
        onBackspace: () -> Unit,
        onBell: () -> Unit,
    ) {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (inPending) {
                pending.append(c)
                i += 1
                if (feedPending(onBell)) {
                    inPending = false
                    pending.setLength(0)
                }
                continue
            }
            when {
                c == '\u001b' -> {
                    inPending = true
                    pending.append(c)
                    i += 1
                }
                c == '\n' -> { onNewline(); i += 1 }
                c == '\r' -> { onCarriageReturn(); i += 1 }
                c == '\b' -> { onBackspace(); i += 1 }
                c == '\u0007' -> { onBell(); i += 1 }
                c == '\t' -> { onText("\t", style()); i += 1 }
                c.code < 0x20 -> i += 1 // 其余控制字符直接丢
                else -> {
                    // 攒一段普通文本一起回调，减少对象分配
                    val start = i
                    while (i < n) {
                        val ch = text[i]
                        if (ch == '\u001b' || ch == '\n' || ch == '\r' || ch == '\b' ||
                            ch == '\u0007' || ch == '\t' || ch.code < 0x20
                        ) break
                        i += 1
                    }
                    onText(text.substring(start, i), style())
                }
            }
        }
    }

    /** 返回 true 表示这个转义序列已经吃完了。 */
    private fun feedPending(onBell: () -> Unit): Boolean {
        val s = pending
        if (s.length < 2) return false
        when (s[1]) {
            '[' -> {
                // CSI：参数字节 0x30-0x3F，中间字节 0x20-0x2F，终止字节 0x40-0x7E
                var i = 2
                while (i < s.length) {
                    val ch = s[i]
                    if (ch.code in 0x40..0x7E) {
                        if (ch == 'm') applySgr(s.substring(2, i))
                        return true
                    }
                    i += 1
                }
                return false
            }
            ']' -> {
                // OSC：到 BEL 或 ST(ESC \) 为止
                var i = 2
                while (i < s.length) {
                    val ch = s[i]
                    if (ch == '\u0007') return true
                    if (ch == '\u001b' && i + 1 < s.length && s[i + 1] == '\\') return true
                    i += 1
                }
                return false
            }
            else -> {
                // 两字符序列（\e= \e> \e( 之类）：吃掉两个字符走人
                if (s[1] == '(' || s[1] == ')' || s[1] == '#') return s.length >= 3
                return true
            }
        }
    }

    /** SGR：`1;38;2;255;0;0m` 这种。 */
    private fun applySgr(params: String) {
        if (params.isEmpty()) { resetStyle(); return }
        val parts = params.split(';')
        var i = 0
        while (i < parts.size) {
            val code = parts[i].toIntOrNull() ?: 0
            when {
                code == 0 -> resetStyle()
                code == 1 -> bold = true
                code == 2 -> dim = true
                code == 3 -> { /* 斜体：Compose 端不做，忽略 */ }
                code == 4 -> underline = true
                code == 7 -> inverse = true
                code == 21 || code == 22 -> { bold = false; dim = false }
                code == 23 -> Unit
                code == 24 -> underline = false
                code == 27 -> inverse = false
                code in 30..37 -> fg = BASIC[code - 30]
                code == 39 -> fg = null
                code in 40..47 -> bg = BASIC[code - 40]
                code == 49 -> bg = null
                code in 90..97 -> fg = BASIC[8 + code - 90]
                code in 100..107 -> bg = BASIC[8 + code - 100]
                code == 38 || code == 48 -> {
                    val isFg = code == 38
                    val mode = parts.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5) {
                        val idx = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        if (isFg) fg = color256(idx) else bg = color256(idx)
                        i += 2
                    } else if (mode == 2) {
                        val r = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        val g = parts.getOrNull(i + 3)?.toIntOrNull() ?: 0
                        val b = parts.getOrNull(i + 4)?.toIntOrNull() ?: 0
                        val c = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
                        if (isFg) fg = c else bg = c
                        i += 4
                    }
                }
            }
            i += 1
        }
    }

    private fun resetStyle() {
        fg = null; bg = null
        bold = false; dim = false; underline = false; inverse = false
    }

    companion object {
        /** 16 个基本色，按深色背景调过 —— 手机端就是深色终端。 */
        val BASIC = intArrayOf(
            0xFF3B4048.toInt(), // 0 黑（深色底上不能真黑，否则看不见）
            0xFFE06C75.toInt(), // 1 红
            0xFF98C379.toInt(), // 2 绿
            0xFFE5C07B.toInt(), // 3 黄
            0xFF61AFEF.toInt(), // 4 蓝
            0xFFC678DD.toInt(), // 5 品红
            0xFF56B6C2.toInt(), // 6 青
            0xFFABB2BF.toInt(), // 7 白
            0xFF5C6370.toInt(), // 8 亮黑
            0xFFFF7B86.toInt(), // 9 亮红
            0xFFB6E38A.toInt(), // 10 亮绿
            0xFFFFD68A.toInt(), // 11 亮黄
            0xFF7CC4FF.toInt(), // 12 亮蓝
            0xFFD9A0F0.toInt(), // 13 亮品红
            0xFF6FD3DF.toInt(), // 14 亮青
            0xFFE6E6E6.toInt(), // 15 亮白
        )

        /** 256 色：0-15 走基本色，16-231 是 6×6×6 立方，232-255 是灰阶。 */
        fun color256(idx: Int): Int {
            if (idx < 0) return BASIC[7]
            if (idx < 16) return BASIC[idx]
            if (idx < 232) {
                val v = idx - 16
                val r = v / 36
                val g = (v % 36) / 6
                val b = v % 6
                fun ch(x: Int) = if (x == 0) 0 else 55 + x * 40
                return 0xFF shl 24 or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            val level = 8 + (idx - 232) * 10
            return 0xFF shl 24 or (level shl 16) or (level shl 8) or level
        }
    }
}

/**
 * 一块终端的"屏幕"：已完成的行 + 正在写的那一行。
 *
 * 只追加、只在末尾改，所以发布快照时外层列表可以共享（见 [AppViewModel] 的 rev）。
 */
class TermBuffer(private val maxLines: Int = 1200) {

    private val parser = AnsiParser()
    private val done = ArrayList<AnsiLine>()
    private var current = ArrayList<AnsiSpan>()
    private var col = 0

    /**
     * 刚收到一个 \r，还没有新字符写上来。
     *
     * 为什么不能"见到 \r 就清行"：PTY 的行尾是 **CRLF** ——
     * `文字\r\n` 里的 \r 在 \n **之前**，此时清行就把这一行整条抹掉了
     * （实测症状：真机每行都是空的，最后只剩光标）。
     * 终端里 \r 只是"光标回第 0 列"，真正覆盖旧内容的是**随后写过来的字符**。
     * 所以这里只在"回列之后又来了新字符"时才清行，进度条重绘照旧正确。
     */
    private var crPending = false

    val lines: List<AnsiLine> get() = done
    val currentLine: AnsiLine get() = current
    val lineCount: Int get() = done.size

    fun clear() {
        parser.reset()
        done.clear()
        current = ArrayList()
        col = 0
        crPending = false
    }

    /** 喂一块输出。返回 true 表示屏幕内容有变化。 */
    fun feed(text: String): Boolean {
        if (text.isEmpty()) return false
        parser.consume(
            text = text,
            onText = { t, st -> append(t, st) },
            onNewline = { newline() },
            onCarriageReturn = { crPending = true; col = 0 },
            onBackspace = { backspace() },
            onBell = { },
        )
        return true
    }

    private fun append(text: String, style: AnsiSpan) {
        if (text.isEmpty()) return
        // 回车之后写来的第一个字符：这才是"覆盖旧行"的时刻（见 crPending 的说明）
        if (crPending) {
            current = ArrayList()
            crPending = false
        }
        val piece = if (text.indexOf('\t') >= 0) expandTabs(text) else text
        val last = current.lastOrNull()
        if (last != null && sameStyle(last, style)) {
            current[current.size - 1] = last.copy(text = last.text + piece)
        } else {
            current.add(style.copy(text = piece))
        }
        col += piece.length
    }

    /** 制表位按 8 列展开：`ls -l`、表格、Makefile 输出全靠它对齐。 */
    private fun expandTabs(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            if (ch != '\t') {
                sb.append(ch)
                continue
            }
            val pad = 8 - (col + sb.length) % 8
            repeat(pad) { sb.append(' ') }
        }
        return sb.toString()
    }

    private fun newline() {
        crPending = false
        // 行尾的空段没意义，去掉（否则每行都多一个空 Span）
        val line: AnsiLine = if (current.isEmpty()) emptyList() else ArrayList(current)
        done.add(line)
        while (done.size > maxLines) done.removeAt(0)
        current = ArrayList()
        col = 0
    }

    private fun backspace() {
        if (current.isEmpty()) return
        val last = current[current.size - 1]
        if (last.text.length <= 1) current.removeAt(current.size - 1)
        else current[current.size - 1] = last.copy(text = last.text.dropLast(1))
        if (col > 0) col -= 1
    }

    /** 两个 span 的样式是否一样（合并相邻同样式片段，少生成一堆对象）。 */
    private fun sameStyle(a: AnsiSpan, b: AnsiSpan): Boolean =
        a.fg == b.fg && a.bg == b.bg && a.bold == b.bold &&
            a.dim == b.dim && a.underline == b.underline && a.inverse == b.inverse
}
