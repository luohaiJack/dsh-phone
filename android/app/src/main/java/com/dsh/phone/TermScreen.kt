package com.dsh.phone

/**
 * 手机端终端的**屏幕模型**（不是"一堆行"，是 cols × rows 的格子）。
 *
 * 为什么非要有它：vim / nano / htop / less 这类全屏程序不按"一行一行往下写"，
 * 它们靠光标寻址反复重绘同一块区域。只按 `\n` 切行的做法（本项目第一版）
 * 会把它们画成一片残影，而且**光标位置是未知的** ——
 * 于是手机也没法"自动滚到你正在编辑的那一行"，键盘一弹就把人盖住了。
 *
 * 支持范围（够跑 vim/nano/htop/less，不去追求 100% VT100）：
 *   - 光标寻址：CUP/HVP、A/B/C/D/E/F、G/CHA、d/VPA、CR/LF/BS/HT
 *   - 擦除：ED(J)、EL(K)、ECH(X)、删字符(P)、插字符(@)、删行(M)、插行(L)
 *   - 滚动：滚动区域 DECSTBM(r)、换行触发上滚、S/T
 *   - 备用屏：?1049 / ?1047 / ?47 —— 全屏程序进出靠它，主屏的历史留得住
 *   - 自动换行（?7，含"延迟换行"语义）、字符集 ESC(0 的制表符映射
 *   - SGR 颜色/粗体/下划线/反显（见 [SgrState]）
 *
 * 不支持（明确记在文档里）：鼠标上报、真正的 24 位光标形状、字符集切换的完整表。
 */

/** 宽字符占的第二格用这个哨兵标记（渲染时跳过）。 */
private const val WIDE_TAIL = '\u0000'

/** 一行 = 若干同样式片段（对外渲染用）。 */
typealias AnsiLine = List<AnsiSpan>

/** 默认样式（前景/背景都跟随 App 主题）。 */
internal val DEFAULT_STYLE = AnsiSpan("")

/**
 * SGR 状态机：`\e[1;38;2;255;0;0m` 这类参数 → 当前样式。
 *
 * 状态**跨行保留**，终端本来就这样。
 */
class SgrState {
    private var fg: Int? = null
    private var bg: Int? = null
    private var bold = false
    private var dim = false
    private var underline = false
    private var inverse = false

    fun style(): AnsiSpan = AnsiSpan("", fg, bg, bold, dim, underline, inverse)

    fun reset() {
        fg = null; bg = null
        bold = false; dim = false; underline = false; inverse = false
    }

