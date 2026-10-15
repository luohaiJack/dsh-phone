package com.dsh.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 够用的 Markdown 渲染器。
 *
 * 为什么不引第三方库：Compose 1.12 / AGP 9 这一代里，现成的 markdown 库
 * 大多还没跟上，版本一冲突就是整包编不过 —— 而这些渲染规则本身并不复杂。
 *
 * 支持：**粗体** / *斜体* / `行内代码` / ~~删除线~~ / [文字](链接) / 裸链接 /
 *       # 标题 / - 列表 / > 引用 / ``` 代码块 / --- 分隔线
 *
 * 链接用 Compose 原生的 LinkAnnotation.Url —— 不给 linkInteractionListener 时，
 * 点击会落到平台的 UriHandler，也就是系统默认浏览器，正是我们想要的。
 */

/* ----------------------------- 块级结构 ----------------------------- */

private sealed interface MdBlock {
    data class Para(val text: AnnotatedString) : MdBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    data class Bullet(val marker: String, val text: AnnotatedString) : MdBlock
    data class Quote(val text: AnnotatedString) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Table(
        val header: List<AnnotatedString>,
        val aligns: List<TextAlign>,
        val rows: List<List<AnnotatedString>>,
    ) : MdBlock
    data object Rule : MdBlock
}

/**
 * 文件链接用的私有 scheme。
 *
 * 不自己发 Intent：Compose 的链接点击最终落到 `LocalUriHandler`，
 * 所以在 MarkdownText 里换一个 UriHandler 就能把这类链接截走、
 * 普通的 http(s) 继续交给系统浏览器。
 */
const val FILE_SCHEME = "dshfile:"

private val DOC_EXT = setOf(
    "md", "markdown", "txt", "json", "yml", "yaml", "toml", "ini", "conf",
    "kt", "java", "py", "js", "ts", "tsx", "sh", "bash", "sql",
    "csv", "log", "xml", "html", "css", "go", "rs", "c", "h", "cpp",
)

/** 行内代码是不是"看起来像个文档路径"。要求带目录分隔符，避免把普通变量名变成链接。 */
private fun looksLikeDocPath(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 300) return false
    if (t.any { it.isWhitespace() }) return false
    if (!t.contains('/')) return false
    val name = t.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return false
    return name.substring(dot + 1).lowercase() in DOC_EXT
}

private val BULLET_RE = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
private val HEADING_RE = Regex("""^(#{1,6})\s+(.*)$""")
private val RULE_RE = Regex("""^\s*(-{3,}|\*{3,}|_{3,})\s*$""")

/** 按未转义的 `|` 拆一行，并去掉首尾的边框竖线。 */
private fun splitTableRow(line: String): List<String> {
    var body = line.trim()
    if (body.startsWith("|")) body = body.substring(1)
    if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c == '\\' && i + 1 < body.length && body[i + 1] == '|') {
            cur.append('|')
            i += 2
            continue
        }
        if (c == '|') {
            cells.add(cur.toString().trim())
            cur.setLength(0)
            i += 1
            continue
        }
        cur.append(c)
        i += 1
    }
    cells.add(cur.toString().trim())
    return cells
}

/** 分隔行：每格都是 :?-+:?（如 |---|---|）。有没有它，是判定表格的关键。 */
private fun parseTableDelimiter(line: String): List<TextAlign>? {
    if (!line.contains('-')) return null
    val cells = splitTableRow(line)
    if (cells.isEmpty()) return null
    val aligns = ArrayList<TextAlign>()
    for (cell in cells) {
        val c = cell.trim()
        if (!Regex(":?-+:?").matches(c)) return null
        aligns.add(
            when {
                c.startsWith(":") && c.endsWith(":") -> TextAlign.Center
                c.endsWith(":") -> TextAlign.End
                else -> TextAlign.Start
            },
        )
    }
    return aligns
}

/**
 * 把 markdown 切成块。用户消息与助手消息都可能带 markdown，
 * 而工具结果那种是纯文本，走不到这里。
 */
