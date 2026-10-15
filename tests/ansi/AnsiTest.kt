import com.dsh.phone.AnsiParser
import com.dsh.phone.TermBuffer

var pass = 0
var fail = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) { pass++; println("✓  $name $extra") } else { fail++; println("✗  $name $extra") }
}

/** 把 buffer 渲染成便于断言的纯文本（丢掉样式）。 */
fun plain(b: TermBuffer): String =
    (b.lines.map { line -> line.joinToString("") { it.text } } + listOf(b.currentLine.joinToString("") { it.text }))
        .joinToString("\n")

fun main() {
    /* 1. 基本文本与换行 */
    run {
        val b = TermBuffer()
        b.feed("hello\nworld\n")
        check("基本换行", plain(b) == "hello\nworld\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 2. SGR 颜色被解析且不出现在文本里 */
    run {
        val b = TermBuffer()
        b.feed("\u001b[31mred\u001b[0m plain\n")
        val line = b.lines[0]
        check("SGR 不着色到文本", plain(b).startsWith("red plain"), "得到=${plain(b)}")
        check("红色 span 拿到 fg", line[0].fg == AnsiParser.BASIC[1], "fg=${line[0].fg?.toString(16)}")
        check("reset 之后 fg 回到 null", line[1].fg == null, "fg=${line[1].fg}")
    }

    /* 3. 真彩色 + 粗体 */
    run {
        val b = TermBuffer()
        b.feed("\u001b[1;38;2;18;52;86mX\u001b[0m\n")
        val s = b.lines[0][0]
        check("真彩色解析", s.fg == 0xFF123456.toInt(), "fg=${s.fg?.toString(16)}")
        check("粗体解析", s.bold)
    }

    /* 4. 256 色 */
    run {
        val b = TermBuffer()
        b.feed("\u001b[38;5;196mY\u001b[0m\n")
        check("256 色索引 196 是红", b.lines[0][0].fg == AnsiParser.color256(196), "fg=${b.lines[0][0].fg?.toString(16)}")
    }

    /* 5. 转义序列被 chunk 切断 */
    run {
        val b = TermBuffer()
        b.feed("\u001b[38;2;")
        b.feed("255;0;0mZ\n")
        check("跨 chunk 的转义序列", plain(b) == "Z\n" && b.lines[0][0].fg == 0xFFFF0000.toInt(),
            "文本=${plain(b).trim()} fg=${b.lines[0][0].fg?.toString(16)}")
    }

    /* 6. OSC 标题/集成序列被丢掉（真机上 starship/shell 集成会发） */
    run {
        val b = TermBuffer()
        b.feed("\u001b]0;dev@dev:~\u0007prompt$ ")
        b.feed("\u001b]3008;start=abc;type=shell\u001b\\out\n")
        check("OSC 被丢掉", plain(b) == "prompt$ out\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 7. 私有模式 / 光标移动 / 清屏都被丢掉 */
    run {
        val b = TermBuffer()
        b.feed("\u001b[?2004h\u001b[2K\u001b[1G\u001b[?2004lclean\n")
        check("CSI 非 SGR 被丢掉", plain(b) == "clean\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 8. \r 重写当前行（进度条） */
    run {
        val b = TermBuffer()
        b.feed("progress 10%")
        b.feed("\rprogress 100%\n")
        check("\\r 重写当前行", plain(b) == "progress 100%\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 9. \b 退格 */
    run {
        val b = TermBuffer()
        b.feed("abc\bd\n")
        check("\\b 退格", plain(b) == "abd\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 10. 制表位展开到 8 列 */
    run {
        val b = TermBuffer()
        b.feed("a\tb\tc\n")
        check("tab 展开", plain(b) == "a       b       c\n", "得到=${plain(b).replace("\n", "\\n")}")
    }

    /* 11. 颜色跨行保留（终端语义） */
    run {
        val b = TermBuffer()
        b.feed("\u001b[32mline1\nline2\n")
        check("样式跨行保留", b.lines[1][0].fg == AnsiParser.BASIC[2], "fg=${b.lines[1][0].fg?.toString(16)}")
    }

    /* 12. 行数上限 */
    run {
        val b = TermBuffer(maxLines = 10)
        for (i in 1..50) b.feed("line$i\n")
        check("行数被封顶", b.lines.size == 10 && plain(b).startsWith("line41"), "size=${b.lines.size} 首行=${b.lines[0].joinToString("") { it.text }}")
    }

    /* 13. 真机抓下来的那一帧（含 OSC 3008 与真彩色提示符） */
    run {
        val raw = "\u001b]3008;start=82651773;type=shell;user=dev\u001b\\\u001b]0;dev@dev:~\u0007" +
            "\u001b[?2004h\r\n\u001b[38;2;208;188;255m dev \u001b[0m ~ $ \u001b[?2004l\r\n" +
            "\u001b[1;34mREADME.md\u001b[0m\r\n"
        val b = TermBuffer()
        b.feed(raw)
        val text = plain(b)
        check("真机帧没有残留转义符", !text.contains('\u001b'), "得到=${text.replace("\n", "\\n").take(90)}")
        check("真机帧文字正确", text.contains("~ $") && text.contains("README.md"))
    }


    println("\n==== ANSI/TermBuffer 验收：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}