    fun apply(params: String) {
        if (params.isEmpty()) { reset(); return }
        val parts = params.split(';')
        var i = 0
        while (i < parts.size) {
            val code = parts[i].toIntOrNull() ?: 0
            when {
                code == 0 -> reset()
                code == 1 -> bold = true
                code == 2 -> dim = true
                code == 3 -> Unit
                code == 4 -> underline = true
                code == 7 -> inverse = true
                code == 21 || code == 22 -> { bold = false; dim = false }
                code == 23 -> Unit
                code == 24 -> underline = false
                code == 27 -> inverse = false
                code in 30..37 -> fg = AnsiPalette.BASIC[code - 30]
                code == 39 -> fg = null
                code in 40..47 -> bg = AnsiPalette.BASIC[code - 40]
                code == 49 -> bg = null
                code in 90..97 -> fg = AnsiPalette.BASIC[8 + code - 90]
                code in 100..107 -> bg = AnsiPalette.BASIC[8 + code - 100]
                code == 38 || code == 48 -> {
                    val isFg = code == 38
                    val mode = parts.getOrNull(i + 1)?.toIntOrNull()
                    if (mode == 5) {
                        val idx = parts.getOrNull(i + 2)?.toIntOrNull() ?: 0
                        if (isFg) fg = AnsiPalette.color256(idx) else bg = AnsiPalette.color256(idx)
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
}

object AnsiPalette {
    /** 16 个基本色，按深色背景调过 —— 手机端就是深色终端。 */
    val BASIC = intArrayOf(
        0xFF3B4048.toInt(), // 0 黑（深底上不能真黑，否则看不见）
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

    /**
     * 字符占几列。
     *
     * 中文/韩文/全角是 2 列。不做完整的 wcwidth 表，只覆盖常用区段 ——
     * 差一列会让 `ls` 的列对不齐，但不会让程序崩。
     */
    fun charWidth(ch: Char): Int {
        val c = ch.code
        return when {
            c < 0x1100 -> 1
            c in 0x1100..0x115F -> 2   // 谚文字母
            c in 0x2E80..0xA4CF -> 2   // 中日韩部首、假名、汉字
            c in 0xA960..0xA97F -> 2
            c in 0xAC00..0xD7A3 -> 2   // 谚文音节
            c in 0xF900..0xFAFF -> 2   // 兼容汉字
            c in 0xFE30..0xFE6F -> 2   // 兼容形式
            c in 0xFF00..0xFF60 -> 2   // 全角
            c in 0xFFE0..0xFFE6 -> 2
            c in 0x1F300..0x1F64F -> 2 // emoji
            c in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }
}

/** DEC 特殊图形字符集（`ESC ( 0`）：vim 画窗口边框就靠它。 */
private fun lineDrawing(ch: Char): Char = when (ch) {
    '`' -> '◆'
    'a' -> '▒'
    'f' -> '°'
    'g' -> '±'
    'j' -> '┘'
    'k' -> '┐'
    'l' -> '┌'
    'm' -> '└'
    'n' -> '┼'
    'o' -> '⎺'
    'p' -> '⎻'
    'q' -> '─'
    'r' -> '⎼'
    's' -> '⎽'
    't' -> '├'
    'u' -> '┤'
    'v' -> '┴'
    'w' -> '┬'
    'x' -> '│'
    'y' -> '≤'
    'z' -> '≥'
    '{' -> 'π'
    '|' -> '≠'
    '}' -> '£'
    '~' -> '·'
    else -> ch
}

/**
 * 一块终端屏幕。
 *
 * 使用方式：`feed(文本)` 喂任意切分的输出（转义序列被切断也没关系），
 * 然后读 [scrollback] + [screenLines] 渲染，[cursorLine] 是光标在渲染列表里的下标。
 */
class TermScreen(val cols: Int, val rows: Int, private val scrollbackMax: Int = 1500) {

    private val cells = CharArray(cols * rows) { ' ' }
    private val styleIdx = IntArray(cols * rows)
    private val stylePool = ArrayList<AnsiSpan>().apply { add(DEFAULT_STYLE) }
    private val styleKeys = HashMap<AnsiSpan, Int>().apply { put(DEFAULT_STYLE, 0) }

    private val sgr = SgrState()

    var cursorCol = 0
        private set
    var cursorRow = 0
        private set
    var cursorVisible = true
        private set

    /** 全屏程序（vim/htop）在备用屏里跑；主屏的历史这时要留着。 */
    var usingAltScreen = false
        private set

    private var autowrap = true
    private var wrapPending = false
    private var scrollTop = 0
    private var scrollBottom = rows - 1
    private var g0LineDrawing = false
    private var savedCursor: Triple<Int, Int, Boolean>? = null
    private var altSavedCursor: Triple<Int, Int, Boolean>? = null

    private val savedChars = CharArray(cols * rows)
    private val savedStyles = IntArray(cols * rows)

    private val back = ArrayDeque<AnsiLine>()

    /** 每变一次 +1：App 拿它当"内容变了"的信号（不能只比对象引用）。 */
    var revision = 0L
        private set

    // ---- 解析状态（转义序列可能被 chunk 切断）----
    private val pending = StringBuilder()
    private var inPending = false

    /** 主屏滚出去的历史行（新→旧存在尾部）。 */
    val scrollback: List<AnsiLine> get() = back

    val scrollbackSize: Int get() = back.size

    /** 屏幕当前的 rows 行。 */
    fun screenLines(): List<AnsiLine> {
        val out = ArrayList<AnsiLine>(rows)
        for (r in 0 until rows) out.add(rowSpans(r))
        return out
    }

    /** 光标在（scrollback + screen）渲染列表里的下标；不可见时为 null。 */
    fun cursorLine(): Int? = if (cursorVisible) scrollbackSize + cursorRow else null

    fun cursorColumn(): Int = cursorCol

    fun clear() {
        java.util.Arrays.fill(cells, ' ')
        java.util.Arrays.fill(styleIdx, 0)
        back.clear()
        cursorCol = 0
        cursorRow = 0
        wrapPending = false
        scrollTop = 0
        scrollBottom = rows - 1
        inPending = false
        pending.setLength(0)
        sgr.reset()
        usingAltScreen = false
        cursorVisible = true
        revision += 1
    }

    private fun rowSpans(r: Int): AnsiLine {
        val out = ArrayList<AnsiSpan>(8)
        var c = 0
        while (c < cols) {
            val i = r * cols + c
            val ch = cells[i]
            if (ch == WIDE_TAIL) { c += 1; continue }
            val st = styleIdx[i]
            val sb = StringBuilder()
            var k = c
            while (k < cols) {
                val j = r * cols + k
                if (cells[j] == WIDE_TAIL || styleIdx[j] != st) break
                sb.append(cells[j])
                k += 1
            }
            // 行尾空白不留样式，省得整行都有背景色
            val text = sb.toString()
            if (text.isNotEmpty()) out.add(stylePool[st].copy(text = text))
            c = k
        }
        return out
    }

    private fun styleId(style: AnsiSpan): Int {
        styleKeys[style]?.let { return it }
        val id = stylePool.size
        stylePool.add(style)
        styleKeys[style] = id
        return id
    }

    // ------------------------------------------------------------------
    // 喂数据
    // ------------------------------------------------------------------

    fun feed(text: String) {
        if (text.isEmpty()) return
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inPending) {
                pending.append(ch)
                i += 1
                if (consumeEscape()) {
                    inPending = false
                    pending.setLength(0)
                }
                continue
            }
            when {
                ch == '\u001b' -> {
                    inPending = true
                    pending.append(ch)
                    i += 1
                }
                ch == '\n' -> { lineFeed(); i += 1 }
                ch == '\r' -> { cursorCol = 0; wrapPending = false; i += 1 }
                ch == '\b' -> {
                    if (cursorCol > 0) cursorCol -= 1
                    wrapPending = false
                    i += 1
                }
                ch == '\t' -> {
                    val next = ((cursorCol / 8) + 1) * 8
                    while (cursorCol < next && cursorCol < cols - 1) cursorCol += 1
                    wrapPending = false
                    i += 1
                }
                ch == '\u0007' -> i += 1
                ch.code < 0x20 -> i += 1
                else -> {
                    // 攒一段普通文本
                    val start = i
                    while (i < text.length) {
                        val c = text[i]
                        if (c == '\u001b' || c == '\n' || c == '\r' || c == '\b' ||
                            c == '\t' || c == '\u0007' || c.code < 0x20
                        ) break
                        i += 1
                    }
                    putText(text, start, i)
                }
            }
        }
        revision += 1
    }

    private fun putText(src: String, from: Int, to: Int) {
        for (i in from until to) {
            var ch = src[i]
            if (g0LineDrawing && ch.code in 0x60..0x7E) ch = lineDrawing(ch)
            putChar(ch)
        }
    }

    private fun putChar(ch: Char) {
        val w = AnsiPalette.charWidth(ch)
        if (wrapPending) {
            cursorCol = 0
            lineFeed()
            wrapPending = false
        }
        if (cursorCol + w > cols) {
            if (!autowrap) {
                cursorCol = cols - w
            } else {
                cursorCol = 0
                lineFeed()
            }
        }
        val id = styleId(sgr.style())
        val i = cursorRow * cols + cursorCol
        cells[i] = ch
        styleIdx[i] = id
        if (w > 1) {
            for (k in 1 until w) {
                if (cursorCol + k < cols) {
                    cells[i + k] = WIDE_TAIL
                    styleIdx[i + k] = id
                }
            }
        }
        val next = cursorCol + w
        if (next >= cols) {
            // 延迟换行：先停在最后一列，等下一个字符真来了再换
            cursorCol = cols - 1
            wrapPending = autowrap
        } else {
            cursorCol = next
        }
    }

    /** 换行：到底了就滚（滚出去的行进 scrollback）。 */
    private fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow += 1
        }
    }

    private fun scrollUp(n: Int) {
        for (k in 0 until n) {
            if (scrollTop == 0 && !usingAltScreen) captureTopLine()
            for (r in scrollTop until scrollBottom) {
                System.arraycopy(cells, (r + 1) * cols, cells, r * cols, cols)
                System.arraycopy(styleIdx, (r + 1) * cols, styleIdx, r * cols, cols)
            }
            blankRow(scrollBottom)
        }
    }

    private fun scrollDown(n: Int) {
        for (k in 0 until n) {
            for (r in scrollBottom downTo scrollTop + 1) {
                System.arraycopy(cells, (r - 1) * cols, cells, r * cols, cols)
                System.arraycopy(styleIdx, (r - 1) * cols, styleIdx, r * cols, cols)
            }
            blankRow(scrollTop)
        }
    }

    private fun captureTopLine() {
        val line = rowSpans(0)
        back.addLast(line)
        while (back.size > scrollbackMax) back.removeFirst()
    }

    private fun blankRow(r: Int) {
        val from = r * cols
        java.util.Arrays.fill(cells, from, from + cols, ' ')
        java.util.Arrays.fill(styleIdx, from, from + cols, 0)
    }

    private fun eraseInLine(mode: Int) {
        val from: Int
        val to: Int
        when (mode) {
            0 -> { from = cursorCol; to = cols }
            1 -> { from = 0; to = cursorCol + 1 }
            else -> { from = 0; to = cols }
        }
        val base = cursorRow * cols
        for (c in from until to.coerceAtMost(cols)) {
            cells[base + c] = ' '
            styleIdx[base + c] = 0
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) blankRow(r)
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) blankRow(r)
            }
            2 -> for (r in 0 until rows) blankRow(r)
            3 -> {
                for (r in 0 until rows) blankRow(r)
                back.clear()
            }
            else -> Unit
        }
    }

