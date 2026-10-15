package com.dsh.phone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dsh.phone.ui.ChatScreen
import com.dsh.phone.ui.DocumentScreen
import com.dsh.phone.ui.DshActions
import com.dsh.phone.ui.UnreadGreen
import com.dsh.phone.ui.DshTheme
import com.dsh.phone.ui.NewSessionScreen
import com.dsh.phone.ui.SessionsScreen
import com.dsh.phone.ui.SettingsScreen
import com.dsh.phone.ui.StatusScreen
import com.dsh.phone.ui.TerminalScreen

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) StatusService.start(this)
        }

    /**
     * 「打开终端页」的请求计数器。
     *
     * 用 Compose 状态而不是普通字段：onNewIntent 里 +1 就能让已经组合好的
     * AppRoot 切页，冷启动和热启动不必写两套逻辑。
     */
    private val terminalRequest = mutableStateOf(0)

    /** 深链复制请求的序号：每次请求都换一个值，好让界面上的 LaunchedEffect 再触发一次。 */
    private var termCopySeq = 0

    /** 深链要求复制的行数（null = 没有请求）。 */
    private val termCopyReq = mutableStateOf<Pair<Int, Int>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 起始页/子页由 intent 指定，便于无人值守验证（澎湃禁止 adb 模拟点击）
        val startTab = when (intent?.getStringExtra(EXTRA_TAB)) {
            "sessions" -> 1
            "settings" -> 2
            else -> 0
        }
        // am start 既可能给 --ez（布尔）也可能给 --es（字符串 "true"），两种都认
        val startNewSession = intent?.let { i ->
            i.getBooleanExtra(EXTRA_NEW_SESSION, false) ||
                i.getStringExtra(EXTRA_NEW_SESSION)?.equals("true", ignoreCase = true) == true ||
                i.getStringExtra(EXTRA_NEW_SESSION) == "1"
        } ?: false
        setContent {
            DshTheme {
                AppRoot(
                    vm = vm,
                    startTab = startTab,
                    startNewSession = startNewSession,
                    terminalRequest = terminalRequest.value,
                    termCopyRequest = termCopyReq.value,
                    onTermCopyHandled = { termCopyReq.value = null },
                    onNeedNotifPermission = { requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onExit = { finish() },
                )
            }
        }
        openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.setForeground(true)
    }

    override fun onPause() {
        vm.setForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    /**
     * 支持用 intent 直接落到某个会话：
     *   adb shell am start -n com.dsh.phone/.MainActivity --es sessionId <id>
     * 也让脚本/快捷方式/通知跳转能直达会话。
     */
    private fun openFromIntent(intent: Intent?) {
        if (intent == null) return
        // 预置端点/令牌：方便用 adb 一次性配好，不用在手机上敲键盘
        //   adb shell am start -n com.dsh.phone/.MainActivity \
        //     --es endpoints "http://127.0.0.1:3080,http://host.ts.net:3080" \
        //     --es token "xxxxx"
        val eps = intent.getStringExtra(EXTRA_ENDPOINTS)
        val tok = intent.getStringExtra(EXTRA_TOKEN)
        if (!eps.isNullOrBlank() || !tok.isNullOrBlank()) {
            vm.applySettings(
                endpointsRaw = eps ?: vm.currentEndpointsRaw(),
                token = tok ?: vm.currentToken(),
                notify = vm.notifyEnabled.value,
            )
        }
        val sid = intent.getStringExtra(EXTRA_SESSION_ID)
        if (!sid.isNullOrBlank()) vm.openSession(sid)

        // 直接打开一个文档（深链 / 无人值守验证）：
        //   adb shell am start -n com.dsh.phone/.MainActivity --es file "docs/xxx.md"
        val file = intent.getStringExtra(EXTRA_FILE)
        if (!file.isNullOrBlank()) vm.openDocument(file)

        // 顺带发一条消息，便于无人值守验证 / 快捷方式一键问一句：
        //   adb shell am start -n com.dsh.phone/.MainActivity \
        //     --es sessionId <id> --es send "现在几点"
        // 深链到终端页，并可选地自动执行一条命令（等 PTY 就绪，见 sendTermWhenReady）：
        //   adb shell am start -n com.dsh.phone/.MainActivity --es screen terminal
        //   adb shell am start -n com.dsh.phone/.MainActivity --es termSend "ls -l"
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        val termSend = intent.getStringExtra(EXTRA_TERM_SEND)
        if (screen == "terminal" || !termSend.isNullOrBlank()) {
            terminalRequest.value += 1
        }
        if (!termSend.isNullOrBlank()) vm.sendTermWhenReady(termSend)

        // --es termCopy N  → 复制终端最近 N 行到剪贴板（也用于无人值守验证）
        val termCopy = intent.getStringExtra(EXTRA_TERM_COPY)?.trim()?.toIntOrNull() ?: 0
        if (termCopy > 0 && screen == "terminal") {
            termCopySeq += 1
            termCopyReq.value = termCopySeq to termCopy
        }

        val send = intent.getStringExtra(EXTRA_SEND)
        if (!sid.isNullOrBlank() && !send.isNullOrBlank()) {
            lifecycleScope.launch {
                delay(1200)
                vm.send(send)
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_TERM_SEND = "termSend"
        const val EXTRA_TERM_COPY = "termCopy"
        const val EXTRA_ENDPOINTS = "endpoints"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SEND = "send"
        const val EXTRA_TAB = "tab"
        const val EXTRA_NEW_SESSION = "newSession"
        const val EXTRA_FILE = "file"
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot(
    vm: AppViewModel,
    startTab: Int = 0,
    startNewSession: Boolean = false,
    /** 每次"要求打开终端"就 +1：冷启动与热启动（onNewIntent）走同一条路。 */
    terminalRequest: Int = 0,
    /** 深链要求复制终端最近 N 行：(seq, n)。 */
    termCopyRequest: Pair<Int, Int>? = null,
    onTermCopyHandled: () -> Unit = {},
    onNeedNotifPermission: () -> Unit,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }

    val state by vm.state.collectAsState()
    val conn by vm.conn.collectAsState()
    val messages by vm.messages.collectAsState()
    val streamText by vm.streamText.collectAsState()
    val streamReasoning by vm.streamReasoning.collectAsState()
    val openId by vm.openSessionId.collectAsState()
    val busy by vm.busy.collectAsState()
    val chatError by vm.chatError.collectAsState()
    val workspaces by vm.workspaces.collectAsState()
    val listing by vm.listing.collectAsState()
    val listingBusy by vm.listingBusy.collectAsState()
    val question by vm.question.collectAsState()
    val unread by vm.unread.collectAsState()
    val working by vm.working.collectAsState()
    val recentAt by vm.recentAt.collectAsState()
    val term by vm.term.collectAsState()
    val termFollowTail by vm.termFollowTail.collectAsState()
    val queue by vm.queue.collectAsState()
    val liveRunning by vm.liveRunning.collectAsState()
    val catalog by vm.models.collectAsState()
    val switchingModel by vm.switchingModel.collectAsState()
    val draft by vm.draft.collectAsState()
    val expandedWorkspaces by vm.expandedWorkspaces.collectAsState()
    val doc by vm.doc.collectAsState()
    val chatListState by vm.chatListState.collectAsState()
    val chatFollowTail by vm.chatFollowTail.collectAsState()
    val toast by vm.toast.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val endpoints by vm.endpoints.collectAsState()
    val token by vm.token.collectAsState()
    val endpointsRaw = remember(endpoints) { endpoints.joinToString("\n") }
    val notifyEnabled by vm.notifyEnabled.collectAsState()

    var tab by remember { mutableStateOf(startTab) }
    var showNewSession by remember { mutableStateOf(startNewSession) }
    var showTerminal by remember { mutableStateOf(false) }

    LaunchedEffect(terminalRequest) {
        if (terminalRequest > 0) showTerminal = true
    }
    var showInjected by remember { mutableStateOf(settings.showInjected) }
    var showExit by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(startNewSession) {
        if (startNewSession && listing.path.isBlank()) vm.browse(null)
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            vm.consumeToast()
        }
    }

    /**
     * 侧边滑动（系统返回手势）按层级回退。
     *
     * 不接管的话，返回手势在任务根部就是"结束 Activity"，也就是直接退出 App。
     * 这里逐层拦：新建页 → 聊天页 → 顶层弹确认框。
     *
     * 放在两个 early return 之前，所以任何一屏都生效。
     * 模型选择面板是 ModalBottomSheet（独立窗口），它自己会先吃掉返回。
     */
    BackHandler {
        when {
            doc != null -> vm.closeDocument()
            showTerminal -> showTerminal = false
            showNewSession -> showNewSession = false
            openId.isNotEmpty() -> vm.closeSession()
            else -> showExit = true
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("退出 App？") },
            text = {
                Text(
                    "确定退出 DSH 手机端吗？\n" +
                        "电脑上的 harness、会话和后台任务都不受影响，随时可以再打开。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    onExit()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text("取消") }
            },
        )
    }

    // 文档阅读页（点开文件路径时进这里）
    if (doc != null) {
        DocumentScreen(
            doc = doc,
            onBack = { vm.closeDocument() },
            onOpenFile = { vm.openDocument(it) },
        )
        return
    }

    // 终端页（整屏）。离开这一页只是断开输出流，电脑上的 shell 留着
    // —— 用户就是要在"会话 ↔ 终端"之间来回切着复制命令的。
    if (showTerminal) {
        TerminalScreen(
            term = term,
            followTail = termFollowTail,
            onFollowTailChange = { vm.setTermFollowTail(it) },
            onEnsure = { cols, rows -> vm.ensureTerminal(cols, rows) },
            onSend = { vm.sendTerm(it) },
            onSendRaw = { vm.sendTermRaw(it) },
            onInterrupt = { vm.interruptTerm() },
            onClear = { vm.clearTerminal() },
            onKill = { vm.killTerminal(); showTerminal = false },
            onReopen = { vm.reopenTerminal() },
            copyRequest = termCopyRequest,
            onCopyHandled = onTermCopyHandled,
            onBack = { showTerminal = false },
        )
        // 这一页不再需要"输出流"时就断开：重进会拿桥接的滚动缓冲补上
        DisposableEffect(Unit) { onDispose { vm.detachTerminal() } }
        return
    }

    // 新建会话页
    if (showNewSession) {
        NewSessionScreen(
            listing = listing,
            busy = listingBusy,
            onBack = { showNewSession = false },
            onBrowse = { vm.browse(it) },
            onMakeFolder = { vm.makeFolder(it) },
            onCreateHere = { title ->
                showNewSession = false
                vm.createSessionHere(title)
            },
        )
        return
    }

    // 打开会话时优先显示聊天页
    if (openId.isNotEmpty()) {
        val session = state.sessions.firstOrNull { it.id == openId }
        ChatScreen(
            title = session?.displayTitle ?: openId.take(12),
            messages = messages,
            streamText = streamText,
            streamReasoning = streamReasoning,
            // 三个来源取或：状态轮询、会话实时流的 turn 事件、正在流式输出。
            // 只靠轮询的话，停止按钮该出现时会晚 5 秒甚至不出现。
            running = session?.running == true || liveRunning || streamText.isNotEmpty(),
            busy = busy,
            showInjected = showInjected,
            chatError = chatError,
            connPhase = conn.phase,
            question = question,
            draft = draft,
            onDraftChange = { vm.updateDraft(it) },
            catalog = catalog,
            switchingModel = switchingModel,
            listState = chatListState,
            followTail = chatFollowTail,
            onFollowTailChange = { vm.setChatFollowTail(it) },
            onBack = { vm.closeSession() },
            onSend = { vm.send(it) },
            onStop = { vm.interrupt() },
            onRetry = { vm.reconnect() },
            onAnswer = { vm.answerQuestion(it) },
            onStopThinking = { vm.stopThinking() },
            onSwitchModel = { p, m, e -> vm.switchModel(p, m, e) },
            onRefreshModels = { vm.refreshModels() },
            onOpenFile = { vm.openDocument(it) },
            onToggleInjected = {
                showInjected = it
                vm.setShowInjected(it)
            },
            queue = queue,
            onEditQueued = { id, text -> vm.editQueued(id, text) },
            onRetractQueued = { vm.retractQueued(it) },
            onSteerQueued = { vm.steerQueued(it) },
        )
        return
    }

    val tabs = listOf(
        Tab("状态", Icons.Filled.Info),
        Tab("会话", Icons.Filled.List),
        Tab("设置", Icons.Filled.Settings),
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = {
                            // 会话标签带上未读角标（绿色 = 有新东西看，不是错误）
                            if (i == 1 && unread.isNotEmpty()) {
                                Text("${t.label} ${unread.size}", color = UnreadGreen)
                            } else {
                                Text(t.label)
                            }
                        },
                    )
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> StatusScreen(
                    state = state,
                    conn = conn,
                    baseUrl = baseUrl,
                    term = term,
                    onRefresh = { vm.refreshNow() },
                    onOpenTerminal = { showTerminal = true },
                )
                1 -> SessionsScreen(
                    state = state,
                    workspaces = workspaces,
                    unread = unread,
                    working = working,
                    recentAt = recentAt,
                    expanded = expandedWorkspaces,
                    listState = vm.sessionsListState,
                    actions = DshActions(
                        openSession = { vm.openSession(it) },
                        renameSession = { id, title -> vm.renameSession(id, title) },
                        forkSession = { vm.forkSession(it) },
                        archiveSession = { vm.archiveSession(it) },
                        toggleWorkspace = { vm.toggleWorkspace(it) },
                        renameWorkspace = { id, title -> vm.renameWorkspace(id, title) },
                        deleteWorkspace = { vm.deleteWorkspace(it) },
                        newSessionInWorkspace = { vm.createSessionInWorkspace(it) },
                        clearRecent = { vm.clearRecent() },
                        newSession = {
                            // 打开时若还没浏览过，从家目录开始
                            if (listing.path.isBlank()) vm.browse(null)
                            showNewSession = true
                        },
                    ),
                )
                else -> SettingsScreen(
                    endpointsRaw = endpointsRaw,
                    activeBaseUrl = baseUrl,
                    token = token,
                    showInjected = showInjected,
                    notifyEnabled = notifyEnabled,
                    onSave = { raw, tok, notify ->
                        vm.applySettings(raw, tok, notify)
                        if (notify) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                onNeedNotifPermission()
                            } else {
                                StatusService.start(context)
                            }
                        } else {
                            StatusService.stop(context)
                        }
                    },
                    onTest = { raw, tok -> vm.testConnection(raw, tok) },
                    onToggleInjected = {
                        showInjected = it
                        vm.setShowInjected(it)
                    },
                )
            }
        }
    }
}
