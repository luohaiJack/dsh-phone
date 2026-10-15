package com.dsh.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.phone.AnsiLine
import com.dsh.phone.TermState
import com.dsh.phone.shortPath

/** 终端底色固定用深色：ANSI 的 16 色是按深底配的，跟着系统主题走会读不清。 */
private val TermBg = Color(0xFF10131A)
private val TermFg = Color(0xFFD7DAE0)

/** 一行等宽字体的样式（列宽测量也必须用同一份）。 */
private val TermTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * 手机上的电脑终端。
 *
 * 交互模型是"命令行框"，不是逐键透传：
 * 上面是电脑的输出，下面是输入框 + 一排功能键。
 * 这样软键盘输入法（中文、联想、纠错）可以正常工作 ——
 * 逐键透传的话输入法根本没机会组词，中文命令就废了。
 *
 * 复制粘贴是这条链路的重点：在会话里长按复制一段命令 → 切到这里 →
 * 「粘贴并跑」。所以这两个按钮放在最显眼的位置，且不依赖输入框焦点。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    term: TermState,
    followTail: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onFollowTailChange: (Boolean) -> Unit,
    onEnsure: (cols: Int, rows: Int) -> Unit,
    onSend: (String) -> Unit,
    onSendRaw: (String) -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onKill: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    var note by remember { mutableStateOf<String?>(null) }

    // 一行等宽字的实际宽度（px）：PTY 的列数必须按它算，否则 `ls`、提示符全折行
    val charWidthPx = remember {
        val w = measurer.measure("0".repeat(40), TermTextStyle).size.width / 40f
        if (w <= 0f) 8f else w
    }
    val lineHeightPx = remember { with(density) { TermTextStyle.lineHeight.toPx() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        // 底部输入区 + 功能键占掉的高度，留给 PTY 的"行"要扣掉
        val cols = ((availW - with(density) { 16.dp.toPx() }) / charWidthPx).toInt().coerceIn(20, 200)
        val rows = ((availH - with(density) { 150.dp.toPx() }) / lineHeightPx).toInt().coerceIn(8, 100)
        LaunchedEffect(cols, rows) { onEnsure(cols, rows) }
    }

    // 跟着最新走：只有"用户这次是真的往上滑了"才停止跟随（见 §22 的三次教训）
    LaunchedEffect(listState) {
        var prevIdx = 0
        var prevOff = 0
        var wasScrolling = false
        snapshotFlow {
            Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { (inProgress, idx, off) ->
            if (inProgress) {
                wasScrolling = true
                val movedUp = idx < prevIdx || (idx == prevIdx && off < prevOff)
                if (movedUp) onFollowTailChange(false)
            } else if (wasScrolling) {
                wasScrolling = false
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                onFollowTailChange(last == null || last.index >= info.totalItemsCount - 1)
            }
            prevIdx = idx
            prevOff = off
        }
    }

    LaunchedEffect(term.rev, followTail) {
        if (!followTail) return@LaunchedEffect
        val total = term.lines.size + 1
        if (total > 1) runCatching { listState.scrollToItem(total - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text("终端", style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(term.statusText)
                                if (term.pid > 0) append(" · pid ").append(term.pid)
                                if (term.cwd.isNotBlank()) append(" · ").append(shortPath(term.cwd))
                                if (term.cols > 0) append(" · ").append(term.cols).append("×").append(term.rows)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onKill) {
                        Icon(Icons.Filled.Close, contentDescription = "关掉终端")
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
            // ---------------- 输出区 ----------------
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(TermBg),
            ) {
                when {
                    term.opening -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在电脑上开终端…", color = TermFg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    term.error != null -> Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                        Text(term.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    ) {
                        items(term.lines) { line -> TermLine(line) }
                        // 正在写的那一行（光标位置就在它的末尾）
                        item {
                            TermLine(term.currentLine, caret = !term.exited)
                        }
                    }
                }

                if (!followTail && term.lines.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { onFollowTailChange(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    ) { Text("↓ 回到最新") }
                }
            }

            note?.let {
                Text(
                    it,
                    modifier = Modifier.padding(start = 10.dp, top = 4.dp, end = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---------------- 功能键 ----------------
            //
            // 用 FlowRow 自动换行，**不要**用横向滚动的 Row：
            // 实测横向滚动容器会在启动时把第一个按钮挤出屏幕左侧，
            // 它的文字被裁掉（无障碍树里只剩一个 36px 的空按钮）。
            // 换行还能让所有键一眼可见 —— 藏在屏幕外的键等于没有。
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isEmpty()) {
                        note = "剪贴板是空的"
                    } else {
                        onDraftChange(text)
                        note = "已粘贴到输入框（${text.length} 字），点 ▶ 发送"
                    }
                }) { Text("粘贴") }

                FilledTonalButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isEmpty()) {
                        note = "剪贴板是空的"
                    } else {
                        onDraftChange("")
                        onSend(text)
                        note = "已把剪贴板的 ${text.length} 字发给终端"
                    }
                }) { Text("粘贴并跑") }

                KeyChip("清屏") { onClear() }
                KeyChip("Ctrl-C") { onInterrupt() }
                KeyChip("Tab") { onSendRaw("\t") }
                KeyChip("Esc") { onSendRaw("\u001b") }
                KeyChip("↑") { onSendRaw("\u001b[A") }
                KeyChip("↓") { onSendRaw("\u001b[B") }
                KeyChip("←") { onSendRaw("\u001b[D") }
                KeyChip("→") { onSendRaw("\u001b[C") }
                KeyChip("Ctrl-D") { onSendRaw("\u0004") }
                KeyChip("Ctrl-L") { onSendRaw("\u000c") }
            }

            // ---------------- 命令行输入 ----------------
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChange(it); note = null },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("在电脑上执行…（可长按粘贴）") },
                    textStyle = TermTextStyle,
                    singleLine = false,
                    maxLines = 4,
                )
                IconButton(
                    onClick = { onSend(draft); onDraftChange("") },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送到终端")
                }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 把一行 AnsiSpan 画成等宽文本。 */
@Composable
private fun TermLine(line: AnsiLine, caret: Boolean = false) {
    val text = buildAnnotatedString {
        line.forEach { span ->
            val style = SpanStyle(
                color = span.fg?.let { Color(it) } ?: TermFg,
                background = span.bg?.let { Color(it) } ?: Color.Unspecified,
                fontWeight = if (span.bold) FontWeight.Bold else null,
                textDecoration = if (span.underline) TextDecoration.Underline else null,
            )
            withStyle(style) { append(span.text) }
        }
        if (caret) append("▌")
    }
    Text(
        text = text,
        style = TermTextStyle,
        softWrap = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