    private fun scrollRegion(top: Int, bottom: Int) {
        val t = top.coerceIn(0, rows - 1)
        val b = bottom.coerceIn(0, rows - 1)
        if (t < b) {
            scrollTop = t
            scrollBottom = b
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        cursorRow = scrollTop
        cursorCol = 0
        wrapPending = false
    }

    private fun setCursor(r: Int, c: Int) {
        cursorRow = r.coerceIn(0, rows - 1)
        cursorCol = c.coerceIn(0, cols - 1)
        wrapPending = false
    }

    private fun switchAlt(on: Boolean) {
        if (on == usingAltScreen) return
        if (on) {
            altSavedCursor = Triple(cursorRow, cursorCol, wrapPending)
            System.arraycopy(cells, 0, savedChars, 0, cells.size)
            System.arraycopy(styleIdx, 0, savedStyles, 0, styleIdx.size)
            java.util.Arrays.fill(cells, ' ')
            java.util.Arrays.fill(styleIdx, 0)
            usingAltScreen = true
        } else {
            System.arraycopy(savedChars, 0, cells, 0, cells.size)
            System.arraycopy(savedStyles, 0, styleIdx, 0, styleIdx.size)
            usingAltScreen = false
        }
        scrollTop = 0
        scrollBottom = rows - 1
        val saved = altSavedCursor
        if (on) {
            setCursor(0, 0)
        } else if (saved != null) {
            cursorRow = saved.first.coerceIn(0, rows - 1)
            cursorCol = saved.second.coerceIn(0, cols - 1)
            wrapPending = saved.third
        }
    }

    // ------------------------------------------------------------------
    // 转义序列
    // ------------------------------------------------------------------

    /** 返回 true 表示这个序列已经吃完了。 */
    private fun consumeEscape(): Boolean {
        val s = pending
        if (s.length < 2) return false
        return when (s[1]) {
            '[' -> consumeCsi(s)
            ']' -> consumeString(s, ']')          // OSC
            'P', '^', '_' -> consumeString(s, s[1]) // DCS / PM / APC
            '(' -> if (s.length >= 3) { g0LineDrawing = s[2] == '0'; true } else false
            ')' -> if (s.length >= 3) true else false
            '#' -> if (s.length >= 3) true else false
            else -> consumeTwoChar(s[1])
        }
    }

    private fun consumeTwoChar(c: Char): Boolean = when (c) {
        '7' -> { savedCursor = Triple(cursorRow, cursorCol, wrapPending); true }
        '8' -> {
            savedCursor?.let { setCursor(it.first, it.second) }
            true
        }
        'D' -> { lineFeed(); true }
        'E' -> { cursorCol = 0; lineFeed(); true }
        'M' -> { if (cursorRow == scrollTop) scrollDown(1) else if (cursorRow > 0) cursorRow -= 1; true }
        'c' -> { clear(); true }
        '=' , '>' -> true
        else -> true
    }

    /** OSC / DCS：吃到 BEL 或 ST(`ESC \`) 为止。 */
    private fun consumeString(s: StringBuilder, kind: Char): Boolean {
        var i = 2
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\u0007') return true
            if (ch == '\u001b' && i + 1 < s.length && s[i + 1] == '\\') return true
            i += 1
        }
        return false
    }