private fun parseBlocks(source: String, linkColor: Color): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val lines = source.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()

    fun flushPara() {
        if (para.isNotEmpty()) {
            blocks.add(MdBlock.Para(inline(para.toString().trim(), linkColor)))
            para.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()

        // 代码围栏
        if (line.trimStart().startsWith("```")) {
            flushPara()
            val buf = StringBuilder()
            i += 1
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                buf.append(lines[i]).append('\n')
                i += 1
            }
            i += 1 // 跳过收尾的 ```
            blocks.add(MdBlock.Code(buf.toString().trimEnd()))
            continue
        }

        if (line.isBlank()) {
            flushPara()
            i += 1
            continue
        }

        // 表格：当前行含 |，且下一行是分隔行
        if (line.contains('|') && i + 1 < lines.size) {
            val aligns = parseTableDelimiter(lines[i + 1])
            if (aligns != null) {
                val headerCells = splitTableRow(line)
                val cols = maxOf(headerCells.size, aligns.size)
                if (cols > 0) {
                    flushPara()
                    val head = ArrayList<AnnotatedString>()
                    for (c in 0 until cols) head.add(inline(headerCells.getOrElse(c) { "" }, linkColor))
                    val normAligns = ArrayList<TextAlign>()
                    for (c in 0 until cols) normAligns.add(aligns.getOrElse(c) { TextAlign.Start })

                    val rows = ArrayList<List<AnnotatedString>>()
                    var j = i + 2
                    while (j < lines.size) {
                        val rowLine = lines[j]
                        if (rowLine.isBlank() || !rowLine.contains('|')) break
                        val cells = splitTableRow(rowLine)
                        val row = ArrayList<AnnotatedString>()
                        for (c in 0 until cols) row.add(inline(cells.getOrElse(c) { "" }, linkColor))
                        rows.add(row)
                        j += 1
                    }
                    blocks.add(MdBlock.Table(head, normAligns, rows))
                    i = j
                    continue
                }
            }
        }

        if (RULE_RE.matches(line)) {
            flushPara()
            blocks.add(MdBlock.Rule)
            i += 1
            continue
        }

        HEADING_RE.find(line)?.let { m ->
            flushPara()
            val level = m.groupValues[1].length
            blocks.add(MdBlock.Heading(level, inline(m.groupValues[2].trim(), linkColor)))
            i += 1
            return@let
        }?.also { continue }

        BULLET_RE.find(line)?.let { m ->
            flushPara()
            val indent = m.groupValues[1].length
            val markerRaw = m.groupValues[2]
            // 有序列表保留原编号，无序统一成圆点（缩进用空格撑）
            val marker = if (markerRaw.first().isDigit()) markerRaw else "•"
            val prefix = "  ".repeat(indent / 2)
            blocks.add(MdBlock.Bullet(prefix + marker, inline(m.groupValues[3].trim(), linkColor)))
            i += 1
            return@let
        }?.also { continue }

        if (line.trimStart().startsWith(">")) {
            flushPara()
            val buf = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                buf.append(lines[i].trimStart().removePrefix(">").trim()).append('\n')
                i += 1
            }
            blocks.add(MdBlock.Quote(inline(buf.toString().trim(), linkColor)))
            continue
        }

        if (para.isNotEmpty()) para.append('\n')
        para.append(line.trim())
        i += 1
    }
    flushPara()
    return blocks
}

/* ----------------------------- 行内标记 ----------------------------- */

/**
 * 手写扫描而不是正则：行内标记互相嵌套、还夹着链接，
 * 一个正则里塞多组会很快就读不懂，也难调。
 */
