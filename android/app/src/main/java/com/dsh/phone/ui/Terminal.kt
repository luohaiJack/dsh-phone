package com.dsh.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
 * 两套输入方式，按电脑那边"是不是在跑全屏程序"自动切：
 *
 * | | 命令行模式（默认） | 全屏模式（vim / htop / less） |
 * | --- | --- | --- |
 * | 输入 | 输入框写好一行再发送 | **逐键直通**：敲一个字就进 vim |
 * | 理由 | 输入法要能组词、联想、纠错 | 全屏程序要的是按键本身 |
 * | 按键条 | 粘贴 / 粘贴并跑 / Ctrl-C / Tab / 方向键 | 再加 Esc / ⏎ / ⌫ / Ctrl 锁存 |
 *
 * 光标：屏幕模型知道光标在第几行（[TermState.cursorLine]），滚动跟着它走 ——
 * 这是"键盘弹起来把正在编辑的那一行盖住"的根治办法。
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
    onReopen: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    var note by remember { mutableStateOf<String?>(null) }
    var ctrlLatch by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(TextFieldValue("")) }
    val rawFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 一行等宽字的实际宽度（px）：PTY 的列数必须按它算，否则 `ls`、提示符全折行
    val charWidthPx = remember {
        val w = measurer.measure("0".repeat(40), TermTextStyle).size.width / 40f
        if (w <= 0f) 8f else w
    }
    val lineHeightPx = remember { with(density) { TermTextStyle.lineHeight.toPx() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availW = with(density) { maxWidth.toPx() }
        val availH = with(density) { maxHeight.toPx() }
        // 底部占掉的高度：命令行模式要留输入框，全屏模式只留按键条 + 键盘挂钩
        val chrome = if (term.fullScreen) 132.dp else 150.dp
        val chromePx = with(density) { chrome.toPx() }
        val cols = ((availW - with(density) { 16.dp.toPx() }) / charWidthPx).toInt().coerceIn(20, 200)
        val rows = ((availH - chromePx) / lineHeightPx).toInt().coerceIn(8, 100)
        LaunchedEffect(cols, rows) { onEnsure(cols, rows) }
    }

    // 进了全屏程序就把软键盘拉起来：用户按了 vim 的 i，接下来就是要打字
    LaunchedEffect(term.fullScreen, term.id) {
        if (term.fullScreen) {
            runCatching { rawFocus.requestFocus() }
            keyboard?.show()
        }
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

    // ★ 滚动跟着**光标**走，不是跟着最后一行走。
    //   全屏程序（vim）反复重绘同一块区域，光标可能在屏幕中间；
    //   只滚到底就会出现"正在打字的行被键盘盖住"。上翻阅读时不打扰。
    LaunchedEffect(term.rev, followTail, term.cursorLine) {
        if (!followTail) return@LaunchedEffect
        val target = term.cursorLine ?: (term.lines.size - 1)
        if (target >= 0 && term.lines.isNotEmpty()) {
            runCatching { listState.scrollToItem(target.coerceIn(0, term.lines.size - 1)) }
        }
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
                        Text(
                            if (term.fullScreen) "终端 · 全屏程序" else "终端",
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                    // 没终端可看时：说清原因，并且给一个按钮 —— 别让用户只能靠"退出去再进来"
                    term.error != null || term.exited || term.id.isEmpty() -> Box(
                        Modifier.fillMaxSize().padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                term.error ?: if (term.exited) "电脑上的终端已经退出了" else "还没有终端",
                                color = if (term.error != null) MaterialTheme.colorScheme.error else TermFg,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(10.dp))
                            FilledTonalButton(onClick = onReopen) { Text("重新开一个终端") }
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    ) {
                        items(term.lines.size) { i -> TermLine(term.lines[i]) }
                    }
                }

                if (!followTail && term.lines.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = { onFollowTailChange(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                    ) { Text("↓ 回到光标") }
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

            // 电脑上没有活终端时（已退出 / 开失败），按键和输入框都是死控件 ——
            // 藏起来，只留上面那个「重新开一个终端」
            if (term.alive || term.opening) {
            // ---------------- 按键条 ----------------
            //
            // 用 FlowRow 自动换行，**不要**用横向滚动的 Row：
            // 实测横向滚动容器会在启动时把第一个按钮挤出屏幕左侧，
            // 它的文字被裁掉（无障碍树里只剩一个 36px 的空按钮）。
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (term.fullScreen) {
                    FilledTonalButton(onClick = { onSendRaw("\u001b") }) { Text("Esc") }
                    KeyChip("⏎") { onSendRaw("\r") }
                    KeyChip("Tab") { onSendRaw("\t") }
                    KeyChip("⌫") { onSendRaw("\u007f") }
                    KeyChip("↑") { onSendRaw("\u001b[A") }
                    KeyChip("↓") { onSendRaw("\u001b[B") }
                    KeyChip("←") { onSendRaw("\u001b[D") }
                    KeyChip("→") { onSendRaw("\u001b[C") }
                    KeyChip(if (ctrlLatch) "Ctrl ●" else "Ctrl") { ctrlLatch = !ctrlLatch }
                    KeyChip("Ctrl-C") { onInterrupt() }
                    KeyChip("/") { onSendRaw("/") }
                    KeyChip(":") { onSendRaw(":") }
                    KeyChip("|") { onSendRaw("|") }
                    KeyChip("-") { onSendRaw("-") }
                    KeyChip("粘贴") {
                        val text = clipboard.getText()?.text.orEmpty()
                        if (text.isEmpty()) note = "剪贴板是空的"
                        else {
                            onSendRaw(text)
                            note = "已粘进全屏程序（${text.length} 字）"
                        }
                    }
                } else {
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
            }

            // ---------------- 输入区 ----------------
            if (term.fullScreen) {
                RawInputBar(
                    value = raw,
                    focusRequester = rawFocus,
                    ctrlLatch = ctrlLatch,
                    onValueChange = { v ->
                        val composing = v.composition != null
                        val text = v.text
                        when {
                            // 组词中：只留在框里给用户看，别发给 vim
                            composing -> raw = v
                            text.isEmpty() -> raw = v
                            // Ctrl 锁存：下一个键按控制字符发（Ctrl-W / Ctrl-R 这些）
                            ctrlLatch && text.length == 1 -> {
                                ctrlLatch = false
                                raw = TextFieldValue("")
                                onSendRaw((text[0].code and 0x1F).toChar().toString())
                            }
                            else -> {
                                raw = TextFieldValue("")
                                onSendRaw(text)
                            }
                        }
                    },
                )
            } else {
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
    }
}

/**
 * 全屏程序（vim/htop）里的输入框。
 *
 * 它看着是个输入框，实际是**键盘挂钩**：输入法一提交就把内容发进 PTY、随即清空。
 * 这样中文能正常组词（输入法只在"框里"工作），
 * 而 vim 收到的是逐键直通的按键 —— 两边都满意。
 * 框里**不保留**已发出去的字符：终端那头会自己回显，那才是权威画面。
 */
@Composable
private fun RawInputBar(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    ctrlLatch: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ctrlLatch) "Ctrl ●" else "⌨",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text(if (ctrlLatch) "按一个键 = Ctrl+那个键" else "在这里打字，直接进 vim") },
                textStyle = TermTextStyle,
                singleLine = true,
            )
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 把一行 AnsiSpan 画成等宽文本。 */
@Composable
private fun TermLine(line: AnsiLine) {
    if (line.isEmpty()) {
        // 空行也要占一行的高度，否则整屏行号会错位
        Text(" ", style = TermTextStyle, modifier = Modifier.fillMaxWidth())
        return
    }
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
    }
    // ★ 不能软换行：PTY 的列数是按屏幕宽度算好的，一行本来就该放得下。
    //   真换行会把下面所有行整体挤下去 —— 全屏程序（vim）的网格当场错位。
    //   宁可把超出的最后几个字符裁掉。
    Text(
        text = text,
        style = TermTextStyle,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.fillMaxWidth(),
    )
}
