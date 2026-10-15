package com.dsh.phone.ui

import androidx.compose.foundation.background
import com.dsh.phone.hookKeystrokes
import com.dsh.phone.lastNonBlankRange
import com.dsh.phone.linesText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
import com.dsh.phone.AnsiSpan
import com.dsh.phone.CursorKind
import com.dsh.phone.cursorKind
import com.dsh.phone.renderCells
import com.dsh.phone.TermState
import com.dsh.phone.shortPath

/** 终端底色固定用深色：ANSI 的 16 色是按深底配的，跟着系统主题走会读不清。 */
private val TermBg = Color(0xFF10131A)
private val TermFg = Color(0xFFD7DAE0)

/** 光标块的颜色：亮一档，在深底上任何时候都看得见。 */
private val TermCursor = Color(0xFFE8ECF2)

/** 选中行的底色（兼容深色终端的半透明蓝）。 */
private val SelectionBg = Color(0x553B6EA5)

/** 一行等宽字体的样式（列宽测量也必须用同一份）。 */
private val TermTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * 手机上的电脑终端。
 *
 * **只有一种输入方式：逐键直通。**
 *
 * 早先分了两套（命令行模式"写好一行再发送" + 全屏模式"逐键直通"），
 * 结果是两个毛病同源：
 *   - 输入框得有个发送按钮（那个小飞机）；
 *   - **Tab 补全根本不可能** —— `vim test` 还躺在输入框里，shell 没看见，
 *     拿什么补全？
 * 真终端就是逐键直通：敲一个字发一个字，shell 自己回显、自己补全。
 * 中文照样没问题：输入法在挂钩框里组词，**提交时**才把那个词发进 PTY。
 *
 * 挂钩框（下面那个"在这里打字"）里不留已发出去的字符 —— 权威画面是终端本身。
 *
 * 光标：屏幕模型知道光标在第几行（[TermState.cursorLine]），滚动跟着它走 ——
 * 这是"键盘弹起来把正在编辑的那一行盖住"的根治办法。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    term: TermState,
    followTail: Boolean,
    onFollowTailChange: (Boolean) -> Unit,
    onEnsure: (cols: Int, rows: Int) -> Unit,
    onSend: (String) -> Unit,
    onSendRaw: (String) -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onKill: () -> Unit,
    onReopen: () -> Unit,
    onBack: () -> Unit,
    /** 外部请求（深链 `--es termCopy N`）：复制最近 N 行。(seq, n)，seq 变就触发一次。 */
    copyRequest: Pair<Int, Int>? = null,
    /** 处理完通知外面清掉请求 —— 否则离开再进来会重放一次（复制到莫名其妙的内容）。 */
    onCopyHandled: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    var note by remember { mutableStateOf<String?>(null) }
    var ctrlLatch by remember { mutableStateOf(false) }
    // 选择模式：点第一行定起点、点第二行定终点（再点就重新开一段）
    var selecting by remember { mutableStateOf(false) }
    var selAnchor by remember { mutableStateOf<Int?>(null) }
    var selEnd by remember { mutableStateOf<Int?>(null) }
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

    // 深链请求：把最近 N 行复制走（等于自动走一遍"选择 → 复制"）
    LaunchedEffect(copyRequest, term.lines.size) {
        val req = copyRequest ?: return@LaunchedEffect
        if (term.lines.isEmpty()) return@LaunchedEffect
        // "最近 N 行" = 最近的 N 行**内容**（跳过屏幕下方的空白，见 lastNonBlankRange）
        onCopyHandled()
        val range = lastNonBlankRange(term.lines, req.second.coerceAtLeast(1))
        if (range == null) {
            note = "屏幕上还没有内容可复制"
            return@LaunchedEffect
        }
        val text = linesText(term.lines, range.first, range.second)
        clipboard.setText(AnnotatedString(text))
        val rows = kotlin.math.abs(range.second - range.first) + 1
        val head = text.replace("\n", " ⏎ ").take(40)
        note = "已复制 $rows 行（${text.length} 字）：$head" +
            if (text.length > 40) " …" else ""
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
                        // 光标那一行要把列号传下去 —— 不然 vim 里根本看不出光标在哪
                        val cursorRow = term.cursorLine
                        val a = selAnchor
                        val b = selEnd
                        val lo = if (a != null && b != null) minOf(a, b) else -1
                        val hi = if (a != null && b != null) maxOf(a, b) else -1
                        items(term.lines.size) { i ->
                            val kind = cursorKind(i, cursorRow, term.fullScreen, term.cursorVisible)
                            val selected = selecting && a != null && i in lo..hi
                            TermLine(
                                line = term.lines[i],
                                cursorCol = if (kind == CursorKind.NONE) null else term.cursorCol,
                                dimCursor = kind == CursorKind.DIM,
                                selected = selected,
                                onClick = if (!selecting) null else {
                                    {
                                        // 第一次点定起点，第二次点定终点，第三次重新开一段
                                        if (selAnchor == null || selEnd != selAnchor) {
                                            selAnchor = i
                                            selEnd = i
                                        } else {
                                            selEnd = i
                                        }
                                    }
                                },
                                onLongClick = if (selecting) null else {
                                    {
                                        selecting = true
                                        selAnchor = i
                                        selEnd = i
                                        onFollowTailChange(false)
                                        note = "再点一行选到那里，然后按「复制」"
                                    }
                                },
                            )
                        }
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

            // 选择模式：把按键条和输入框换成"复制 / 全选 / 取消"
            if (selecting) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val a = selAnchor
                        val b = selEnd
                        val count = if (a != null && b != null) kotlin.math.abs(b - a) + 1 else 0
                        Text(
                            if (count > 0) "已选 $count 行" else "点一行开始选",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = count > 0,
                            onClick = {
                                val text = linesText(term.lines, a ?: 0, b ?: 0)
                                clipboard.setText(AnnotatedString(text))
                                // 回显开头一小段：用户一眼能看出"抄对了没"，
                                // 也让这条链路可以被无人值守核对（读屏上的文字即可）
                                val head = text.replace("\n", " ⏎ ").take(40)
                                note = "已复制 $count 行（${text.length} 字）：$head" +
                                    if (text.length > 40) " …" else ""
                                selecting = false
                                selAnchor = null
                                selEnd = null
                            },
                        ) { Text("复制") }
                        TextButton(onClick = {
                            selAnchor = 0
                            selEnd = term.lines.size - 1
                            note = "已全选（${term.lines.size} 行），按「复制」"
                        }) { Text("全选") }
                        TextButton(onClick = {
                            selecting = false
                            selAnchor = null
                            selEnd = null
                            note = null
                        }) { Text("取消") }
                    }
                }
            }

            // 电脑上没有活终端时（已退出 / 开失败），按键和输入框都是死控件 ——
            // 藏起来，只留上面那个「重新开一个终端」
            if (!selecting && (term.alive || term.opening)) {
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
                // 一套键，两种模式通用：命令行下 Tab/补全/历史/行编辑全靠它们，
                // 全屏程序下多出来的也是这些（Esc / 方向键 / Ctrl）。
                FilledTonalButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isEmpty()) {
                        note = "剪贴板是空的"
                    } else {
                        // 「粘贴」= 打进当前命令行（不回车）：补全、改一改再跑都行
                        onSendRaw(text)
                        note = "已把剪贴板的 ${text.length} 字打进命令行"
                    }
                }) { Text("粘贴") }

                FilledTonalButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    if (text.isEmpty()) {
                        note = "剪贴板是空的"
                    } else {
                        onSend(text)
                        note = "已把剪贴板的 ${text.length} 字发出去并回车"
                    }
                }) { Text("粘贴并跑") }

                KeyChip("⏎") { onSendRaw("\r") }
                KeyChip("Tab") { onSendRaw("\t") }
                KeyChip("⌫") { onSendRaw("\u007f") }
                KeyChip("Esc") { onSendRaw("\u001b") }
                KeyChip("↑") { onSendRaw("\u001b[A") }
                KeyChip("↓") { onSendRaw("\u001b[B") }
                KeyChip("←") { onSendRaw("\u001b[D") }
                KeyChip("→") { onSendRaw("\u001b[C") }
                KeyChip("Ctrl-C") { onInterrupt() }
                KeyChip(if (ctrlLatch) "Ctrl ●" else "Ctrl") { ctrlLatch = !ctrlLatch }
                KeyChip("清屏") { onClear() }
                KeyChip("选择") {
                    selecting = true
                    selAnchor = null
                    selEnd = null
                    onFollowTailChange(false)
                    note = "点一行定起点，再点一行定终点"
                }
            }

            // ---------------- 输入区 ----------------
            //
            // 只有这一个输入框：它是**键盘挂钩**，不是"待发送内容"。
            // 输入法一提交就把字符发进 PTY 并清空 —— 于是 shell 那边是真的
            // 一个字一个字收到，补全/历史/行编辑全都成立。
            RawInputBar(
                value = raw,
                focusRequester = rawFocus,
                ctrlLatch = ctrlLatch,
                onValueChange = { v ->
                    // 判据在 TermScreen.kt 的 hookKeystrokes（纯函数，有单测）：
                    // 回车在这里就是一句换行符，必须**发出去**，不许被当空内容吞掉
                    val r = hookKeystrokes(v.text, v.composition != null, ctrlLatch)
                    if (r.keepInField) {
                        raw = v
                    } else {
                        if (r.consumeLatch) ctrlLatch = false
                        raw = TextFieldValue("")
                        r.send?.let { onSendRaw(it) }
                    }
                },
                onEnter = { onSendRaw("\r") },
            )
            }
        }
    }
}