    private fun consumeCsi(s: StringBuilder): Boolean {
        var i = 2
        // 私有标记 ? > ! 之类
        var priv = ' '
        if (i < s.length && (s[i] == '?' || s[i] == '>' || s[i] == '<' || s[i] == '=')) {
            priv = s[i]
            i += 1
        }
        val paramStart = i
        while (i < s.length) {
            val ch = s[i]
            if (ch.code in 0x40..0x7E) {
                applyCsi(priv, s.substring(paramStart, i), ch)
                return true
            }
            i += 1
        }
        return false
    }

    private fun params(raw: String): List<Int> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Int>(4)
        for (p in raw.split(';')) {
            out.add(p.toIntOrNull() ?: 0)
        }
        return out
    }

    private fun applyCsi(priv: Char, raw: String, final: Char) {
        val p = params(raw)
        fun n(idx: Int, dflt: Int): Int {
            val v = p.getOrNull(idx) ?: return dflt
            return if (v == 0) dflt else v
        }
        if (priv == '?') {
            when (final) {
                'h' -> for (v in p) when (v) {
                    7 -> autowrap = true
                    25 -> cursorVisible = true
                    47, 1047, 1049 -> switchAlt(true)
                    else -> Unit
                }
                'l' -> for (v in p) when (v) {
                    7 -> autowrap = false
                    25 -> cursorVisible = false
                    47, 1047, 1049 -> switchAlt(false)
                    else -> Unit
                }
                else -> Unit
            }
            return
        }
        when (final) {
            'A' -> setCursor(cursorRow - n(0, 1), cursorCol)
            'B' -> setCursor(cursorRow + n(0, 1), cursorCol)
            'C' -> setCursor(cursorRow, cursorCol + n(0, 1))
            'D' -> setCursor(cursorRow, cursorCol - n(0, 1))
            'E' -> setCursor(cursorRow + n(0, 1), 0)
            'F' -> setCursor(cursorRow - n(0, 1), 0)
            'G', '`' -> setCursor(cursorRow, n(0, 1) - 1)
            'd' -> setCursor(n(0, 1) - 1, cursorCol)
            'H', 'f' -> setCursor(n(0, 1) - 1, n(1, 1) - 1)
            'J' -> eraseInDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseInLine(p.getOrNull(0) ?: 0)
            'L' -> {
                if (cursorRow in scrollTop..scrollBottom) {
                    val saveTop = scrollTop
                    val saveBottom = scrollBottom
                    scrollTop = cursorRow
                    scrollDown(n(0, 1))
                    scrollTop = saveTop
                    scrollBottom = saveBottom
                }
            }
            'M' -> {
                if (cursorRow in scrollTop..scrollBottom) {
                    val saveTop = scrollTop
                    val saveBottom = scrollBottom
                    scrollTop = cursorRow
                    scrollUp(n(0, 1))
                    scrollTop = saveTop
                    scrollBottom = saveBottom
                }
            }
            'P' -> {
                val cnt = n(0, 1)
                val base = cursorRow * cols
                for (c in cursorCol until cols) {
                    val src = c + cnt
                    cells[base + c] = if (src < cols) cells[base + src] else ' '
                    styleIdx[base + c] = if (src < cols) styleIdx[base + src] else 0
                }
            }
            '@' -> {
                val cnt = n(0, 1)
                val base = cursorRow * cols
                for (c in cols - 1 downTo cursorCol) {
                    val src = c - cnt
                    cells[base + c] = if (src >= cursorCol) cells[base + src] else ' '
                    styleIdx[base + c] = if (src >= cursorCol) styleIdx[base + src] else 0
                }
            }
            'X' -> {
                val cnt = n(0, 1)
                val base = cursorRow * cols
                for (c in cursorCol until (cursorCol + cnt).coerceAtMost(cols)) {
                    cells[base + c] = ' '
                    styleIdx[base + c] = 0
                }
            }
            'S' -> scrollUp(n(0, 1))
            'T' -> scrollDown(n(0, 1))
            'r' -> scrollRegion(n(0, 1) - 1, n(1, rows) - 1)
            's' -> { savedCursor = Triple(cursorRow, cursorCol, wrapPending) }
            'u' -> savedCursor?.let { setCursor(it.first, it.second) }
            'm' -> sgr.apply(raw)
            'h', 'l' -> Unit // 非私有模式的开关（? 之外的）忽略
            else -> Unit
        }
    }
}

