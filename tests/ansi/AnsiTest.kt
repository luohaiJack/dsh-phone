import com.dsh.phone.AnsiPalette
import com.dsh.phone.TermScreen

var pass = 0
var fail = 0
fun check(name: String, ok: Boolean, extra: String = "") {
    if (ok) { pass++; println("✓  $name $extra") } else { fail++; println("✗  $name $extra") }
}

/** 屏幕可视内容：每行去掉右侧空白，方便断言。 */
fun plain(s: TermScreen): String =
    s.screenLines().joinToString("\n") { line -> line.joinToString("") { it.text }.trimEnd() }

/** 渲染列表（历史 + 屏幕），光标下标就在这个坐标系里。 */
fun rendered(s: TermScreen): List<String> =
    (s.scrollback + s.screenLines()).map { line -> line.joinToString("") { it.text }.trimEnd() }

fun main() {
    /* 1. 基本输出与换行（真 PTY 的行尾是 CRLF） */
    run {
        val s = TermScreen(20, 5)
        s.feed("hello\r\nworld\r\n")
        check("基本换行", plain(s) == "hello\nworld\n\n\n", "得到=${plain(s).replace("\n", "\\n")}")
        check("光标回到第 3 行行首", s.cursorRow == 2 && s.cursorCol == 0, "row=${s.cursorRow} col=${s.cursorCol}")
    }

    /* 1b. 裸 LF 的语义：只下移一行、**保留列**（VT 就是这样， 才是回列）。
           真终端里程序输出会被行规程的 ONLCR 翻译成 CRLF，所以这条平时遇不到；
           写下来是为了防止以后有人"顺手"把 LF 改成换行 —— 那会让全屏程序错位。 */
    run {
        val s = TermScreen(20, 3)
        s.feed("ab\ncd")
        check("裸 LF 只下移不回列", plain(s).lines()[0] == "ab" && plain(s).lines()[1] == "  cd",
            plain(s).replace("\n", "\\n"))
    }

    /* 2. ★ CRLF：\r 在 \n 之前，不能把这一行抹掉（真机上踩过的坑） */
    run {
        val s = TermScreen(20, 4)
        s.feed("line1\r\nline2\r\n")
        check("CRLF 不丢内容", plain(s).startsWith("line1\nline2"), "得到=${plain(s).replace("\n", "\\n")}")
    }

    /* 3. \r 用于进度条重绘：回列之后新写的字符覆盖旧内容 */
    run {
        val s = TermScreen(20, 3)
        s.feed("progress 10%")
        s.feed("\rprogress 100%")
        check("\\r 覆盖当前行", plain(s).startsWith("progress 100%"), "得到=${plain(s).lines()[0]}")
    }

    /* 4. 光标寻址：vim 就是靠这个把字画到指定格子 */
    run {
        val s = TermScreen(20, 5)
        s.feed("\u001b[2;3HABC")
        val lines = plain(s).split("\n")
        check("CUP 定位到第 2 行第 3 列", lines[1] == "  ABC", "得到=${lines[1]}")
        check("光标在 ABC 之后", s.cursorRow == 1 && s.cursorCol == 5, "row=${s.cursorRow} col=${s.cursorCol}")
    }

    /* 5. 擦除 */
    run {
        val s = TermScreen(20, 4)
        s.feed("abcdefghij")
        s.feed("\u001b[1;4H\u001b[K")     // 光标到第 1 行第 4 列，擦到行尾
        check("EL(0) 擦到行尾", plain(s).lines()[0] == "abc", "得到=${plain(s).lines()[0]}")
        s.feed("\u001b[2J")
        check("ED(2) 清屏", plain(s).trim().isEmpty(), "得到=${plain(s).trim()}")
    }

    /* 6. 备用屏（vim/htop）：进去再出来，主屏内容要还在 */
    run {
        val s = TermScreen(20, 4)
        s.feed("主屏的内容\r\n")
        s.feed("\u001b[?1049h\u001b[2J\u001b[H全屏程序")
        check("进备用屏后是空的+新内容", plain(s).startsWith("全屏程序") && !plain(s).contains("主屏"), "得到=${plain(s).replace("\n", "\\n")}")
        check("usingAltScreen = true", s.usingAltScreen)
        s.feed("\u001b[?1049l")
        check("退出备用屏后主屏恢复", plain(s).startsWith("主屏的内容") && !s.usingAltScreen,
            "得到=${plain(s).replace("\n", "\\n")}")
    }

    /* 7. 滚动区域里的换行：vim 分屏就靠它 */
    run {
        val s = TermScreen(20, 5)
        for (i in 1..5) s.feed("L$i\r\n")
        // 主屏滚出去的行进 scrollback
        check("滚出去的行进历史", s.scrollbackSize == 1 && rendered(s)[0] == "L1",
            "历史=${s.scrollbackSize} 首行=${rendered(s).firstOrNull()}")
        check("屏幕上剩 L2..L5", rendered(s).drop(1).take(4).joinToString(",") == "L2,L3,L4,L5",
            rendered(s).joinToString(","))
    }

    /* 8. 插入行 / 删除行（vim 的 o / dd 会用到） */
    run {
        val s = TermScreen(10, 4)
        s.feed("A\r\nB\r\nC\r\nD")
        s.feed("\u001b[2;1H\u001b[L")     // 第 2 行处插一行 → 下面整体下移
        val after = plain(s).split("\n")
        check("IL 插入行", after[0] == "A" && after[1] == "" && after[2] == "B", after.joinToString("|"))
        s.feed("\u001b[2;1H\u001b[M")     // 再删掉那一行
        val back = plain(s).split("\n")
        check("DL 删除行", back[0] == "A" && back[1] == "B", back.joinToString("|"))
    }

    /* 9. 擦字符 / 插字符 / 删字符 */
    run {
        val s = TermScreen(10, 2)
        s.feed("abcdef")
        s.feed("\u001b[1;2H\u001b[2P")     // 从第 2 列删 2 个 → a 后面接 def
        check("DCH 删字符", plain(s).lines()[0] == "adef", "得到=${plain(s).lines()[0]}")
        s.feed("\u001b[1;2H\u001b[2@")     // 插 2 个空格
        check("ICH 插字符", plain(s).lines()[0].startsWith("a  def"), "得到=${plain(s).lines()[0]}")
    }

    /* 10. 自动换行 + 延迟换行 */
    run {
        val s = TermScreen(5, 3)
        s.feed("abcdefg")
        val lines = plain(s).split("\n")
        check("满行自动折到下一行", lines[0] == "abcde" && lines[1] == "fg", lines.joinToString("|"))
    }

    /* 11. 关闭自动换行时原地覆盖 */
    run {
        val s = TermScreen(5, 2)
        s.feed("\u001b[?7labcdefg")
        check("?7l 关掉自动换行", plain(s).lines()[0] == "abcdg", "得到=${plain(s).lines()[0]}")
    }

    /* 12. 宽字符占两列（中文对齐全靠它） */
    run {
        val s = TermScreen(10, 2)
        s.feed("中文x")
        check("宽字符算两列", s.cursorCol == 5, "col=${s.cursorCol}")
        check("宽字符渲染不乱", plain(s).lines()[0] == "中文x", "得到=${plain(s).lines()[0]}")
    }

    /* 13. 光标可见性 + 光标下标（界面靠它自动滚到正在编辑的那一行） */
    run {
        val s = TermScreen(20, 6)
        s.feed("a\r\nb\r\nc\r\n")
        check("光标行可知", s.cursorLine() == 3, "cursorLine=${s.cursorLine()}")
        s.feed("\u001b[?25l")
        check("?25l 隐藏光标", s.cursorLine() == null)
        s.feed("\u001b[?25h")
        check("?25h 恢复光标", s.cursorLine() != null)
    }

    /* 14. 颜色：真彩色 / 256 色 / 粗体 / 跨行保留 */
    run {
        val s = TermScreen(20, 4)
        s.feed("\u001b[1;38;2;18;52;86mX\u001b[0m\r\n")
        val cell = s.screenLines()[0][0]
        check("真彩色解析", cell.fg == 0xFF123456.toInt(), "fg=${cell.fg?.toString(16)}")
        check("粗体解析", cell.bold)
        s.feed("\u001b[32mline1\r\nline2\r\n")
        check("样式跨行保留", s.screenLines()[1][0].fg == AnsiPalette.BASIC[2], "fg=${s.screenLines()[1][0].fg}")
        s.feed("\u001b[38;5;196m")
        s.feed("Y\u001b[0m\r\n")
        check("256 色", s.screenLines()[2][0].fg == AnsiPalette.color256(196))
    }

    /* 15. 转义序列被 chunk 切断 */
    run {
        val s = TermScreen(10, 2)
        s.feed("\u001b[38;2;")
        s.feed("255;0;0mZ")
        check("跨 chunk 的转义序列", s.screenLines()[0][0].text == "Z" && s.screenLines()[0][0].fg == 0xFFFF0000.toInt(),
            "文本=${s.screenLines()[0][0].text} fg=${s.screenLines()[0][0].fg?.toString(16)}")
    }

    /* 16. OSC / 私有模式 / 非 SGR 的 CSI 全部丢掉 */
    run {
        val s = TermScreen(30, 3)
        s.feed("\u001b]0;title\u0007\u001b]3008;start=abc\u001b\\\u001b[?2004h\u001b[?2004l\u001b[1Gclean")
        check("OSC 与私有模式被丢掉", plain(s).lines()[0] == "clean", "得到=${plain(s).lines()[0]}")
    }

    /* 17. 制表位 */
    run {
        val s = TermScreen(20, 2)
        s.feed("a\tb")
        check("tab 到第 8 列", s.cursorCol == 9 && plain(s).lines()[0] == "a       b",
            "col=${s.cursorCol} 行=${plain(s).lines()[0]}")
    }

    /* 18. 退格 */
    run {
        val s = TermScreen(10, 2)
        s.feed("abc\bd")
        check("退格不删字符只移光标", plain(s).lines()[0] == "abd", "得到=${plain(s).lines()[0]}")
    }

    /* 19. 行绘制字符集 ESC(0（vim 的窗口边框） */
    run {
        val s = TermScreen(10, 2)
        s.feed("\u001b(0lqk\u001b(B")
        check("ESC(0 映射成制表符", plain(s).lines()[0] == "┌─┐", "得到=${plain(s).lines()[0]}")
    }

    /* 20. 一段真机抓下来的 vim 开场（备用屏 + 光标寻址 + 颜色） */
    run {
        val raw = "\u001b[?1049h\u001b[22;0;0t\u001b[?1h\u001b=\r\n" +
            "\u001b[?2004h\u001b[1;24r\u001b[H\u001b[2J\u001b[?25l" +
            "\u001b[24;1H\u001b[1;32m\"test.md\" 3L, 12B\u001b[0m" +
            "\u001b[1;1Hhello vim\r\n\u001b[2;1H第二行中文\r\n\u001b[3;1Hthird line" +
            "\u001b[1;6H\u001b[?25h\u001b[?2004l"
        val s = TermScreen(40, 24)
        s.feed(raw)
        check("真机 vim 帧：备用屏", s.usingAltScreen)
        check("真机 vim 帧：文字对", rendered(s)[0] == "hello vim" && rendered(s)[1] == "第二行中文",
            rendered(s).take(3).joinToString("|"))
        check("真机 vim 帧：知道光标在哪一行", s.cursorLine() == 0 && s.cursorColumn() == 5,
            "line=${s.cursorLine()} col=${s.cursorColumn()}")
        check("真机 vim 帧：没有残留转义符", !rendered(s).joinToString("").contains('\u001b'))
    }

    /* 21. 清屏也要清历史 */
    run {
        val s = TermScreen(10, 3)
        for (i in 1..6) s.feed("x$i\r\n")
        check("先有历史", s.scrollbackSize > 0, "历史=${s.scrollbackSize}")
        s.feed("\u001b[3J")
        check("ED(3) 清掉历史", s.scrollbackSize == 0, "历史=${s.scrollbackSize}")
    }

    println("\n==== TermScreen 验收：$pass 项通过，$fail 项失败 ====")
    if (fail > 0) throw RuntimeException("有失败项")
}