private fun inline(source: String, linkColor: Color): AnnotatedString {
    val link = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
    // 文件链接用同一套视觉，但指向私有 scheme
    val fileLink = link
    return buildAnnotatedString {
        var i = 0
        val n = source.length
        while (i < n) {
            val rest = source.substring(i)

            // 行内代码：`code`（里面不再解析其它标记）
            if (source[i] == '`') {
                val end = source.indexOf('`', i + 1)
                if (end > i + 1) {
                    val code = source.substring(i + 1, end)
                    if (looksLikeDocPath(code)) {
                        // 像个文档路径 → 做成可点链接（走 FILE_SCHEME，由 UriHandler 截走）
                        withLink(LinkAnnotation.Url(FILE_SCHEME + code, fileLink)) {
                            pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                            append(code)
                            pop()
                        }
                    } else {
                        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                        append(code)
                        pop()
                    }
                    i = end + 1
                    continue
                }
            }

            // 粗斜体：***x*** 或 ___x___ —— 必须排在粗体之前，
            // 否则 "**" 会先吃掉前两个星号，留下字面的 "*x*"（实测过）
            if (rest.startsWith("***") || rest.startsWith("___")) {
                val tok = rest.substring(0, 3)
                val end = source.indexOf(tok, i + 3)
                if (end > i + 3) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(source.substring(i + 3, end))
                    pop()
                    i = end + 3
                    continue
                }
            }

            // 粗体：**x** 或 __x__
            if (rest.startsWith("**") || rest.startsWith("__")) {
                val tok = rest.substring(0, 2)
                val end = source.indexOf(tok, i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(source.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }

            // 删除线：~~x~~
            if (rest.startsWith("~~")) {
                val end = source.indexOf("~~", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    append(source.substring(i + 2, end))
                    pop()
                    i = end + 2
                    continue
                }
            }

            // 链接：[文字](url)
            if (source[i] == '[') {
                val mid = source.indexOf("](", i + 1)
                if (mid > i) {
                    val end = source.indexOf(')', mid + 2)
                    if (end > mid + 2) {
                        val label = source.substring(i + 1, mid)
                        val url = source.substring(mid + 2, end).trim()
                        if (isWebUrl(url)) {
                            withLink(LinkAnnotation.Url(url, link)) { append(label) }
                        } else {
                            append(label)
                        }
                        i = end + 1
                        continue
                    }
                }
            }

            // 裸链接
            if (rest.startsWith("http://") || rest.startsWith("https://")) {
                var end = i
                while (end < n && !source[end].isWhitespace() && source[end] !in "<>()[]「」（）") end += 1
                // 结尾的标点通常不属于链接
                while (end > i && source[end - 1] in ".,;:!?、。，；：！？") end -= 1
                val url = source.substring(i, end)
                if (end > i) {
                    withLink(LinkAnnotation.Url(url, link)) { append(url) }
                    i = end
                    continue
                }
            }

            // 斜体：*x* 或 _x_（排除 ** 与 __）
            if ((source[i] == '*' || source[i] == '_') &&
                !rest.startsWith("**") && !rest.startsWith("__")
            ) {
                val tok = source[i]
                val end = source.indexOf(tok, i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(source.substring(i + 1, end))
                    pop()
                    i = end + 1
                    continue
                }
            }

            append(source[i])
            i += 1
        }
    }
}

/** 只认 http/https —— 别的 scheme（intent:// javascript: 之类）不进链接。 */
private fun isWebUrl(url: String): Boolean =
    url.startsWith("http://") || url.startsWith("https://")

/* ------------------------------ 渲染 ------------------------------ */

@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onOpenFile: (String) -> Unit = {},
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val blocks = remember(text, linkColor) { parseBlocks(text, linkColor) }

    // 先把平台自带的 handler 取出来，再包一层：
    // dshfile: 走文档阅读页，其余（http/https）原样交给系统浏览器。
    val platformHandler = LocalUriHandler.current
    val handler = remember(platformHandler, onOpenFile) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(FILE_SCHEME)) onOpenFile(uri.removePrefix(FILE_SCHEME))
                else platformHandler.openUri(uri)
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides handler) {
    Column(modifier) {
        blocks.forEachIndexed { idx, block ->
            if (idx > 0) Spacer(Modifier.height(if (block is MdBlock.Para) 8.dp else 4.dp))
            when (block) {
                is MdBlock.Para -> Text(block.text, style = style, color = color)

                is MdBlock.Heading -> Text(
                    block.text,
                    style = style.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (block.level) {
                            1 -> style.fontSize * 1.35f
                            2 -> style.fontSize * 1.2f
                            else -> style.fontSize * 1.08f
                        },
                    ),
                    color = color,
                )

                is MdBlock.Bullet -> Row(Modifier.fillMaxWidth()) {
                    Text(
                        block.marker,
                        style = style,
                        color = color,
                        modifier = Modifier.width(if (block.marker.first().isDigit()) 26.dp else 16.dp),
                    )
                    Text(block.text, style = style, color = color, modifier = Modifier.weight(1f))
                }

                // height(IntrinsicSize.Min) 让左侧竖条跟着文字高度走
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        block.text,
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }

                is MdBlock.Table -> {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val cellStyle = style.copy(fontSize = 12.sp)
                    val headStyle = cellStyle.copy(fontWeight = FontWeight.Bold)
                    val padH = 8.dp
                    val minW = 48.dp
                    val maxCellW = 220.dp

                    // 按内容量出每列宽度：等分列宽会把长内容挤成一行几个字，
                    // 而各行各自"按内容撑开"又会导致列对不齐 —— 必须统一量一次。
                    val colWidths = remember(block, style, density, measurer) {
                        val maxPx = with(density) { maxCellW.roundToPx() }
                        val n = block.header.size
                        val px = IntArray(n)
                        fun consider(c: Int, a: AnnotatedString, st: TextStyle) {
                            if (c >= n) return
                            val r = measurer.measure(a, style = st, constraints = Constraints(maxWidth = maxPx))
                            if (r.size.width > px[c]) px[c] = r.size.width
                        }
                        block.header.forEachIndexed { c, a -> consider(c, a, headStyle) }
                        block.rows.forEach { row -> row.forEachIndexed { c, a -> consider(c, a, cellStyle) } }
                        px.map { p ->
                            val w = with(density) { p.toDp() } + padH * 2
                            w.coerceIn(minW, maxCellW + padH * 2)
                        }
                    }

                    val scroll = rememberScrollState()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scroll),
                    ) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(codeBg),
                        ) {
                            Row {
                                block.header.forEachIndexed { c, cell ->
                                    Text(
                                        cell,
                                        style = headStyle,
                                        color = color,
                                        textAlign = block.aligns[c],
                                        modifier = Modifier
                                            .width(colWidths[c])
                                            .padding(horizontal = padH, vertical = 6.dp),
                                    )
                                }
                            }
                            HorizontalDivider()
                            block.rows.forEachIndexed { r, row ->
                                Row {
                                    row.forEachIndexed { c, cell ->
                                        Text(
                                            cell,
                                            style = cellStyle,
                                            color = color,
                                            textAlign = block.aligns[c],
                                            modifier = Modifier
                                                .width(colWidths[c])
                                                .padding(horizontal = padH, vertical = 6.dp),
                                        )
                                    }
                                }
                                if (r < block.rows.size - 1) HorizontalDivider()
                            }
                        }
                    }
                }

                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(codeBg)
                        .padding(8.dp),
                ) {
                    Text(
                        block.code,
                        style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = color,
                    )
                }

                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
    }
}