/**
 * 渲染一行时要画的格子：字符 + 它落在第几列 + 样式 + 是不是光标那一格。
 *
 * 单独拎出来是为了**能测**：光标位置算错（尤其行内有中文时，
 * 一个汉字占两列、列号和字符数不是一回事）只有真跑一遍才知道，
 * 靠肉眼看屏幕很难发现偏了一格。
 */
class RenderCell(val ch: Char, val col: Int, val style: AnsiSpan, val onCursor: Boolean)

/**
 * 把一行拆成"带列号的格子"，并按 [cursorCol] 标出光标那一格。
 *
 * 光标停在行尾之后是常态（提示符后面、vim 的行尾），这时要先补空格再放光标块。
 */
fun renderCells(line: AnsiLine, cursorCol: Int?): List<RenderCell> {
    val out = ArrayList<RenderCell>(64)
    var col = 0
    for (span in line) {
        for (ch in span.text) {
            val w = AnsiPalette.charWidth(ch)
            val on = cursorCol != null && cursorCol >= col && cursorCol < col + w
            out.add(RenderCell(ch, col, span, on))
            col += w
        }
    }
    if (cursorCol != null) {
        var padded = col
        while (padded < cursorCol) {
            out.add(RenderCell(' ', padded, DEFAULT_STYLE, false))
            padded += 1
        }
        if (out.none { it.onCursor }) {
            out.add(RenderCell(' ', cursorCol, DEFAULT_STYLE, true))
        }
    }
    return out
}