/**
 * 终端底部的输入框 —— 它是**键盘挂钩**，不是"待发送内容"。
 *
 * 输入法一提交就把内容发进 PTY、随即清空：中文能正常组词（输入法只在"框里"工作），
 * 而 shell / vim 收到的是逐键直通的按键。框里**不保留**已发出去的字符 ——
 * 终端那头会自己回显，那才是权威画面（也正因如此，Tab 补全才成立）。
 */
@Composable
private fun RawInputBar(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    ctrlLatch: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onEnter: () -> Unit,
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
                placeholder = { Text(if (ctrlLatch) "按一个键 = Ctrl+那个键" else "在这里打字，直接进终端（Tab 可补全）") },
                textStyle = TermTextStyle,
                // ★ 回车必须真的发出去。这里踩过一个坑：原来 singleLine = true，
                //   手机键盘的回车被输入法当成"IME 动作"吃掉了，压根进不到 onValueChange，
                //   表现就是"按回车没反应、什么也没进去"。
                //   两条路一起铺，哪条通都算：
                //     1) imeAction = None + 多行：回车**变成换行符**进到文本里，
                //        由 onValueChange 发出去（对输入法最不挑）；
                //     2) 万一某个输入法仍然发"动作"，onSend/onDone/onGo 也接住。
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                keyboardActions = KeyboardActions(
                    onSend = { onEnter() },
                    onDone = { onEnter() },
                    onGo = { onEnter() },
                ),
                singleLine = false,
                maxLines = 1,
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

@Composable
private fun spanStyle(span: AnsiSpan, cursor: Boolean = false, dimCursor: Boolean = false): SpanStyle = when {
    // 光标 = 反显块：底色用前景色，字色用终端底色
    cursor -> SpanStyle(color = TermBg, background = TermCursor)
    // 全屏程序报告"隐藏光标"时的位置提示：描个边，不抢注意力
    dimCursor -> SpanStyle(
        color = span.fg?.let { Color(it) } ?: TermFg,
        background = TermBg,
        textDecoration = TextDecoration.Underline,
    )
    else -> SpanStyle(
        color = span.fg?.let { Color(it) } ?: TermFg,
        background = span.bg?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (span.bold) FontWeight.Bold else null,
        textDecoration = if (span.underline) TextDecoration.Underline else null,
    )
}

/**
 * 把一行 AnsiSpan 画成等宽文本，并在 [cursorCol] 那一格画光标块。
 *
 * 光标为什么必须画：vim 全靠它 —— 没有光标块，"我在第几个字"、"按 i 会插到哪"
 * 全都看不出来，编辑就成了盲操作。位置由屏幕模型给（[TermState.cursorLine] /
 * [TermState.cursorCol]），不是猜的。
 *
 * 注意**列 ≠ 字符数**：一个汉字占两列。所以先把这一行摊成"带起始列的格子"，
 * 再按列找光标那一格，否则行内有中文时光标会偏。
 */
@Composable
private fun TermLine(
    line: AnsiLine,
    cursorCol: Int?,
    dimCursor: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    if (line.isEmpty() && cursorCol == null) {
        // 空行也要占一行的高度，否则整屏行号会错位
        Text(" ", style = TermTextStyle, modifier = Modifier.fillMaxWidth())
        return
    }

    // 格子与"哪一格是光标"由 TermScreen.kt 里的 renderCells() 算 —— 那是纯逻辑，
    // 有单元测试盯着（含中文的列号换算），这里只负责画。
    val cells = renderCells(line, cursorCol)

    val text = buildAnnotatedString {
        cells.forEach { c ->
            withStyle(spanStyle(c.style, c.onCursor, dimCursor && c.onCursor)) { append(c.ch) }
        }
    }
    // ★ 不能软换行：PTY 的列数是按屏幕宽度算好的，一行本来就该放得下。
    //   真换行会把下面所有行整体挤下去 —— 全屏程序（vim）的网格当场错位。
    //   宁可把超出的最后几个字符裁掉。
    val base = Modifier
        .fillMaxWidth()
        .let { if (selected) it.background(SelectionBg) else it }
        .let {
            when {
                onClick != null && onLongClick != null ->
                    it.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                onClick != null -> it.clickable(onClick = onClick)
                onLongClick != null -> it.combinedClickable(onClick = {}, onLongClick = onLongClick)
                else -> it
            }
        }
    Text(
        text = text,
        style = TermTextStyle,
        softWrap = false,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = base,
    )
}