/** 光标该画成什么样。 */
enum class CursorKind { NONE, SOLID, DIM }

/**
 * 这一行该不该画光标、画实心还是淡的。
 *
 * - 终端说"光标可见" → 实心块（正常情况，vim 编辑时就是它）；
 * - 终端说"隐藏" → 通常意味着程序正在重绘（vim 执行命令时会 `?25l`），
 *   命令行模式下不画（免得干扰）；但**全屏程序里仍然画一个淡的** ——
 *   否则用户会看到"vim 里没有光标"，然后盲操作。这是实测踩到的坑（见 §30）。
 */
fun cursorKind(index: Int, cursorLine: Int?, fullScreen: Boolean, visible: Boolean): CursorKind = when {
    cursorLine == null || index != cursorLine -> CursorKind.NONE
    visible -> CursorKind.SOLID
    fullScreen -> CursorKind.DIM
    else -> CursorKind.NONE
}

/**
 * 一行拼成纯文本（复制用）：**去掉行尾空白**，行内的空格原样保留。
 *
 * 去行尾是必须的：终端的每一行都是按列数补齐的，不去掉的话复制出来
 * 每条后面都拖一串空格，粘到别处全是空档。
 */
fun lineText(line: AnsiLine): String = line.joinToString("") { it.text }.trimEnd()

/**
 * 取 [from]..[to] 这些行的纯文本（含两端）。
 *
 * 越界会被夹住；`from > to` 时自动交换 —— 用户先点下面再点上面是常态，
 * 不该因此复制到空内容。
 */
fun linesText(lines: List<AnsiLine>, from: Int, to: Int): String {
    if (lines.isEmpty()) return ""
    var a = from
    var b = to
    if (a > b) { val t = a; a = b; b = t }
    a = a.coerceIn(0, lines.size - 1)
    b = b.coerceIn(0, lines.size - 1)
    val sb = StringBuilder()
    for (i in a..b) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(lineText(lines[i]))
    }
    return sb.toString()
}

/**
 * 从底部往上找 [n] 行**非空**的行，返回它们的行号区间（含两端）；找不到返回 null。
 *
 * 为什么不是"最后 n 行"：终端屏幕是 47 行，大部分是空白 ——
 * 直接取最后 3 行会得到 0 个字（实测踩到）。用户说"复制最近 3 行"，
 * 意思显然是**最近的 3 行内容**。中间夹着的空行会一并带上，保持版面可读。
 */
fun lastNonBlankRange(lines: List<AnsiLine>, n: Int): Pair<Int, Int>? {
    if (lines.isEmpty() || n <= 0) return null
    var first = -1
    var count = 0
    for (i in lines.indices.reversed()) {
        if (lineText(lines[i]).isEmpty()) continue
        if (first < 0) first = i
        count += 1
        if (count >= n) return i to first
    }
    return if (first >= 0) 0 to first else null
}
