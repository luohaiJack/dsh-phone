package com.dsh.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.dsh.phone.ChatMessage
import com.dsh.phone.ConnPhase
import com.dsh.phone.ConnStatus
import com.dsh.phone.DirListing
import com.dsh.phone.DocState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dsh.phone.PendingAttachment
import com.dsh.phone.GoalInfo
import com.dsh.phone.goalWorthShowing
import com.dsh.phone.SessionProgress
import com.dsh.phone.PendingQuestion
import com.dsh.phone.SessionActivity
import com.dsh.phone.QueuedMessage
import com.dsh.phone.TermState
import com.dsh.phone.QuestionAnswer
import com.dsh.phone.HarnessState
import com.dsh.phone.ModelCatalog
import com.dsh.phone.WorkspaceInfo
import com.dsh.phone.WorkspaceSession
import com.dsh.phone.contextPercent
import com.dsh.phone.formatTokens
import com.dsh.phone.formatClock
import com.dsh.phone.formatDuration
import com.dsh.phone.relativeTime
import com.dsh.phone.shortPath

/* ============================== 通用小组件 ============================== */

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            key,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium.let {
                if (mono) it.copy(fontFamily = FontFamily.Monospace) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun ConnBadge(conn: ConnStatus) {
    val (color, label) = when (conn.phase) {
        ConnPhase.LIVE -> Color(0xFF35C77A) to "已连接"
        ConnPhase.CONNECTING -> Color(0xFFE0A93B) to "连接中"
        ConnPhase.ERROR -> MaterialTheme.colorScheme.error to "未连接"
        ConnPhase.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant to "空闲"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/* ================================ 状态页 ================================ */

@Composable
fun StatusScreen(
    state: HarnessState,
    conn: ConnStatus,
    baseUrl: String,
    term: TermState,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("连接") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnBadge(conn)
                if (conn.latencyMs > 0) {
                    Text("往返 ${conn.latencyMs} ms", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            KeyValue("地址", baseUrl, mono = true)
            KeyValue("状态", if (state.ok) "harness 正常" else (state.error ?: "未知"))
            state.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            conn.message?.takeIf { conn.phase == ConnPhase.ERROR }?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("刷新")
            }
        }

        SectionCard("终端") {
            KeyValue("状态", term.statusText)
            if (term.pid > 0) KeyValue("进程", "pid " + term.pid)
            if (term.cwd.isNotBlank()) KeyValue("工作目录", term.cwd, mono = true)
            if (term.cols > 0) KeyValue("尺寸", term.cols.toString() + " 列 × " + term.rows + " 行")
            Spacer(Modifier.height(6.dp))
            Text(
                "在电脑上开一个真终端（真 PTY）。在会话里长按复制一段命令，\n" +
                    "切到这里点「粘贴并跑」就能直接执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onOpenTerminal) {
                Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (term.alive) "回到终端" else "打开终端")
            }
        }

        SectionCard("模型") {
            val m = state.model
            if (m == null) {
                Text("未读到模型信息", style = MaterialTheme.typography.bodyMedium)
            } else {
                KeyValue("Provider", m.provider)
                KeyValue("Model", m.model)
                KeyValue("推理强度", m.effort ?: "默认")
            }
        }

        SectionCard("概览") {
            KeyValue("会话总数", state.totalSessions.toString())
            KeyValue("运行中", state.runningCount.toString())
            KeyValue("活动任务", state.activeJobs.size.toString())
            KeyValue("快照时间", formatClock(state.ts))
        }

        if (state.activeJobs.isNotEmpty()) {
            SectionCard("运行中的任务") {
                state.activeJobs.forEach { j ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                j.label.ifBlank { j.id },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatDuration(j.startedAt, j.finishedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            "${j.kind} · ${j.status}" + (j.detail?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 2.dp))
                }
            }
        }

        val withGoal = state.sessions.filter { it.goal != null && it.running }
        if (withGoal.isNotEmpty()) {
            SectionCard("进行中的目标") {
                withGoal.forEach { s ->
                    val g = s.goal ?: return@forEach
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            s.displayTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(g.objective, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                        Spacer(Modifier.height(6.dp))
                        val max = g.maxGoalRounds
                        if (max != null && max > 0) {
                            LinearProgressIndicator(
                                progress = { (g.roundsStarted.toFloat() / max).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            "阶段 ${g.phase} · 第 ${g.roundsStarted}" + (max?.let { "/$it" } ?: "") + " 轮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        g.blockedReason?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ======================= 工作区 / 会话列表 ======================= */

/** 「最近会话」最多显示几条 —— 它只是快捷键，不是第二个工作区树。 */
private const val RECENT_SHOWN = 6

/**
 * 三个板块（未读 / 正在工作 / 最近）共用的一张会话卡。
 *
 * 抽出来的理由：它们只差「左侧标记、右下角那句提示、配色」三点，
 * 复制三份的话以后改一处必然漏两处。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionSectionCard(
    container: Color,
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String,
    actionLabel: String,
    actionColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Box {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = actionColor)
            }
            menu()
        }
    }
}

/** 会话页要用到的全部动作，收成一个 bundle —— 否则参数列表会长到没法读。 */
data class DshActions(
    val openSession: (String) -> Unit,
    val renameSession: (String, String) -> Unit,
    val forkSession: (String) -> Unit,
    val archiveSession: (String) -> Unit,
    val toggleWorkspace: (String) -> Unit,
    val renameWorkspace: (String, String) -> Unit,
    val deleteWorkspace: (String) -> Unit,
    val newSessionInWorkspace: (String) -> Unit,
    val newSession: () -> Unit,
    /** 清空手机侧的「最近会话」记忆（电脑上的会话一个都不动）。 */
    val clearRecent: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    state: HarnessState,
    workspaces: List<WorkspaceInfo>,
    unread: Set<String>,
    working: Set<String>,
    recentAt: Map<String, Long>,
    expanded: Set<String>,
    listState: LazyListState,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    actions: DshActions,
) {
    // 长按弹出的菜单 / 对话框的目标
    var menuSession by remember { mutableStateOf<String?>(null) }
    var menuWorkspace by remember { mutableStateOf<String?>(null) }
    var renameSessionTarget by remember { mutableStateOf<WorkspaceSession?>(null) }
    var archiveTarget by remember { mutableStateOf<WorkspaceSession?>(null) }
    var renameWorkspaceTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var deleteWorkspaceTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    // 长按菜单里「查看 session id」的目标
    var idTarget by remember { mutableStateOf<WorkspaceSession?>(null) }

    // 三个板块的分类规则（互斥，一个会话只出现在一块里）：
    //   正在工作 = 电脑上还在跑（或手机正开着且实时流说在跑）
    //   未读回复 = 没在跑 且 助手最后说话 且 手机没看过这条
    //   最近会话 = 没在跑 且 已读 且 手机上最近点开过
    //
    // 为什么"还在跑"要从未读里摘出去：一轮里助手可能已经发过好几条消息
    // （调工具前后的说明、中间结论），它们都带 seq 但都不是最终回复，
    // 算成未读等于骗人 —— 点进去只看到半截。它该待在「正在工作」。
    val byId = HashMap<String, Pair<WorkspaceInfo, WorkspaceSession>>()
    workspaces.forEach { w -> w.sessions.forEach { s -> byId[s.id] = w to s } }

    val totalSessions = workspaces.sumOf { it.sessionCount }

    val runningNow = working
        .mapNotNull { id -> byId[id] }
        .sortedByDescending { it.second.updatedAt }

    val unreadItems = workspaces
        .flatMap { w -> w.sessions.filter { unread.contains(it.id) && !working.contains(it.id) }.map { w to it } }
        .sortedByDescending { it.second.activity.lastReplyAt }

    // recentAt 是有序表（新 → 旧），直接按它的顺序映射；映射不到的（刚被归档/删掉）自然消失
    val recentItems = recentAt.keys
        .mapNotNull { id -> byId[id]?.let { (w, s) -> Triple(w, s, recentAt[id] ?: 0L) } }
        .filter { (_, s, _) -> !working.contains(s.id) && !unread.contains(s.id) }
        .take(RECENT_SHOWN)

    // 下拉刷新：列表是工作区树渲染的，归档/新会话/重命名都要靠重拉才看得到
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${workspaces.size} 个工作区 · $totalSessions 个会话",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = actions.newSession) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("新建会话")
                    }
                }
            }

            if (unreadItems.isNotEmpty()) {
                item {
                    Text(
                        "未读回复（${unreadItems.size}）",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = UnreadGreen,
                    )
                }
                items(unreadItems.size) { i ->
                    val (w, sess) = unreadItems[i]
                    SessionSectionCard(
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        leading = {
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(UnreadGreen))
                        },
                        title = sess.displayTitle,
                        subtitle = w.displayTitle + " · " + relativeTime(sess.activity.lastReplyAt),
                        actionLabel = "去看 ›",
                        actionColor = UnreadGreen,
                        onClick = { actions.openSession(sess.id) },
                        onLongClick = { menuSession = sess.id },
                        menu = {
                            SessionMenu(
                                expanded = menuSession == sess.id,
                                onDismiss = { menuSession = null },
                                onOpen = { menuSession = null; actions.openSession(sess.id) },
                                onRename = { menuSession = null; renameSessionTarget = sess },
                                onFork = { menuSession = null; actions.forkSession(sess.id) },
                                onArchive = { menuSession = null; archiveTarget = sess },
                                onShowId = { menuSession = null; idTarget = sess },
                            )
                        },
                    )
                }
            }

            if (runningNow.isNotEmpty()) {
                item {
                    Text(
                        "正在工作",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(runningNow.size) { i ->
                    val (w, sess) = runningNow[i]
                    SessionSectionCard(
                        container = MaterialTheme.colorScheme.primaryContainer,
                        leading = { CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp) },
                        title = sess.displayTitle,
                        subtitle = w.displayTitle + " · " + relativeTime(sess.updatedAt),
                        actionLabel = "进入 ›",
                        actionColor = MaterialTheme.colorScheme.primary,
                        onClick = { actions.openSession(sess.id) },
                        onLongClick = { menuSession = sess.id },
                        menu = {
                            SessionMenu(
                                expanded = menuSession == sess.id,
                                onDismiss = { menuSession = null },
                                onOpen = { menuSession = null; actions.openSession(sess.id) },
                                onRename = { menuSession = null; renameSessionTarget = sess },
                                onFork = { menuSession = null; actions.forkSession(sess.id) },
                                onArchive = { menuSession = null; archiveTarget = sess },
                                onShowId = { menuSession = null; idTarget = sess },
                            )
                        },
                    )
                }
            }

            // 「最近会话」：手机屏小，工作区树一展开就滚不到底。
            // 这块只放"已经看过、也已经停下来"的 —— 未读和正在工作的在上面两块里，
            // 同一个会话不会同时出现在两块，免得用户不知道该点哪张卡。
            if (recentItems.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "最近会话",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = actions.clearRecent) { Text("清空") }
                    }
                }
                items(recentItems.size) { i ->
                    val (w, sess, openedAt) = recentItems[i]
                    SessionSectionCard(
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        leading = {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        title = sess.displayTitle,
                        subtitle = w.displayTitle + " · 上次打开：" + relativeTime(openedAt),
                        actionLabel = "继续 ›",
                        actionColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { actions.openSession(sess.id) },
                        onLongClick = { menuSession = sess.id },
                        menu = {
                            SessionMenu(
                                expanded = menuSession == sess.id,
                                onDismiss = { menuSession = null },
                                onOpen = { menuSession = null; actions.openSession(sess.id) },
                                onRename = { menuSession = null; renameSessionTarget = sess },
                                onFork = { menuSession = null; actions.forkSession(sess.id) },
                                onArchive = { menuSession = null; archiveTarget = sess },
                                onShowId = { menuSession = null; idTarget = sess },
                            )
                        },
                    )
                }
            }

            item {
                Text(
                    "工作区（历史 + 当前）",
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (workspaces.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "还没读到工作区\n检查连接，或点右上「新建会话」建一个",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(workspaces.size) { i ->
                val w = workspaces[i]
                val isOpen = expanded.contains(w.id)
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { actions.toggleWorkspace(w.id) },
                                        onLongClick = { menuWorkspace = w.id },
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (isOpen) "▾" else "▸",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            w.displayTitle,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (w.runningCount > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("运行中 ${w.runningCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        }
                                        val wUnread = w.sessions.count { unread.contains(it.id) }
                                        if (wUnread > 0) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("未读 $wUnread", style = MaterialTheme.typography.labelSmall, color = UnreadGreen)
                                        }
                                    }
                                    Text(w.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${w.sessionCount} 个会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (isOpen) {
                                HorizontalDivider()
                                if (w.sessions.isEmpty()) {
                                    Text(
                                        "这个工作区还没有会话",
                                        modifier = Modifier.padding(start = 34.dp, top = 8.dp, bottom = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                w.sessions.take(30).forEach { sess ->
                                    Box {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { actions.openSession(sess.id) },
                                                    onLongClick = { menuSession = sess.id },
                                                )
                                                .padding(start = 34.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            if (sess.running) {
                                                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 2.dp)
                                                Spacer(Modifier.width(6.dp))
                                            } else if (unread.contains(sess.id)) {
                                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(UnreadGreen))
                                                Spacer(Modifier.width(6.dp))
                                            }
                                            Text(sess.displayTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                            Text(relativeTime(sess.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        SessionMenu(
                                            expanded = menuSession == sess.id,
                                            onDismiss = { menuSession = null },
                                            onOpen = { menuSession = null; actions.openSession(sess.id) },
                                            onRename = { menuSession = null; renameSessionTarget = sess },
                                            onFork = { menuSession = null; actions.forkSession(sess.id) },
                                            onArchive = { menuSession = null; archiveTarget = sess },
                                            onShowId = { menuSession = null; idTarget = sess },
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { actions.newSessionInWorkspace(w.id) },
                                    modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
                                ) { Text("在此工作区新建会话") }
                            }
                        }
                        WorkspaceMenu(
                            expanded = menuWorkspace == w.id,
                            onDismiss = { menuWorkspace = null },
                            onRename = { menuWorkspace = null; renameWorkspaceTarget = w },
                            onDelete = { menuWorkspace = null; deleteWorkspaceTarget = w },
                            onNewSession = { menuWorkspace = null; actions.newSessionInWorkspace(w.id) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    /* ------------------------------ 对话框 ------------------------------ */

    idTarget?.let { target ->
        SessionIdDialog(session = target, onDismiss = { idTarget = null })
    }

    renameSessionTarget?.let { sess ->
        RenameDialog(
            title = "重命名会话",
            initial = sess.displayTitle,
            onDismiss = { renameSessionTarget = null },
            onConfirm = { newTitle ->
                actions.renameSession(sess.id, newTitle)
                renameSessionTarget = null
            },
        )
    }

    renameWorkspaceTarget?.let { w ->
        RenameDialog(
            title = "重命名工作区",
            initial = w.displayTitle,
            onDismiss = { renameWorkspaceTarget = null },
            onConfirm = { newTitle ->
                actions.renameWorkspace(w.id, newTitle)
                renameWorkspaceTarget = null
            },
        )
    }

    archiveTarget?.let { sess ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text("归档这个会话？") },
            text = {
                Text(
                    "「${sess.displayTitle}」会从工作区列表里隐藏。\n\n" +
                        "会话日志仍然完整保留在电脑上，不会被删除。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.archiveSession(sess.id)
                    archiveTarget = null
                }) { Text("归档") }
            },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("取消") } },
        )
    }

    deleteWorkspaceTarget?.let { w ->
        AlertDialog(
            onDismissRequest = { deleteWorkspaceTarget = null },
            title = { Text("删除这个工作区？") },
            text = {
                Text(
                    "「${w.displayTitle}」的**注册记录**会被删除，它不再出现在列表里。\n\n" +
                        "⚠️ 只是取消登记：电脑上的目录（${w.path}）和里面所有会话日志都不会被删除。\n\n" +
                        "以后想找回来，重新把那个目录登记成工作区即可。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    actions.deleteWorkspace(w.id)
                    deleteWorkspaceTarget = null
                }) { Text("删除注册") }
            },
            dismissButton = { TextButton(onClick = { deleteWorkspaceTarget = null }) { Text("取消") } },
        )
    }
}

/** 会话长按菜单：和电脑上点「···」是同一批操作。 */
@Composable
private fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onShowId: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("打开") }, onClick = onOpen)
        DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
        DropdownMenuItem(text = { Text("分支会话") }, onClick = onFork)
        // session id 平时不露脸（列表里显示标题），但"要去电脑上找这个会话"时
        // 它是唯一准确的抓手：命令行、日志、别的工具里都得用它
        DropdownMenuItem(text = { Text("查看 session id") }, onClick = onShowId)
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("归档", color = MaterialTheme.colorScheme.error) },
            onClick = onArchive,
        )
    }
}

/** 工作区长按菜单。 */
@Composable
private fun WorkspaceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onNewSession: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("在此新建会话") }, onClick = onNewSession)
        DropdownMenuItem(text = { Text("重命名工作区") }, onClick = onRename)
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("删除工作区", color = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
        )
    }
}

/** 通用重命名对话框。 */
@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("新名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/* ============================ 新建会话 ============================ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(
    listing: DirListing,
    busy: Boolean,
    onBack: () -> Unit,
    onBrowse: (String?) -> Unit,
    onMakeFolder: (String) -> Unit,
    onCreateHere: (String?) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }
    var wsTitle by remember { mutableStateOf("") }
    var showFolderBox by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建会话") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // 面包屑
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listing.crumbs.takeLast(4).forEachIndexed { idx, c ->
                    if (idx > 0) Text(" / ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        c.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onBrowse(c.path) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (listing.home.isNotBlank()) {
                    Text(
                        "家目录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onBrowse(listing.home) },
                    )
                }
            }

            Text(
                listing.path.ifBlank { "（读取中）" },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // 新建文件夹
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showFolderBox) {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("新文件夹名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        enabled = folderName.isNotBlank(),
                        onClick = {
                            onMakeFolder(folderName)
                            folderName = ""
                            showFolderBox = false
                        },
                    ) { Text("创建") }
                } else {
                    FilledTonalButton(onClick = { showFolderBox = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("在此新建文件夹")
                    }
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // 目录列表
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(listing.entries.size) { i ->
                    val e = listing.entries[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onBrowse(e.path) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (e.hidden) "· " else "📁 ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            e.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (e.hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
                if (listing.entries.isEmpty() && !busy && listing.path.isNotBlank()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                            Text("这个目录下没有子目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                OutlinedTextField(
                    value = wsTitle,
                    onValueChange = { wsTitle = it },
                    label = { Text("工作区标题（留空则用文件夹名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { onCreateHere(wsTitle.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("在这里新建会话") }
                Spacer(Modifier.height(4.dp))
                Text(
                    "会在电脑上把这个目录登记成 harness 工作区，并开一个会话。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ================================ 文档阅读 ================================ */

/**
 * 点开工具调用里的文件路径 / 正文里的文档路径，进到这里看内容。
 *
 * .md 走同一套 MarkdownText 渲染（表格、代码块、链接都在），
 * 其它扩展名按等宽纯文本显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    doc: DocState?,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val d = doc ?: DocState(path = "")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(d.fileName.ifBlank { "文档" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.path.isNotBlank()) {
                            Text(
                                d.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when {
                d.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())

                d.error != null -> Column(Modifier.padding(16.dp)) {
                    Text("读不到这个文件", style = MaterialTheme.typography.titleSmall, color = scheme.error)
                    Spacer(Modifier.height(6.dp))
                    Text(d.error, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "提示：正文里的相对路径是按当前会话的工作目录解析的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }

                d.content != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (d.truncated) {
                        Text(
                            "（文件较大，只显示前面一部分）",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                        )
                    }
                    Box(Modifier.padding(16.dp)) {
                        if (d.isMarkdown) {
                            MarkdownText(
                                text = d.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                                onOpenFile = onOpenFile,
                            )
                        } else {
                            SelectionContainer {
                                Text(
                                    d.content,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ================================= 聊天 ================================= */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    title: String,
    sessionId: String = "",
    messages: List<ChatMessage>,
    streamText: String,
    streamReasoning: String,
    running: Boolean,
    busy: Boolean,
    showInjected: Boolean,
    chatError: String?,
    connPhase: ConnPhase,
    question: PendingQuestion?,
    draft: String,
    onDraftChange: (String) -> Unit,
    catalog: ModelCatalog,
    switchingModel: Boolean,
    listState: LazyListState,
    followTail: Boolean,
    onFollowTailChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onAnswer: (List<QuestionAnswer>) -> Unit,
    onStopThinking: () -> Unit,
    onSwitchModel: (String, String, String?) -> Unit,
    onRefreshModels: () -> Unit,
    onOpenFile: (String) -> Unit,
    onToggleInjected: (Boolean) -> Unit,
    queue: List<QueuedMessage>,
    hasMoreHistory: Boolean,
    loadingOlder: Boolean,
    queueEditDraft: Pair<String, String>?,
    onQueueEditChange: (String, String) -> Unit,
    onQueueEditDiscard: () -> Unit,
    attachments: List<PendingAttachment>,
    uploadingName: String?,
    onAttachFile: (String, ByteArray) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onClearAttachments: () -> Unit,
    onLoadOlder: () -> Unit,
    onJumpPreviousUser: () -> Unit,
    activity: SessionActivity,
    progress: SessionProgress,
    goal: GoalInfo?,
    onClearGoal: () -> Unit,
    onOpenSession: (String) -> Unit,
    onEditQueued: (String, String) -> Unit,
    onRetractQueued: (String) -> Unit,
    onSteerQueued: (String) -> Unit,
) {
    var showModels by remember { mutableStateOf(false) }
    var editQueueTarget by remember { mutableStateOf<QueuedMessage?>(null) }
    // 点标题看 session id：列表里显示的是标题，而要去电脑上找这个会话时用的是 id
    var showSessionId by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 选文件：用系统的 SAF（不申请存储权限，用户选什么我们才读什么）
    var pickError by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedFile(context, uri) }
            when {
                picked == null -> pickError = "读不到这个文件（可能没有访问权限）"
                picked.second == null -> pickError = "文件太大（上限 ${MAX_UPLOAD_MB} MB）"
                else -> {
                    pickError = null
                    onAttachFile(picked.first, picked.second!!)
                }
            }
        }
    }

    val visible = remember(messages, showInjected) {
        if (showInjected) messages else messages.filterNot { it.isInjected }
    }

    /**
     * 是否跟随最新输出。
     *
     * 不能在"内容变化时"去看布局判断 —— 那时新内容还没进布局，
     * totalItemsCount 已经涨了、visibleItemsInfo 还是旧的，
     * 于是"最后一屏可见吗"必然为假，跟随就永远开不起来。
     *
     * 改成看**滚动位移的方向**：索引/偏移变小 = 用户自己往上翻了 → 停止跟随；
     * 滚到最后一屏可见 = 恢复跟随。内容是被追加在下方的，位移不变，
     * 所以追加本身不会误触发"停止跟随"。
     */
    LaunchedEffect(listState) {
        var prevIdx = listState.firstVisibleItemIndex
        var prevOff = listState.firstVisibleItemScrollOffset
        var wasScrolling = false
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (inProgress, idx, off) ->
            if (inProgress) {
                wasScrolling = true
                // 手指还在屏幕上、且往上挪了 → 立刻停止跟随（不能等松手，
                // 否则这期间流式输出还是会把页面拽走）
                val movedUp = idx < prevIdx || (idx == prevIdx && off < prevOff)
                if (movedUp) onFollowTailChange(false)
            } else if (wasScrolling) {
                // 只在"一次真正的滚动刚结束"时才评估是否到底。
                // ★ 不能在非滚动状态下评估：启动那一刻列表还短，
                //   最后一个 item 必然可见，会把恢复出来的 followTail=false 覆盖成 true。
                wasScrolling = false
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                onFollowTailChange(last == null || last.index >= info.totalItemsCount - 1)
            }
            prevIdx = idx
            prevOff = off
        }
    }

    LaunchedEffect(visible.size, streamText.length) {
        if (!followTail) return@LaunchedEffect
        val target = visible.size + if (streamText.isNotEmpty()) 1 else 0
        if (target > 0) runCatching { listState.scrollToItem(target - 1) }
    }

    // 滑到最顶上就自动接着往前载 —— 用户一路往上拉，不该在每页交界处停一下手
    LaunchedEffect(listState, hasMoreHistory, loadingOlder) {
        if (!hasMoreHistory || loadingOlder) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { idx ->
                if (idx <= 0 && hasMoreHistory && !loadingOlder) onLoadOlder()
            }
    }

    if (showSessionId && sessionId.isNotEmpty()) {
        SessionIdDialog(
            session = WorkspaceSession(
                id = sessionId,
                title = title,
                running = false,
                blank = false,
                updatedAt = 0L,
                cwd = null,
            ),
            onDismiss = { showSessionId = false },
        )
    }

    editQueueTarget?.let { target ->
        EditQueueDialog(
            original = target.text,
            restored = queueEditDraft?.takeIf { it.first == target.id }?.second,
            // 返回手势/点外面：只关对话框，草稿留着
            onDismiss = { editQueueTarget = null },
            onChange = { onQueueEditChange(target.id, it) },
            onDiscard = {
                onQueueEditDiscard()
                editQueueTarget = null
            },
            onConfirm = { text ->
                editQueueTarget = null
                onEditQueued(target.id, text)
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { if (sessionId.isNotEmpty()) showSessionId = true }) {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (running) "运行中" else "空闲",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (running) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 当前模型：点开切换
                    val cur = catalog.current
                    TextButton(onClick = { onRefreshModels(); showModels = true }) {
                        Text(
                            cur?.short ?: "模型",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { inner ->
        // ★ imePadding 必须加在**根容器**上：这样键盘弹起时整块内容上移，
        //   而 LazyColumn 的 weight(1f) 会跟着缩，输入框自然贴在键盘上方。
        //   以前它只加在输入行上：附件条/排队卡在输入行**外面**，不会跟着缩，
        //   内容一多就把输入框顶出屏幕（用户 2026-10-16 报的"文字被键盘遮挡"）。
        Column(Modifier.fillMaxSize().padding(inner).imePadding()) {
            // 仪表盘：轮/步 + 目标（有才显示）+ 上下文进度
            SessionDashboard(progress = progress, goal = goal, onClearGoal = onClearGoal)

            // 掉线横幅：USB 通道（adb reverse）会因拔插线/系统重协商而失效，
            // 这时聊天页如果只是空白，用户无法区分"没有消息"和"没连上"。
            val warn = chatError
                ?: if (connPhase != ConnPhase.LIVE) "未连接到 harness（检查数据线，并重跑 install-to-phone.sh 恢复 adb reverse）" else null
            if (warn != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        warn,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                // 列表最上面：更早的消息入口。
                // 一轮里工具调用能有一百多条，不给入口的话用户拉到顶也不知道
                // "是到头了还是没加载"。
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingOlder -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在载入更早的消息…", style = MaterialTheme.typography.labelMedium)
                            }
                            hasMoreHistory -> TextButton(onClick = onLoadOlder) {
                                Text("↑ 载入更早的消息")
                            }
                            else -> Text(
                                "· 已经是最早 ·",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onJumpPreviousUser) { Text("↑ 上一条我的提问") }
                    }
                }

                items(visible.size) { i ->
                    MessageRow(visible[i], onOpenFile)
                }
                if (streamText.isNotEmpty() || streamReasoning.isNotEmpty()) {
                    item {
                        StreamingRow(streamText, streamReasoning)
                    }
                }
                if (visible.isEmpty() && streamText.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "这个会话还没有消息，发一条试试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // 上翻阅读时，给一个回到最新的入口（否则得手动滑回去）
            if (!followTail) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(
                        onClick = {
                            onFollowTailChange(true)
                            val target = visible.size + if (streamText.isNotEmpty()) 1 else 0
                            if (target > 0) scope.launch { listState.animateScrollToItem(target - 1) }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text("↓ 回到最新", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            // 输入框上方的这几块（提问卡 / 活动 / 排队 / 附件条）在"键盘弹起 + 内容多"
            // 时会很高。给它们一个高度上限并允许**内部滚动** —— 否则整块会把输入框
            // 顶出屏幕，打字时看不到自己写的字。输入行永远是这个 Column 的最后一个
            // 孩子，所以它永远贴在键盘正上方。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
            // 有提问时，卡片贴在输入框上方
            if (question != null) {
                QuestionCard(pending = question, onAnswer = onAnswer)
            }

            // 这个会话在跑什么（后台任务 / 子代理）
            ActivityCard(activity = activity, onOpenSession = onOpenSession)

            // 排队消息：harness 还在跑的时候发出去的那几条。
            // 放在输入框正上方 —— 用户的视线和手指本来就在这儿，
            // "我发的那句话去哪了"必须一眼看得见，而且能改能撤。
            if (queue.isNotEmpty()) {
                QueueCard(
                    items = queue,
                    draftItemId = queueEditDraft?.first,
                    onEdit = { id, _ -> editQueueTarget = queue.firstOrNull { it.id == id } },
                    onRetract = onRetractQueued,
                    onSteer = onSteerQueued,
                )
            }

            // 选中了、还没发出去的文件（**还在手机上**，点发送时才传给电脑）。
            // 必须看得见：不然用户以为丢了，会再选一遍
            if (attachments.isNotEmpty() || uploadingName != null) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    uploadingName?.let { n ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("正在传 $n …（发送中）", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    attachments.forEach { a ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📎 ${a.name} · ${a.sizeText}", style = MaterialTheme.typography.labelMedium)
                                // ★ 这个 ✕ 原来只有 22dp（图标 14dp）—— 远低于 48dp 的最小点击区，
                                //   看起来"根本没有删除操作"（用户 2026-10-16 报的）。现在 32dp + 18dp 图标，
                                //   并配上颜色，让人一眼看得见、点得中。
                                IconButton(
                                    onClick = { onRemoveAttachment(a.localId) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除附件 ${a.name}",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    // 一次选好几张时，逐个点太累 —— 给一个明确的出口
                    if (attachments.size > 1) {
                        TextButton(onClick = onClearAttachments) {
                            Text("清空附件", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            pickError?.let {
                Text(
                    it,
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            }   // ← 关闭"输入框上方可滚区"

            HorizontalDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // 传文件：手机上选一个，传到电脑，跟这条消息一起发过去
                IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = "传文件到会话")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (attachments.isEmpty()) "给 harness 发消息…" else "可写说明，也可直接发附件…") },
                    maxLines = 6,
                )
                Spacer(Modifier.width(8.dp))
                // 正在思考/生成时给一个显眼的停止按钮，
                // 不用跑到顶栏去戳那个小 x
                if (running) {
                    FilledTonalButton(
                        onClick = onStopThinking,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("停止", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(
                    enabled = (draft.isNotBlank() || attachments.isNotEmpty()) && !busy,
                    onClick = {
                        val t = draft
                        onDraftChange("")
                        onFollowTailChange(true)   // 自己发了消息，当然要跟到最新
                        onSend(t)
                    },
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showModels) {
        ModalBottomSheet(onDismissRequest = { showModels = false }) {
            ModelPicker(
                catalog = catalog,
                switching = switchingModel,
                onPick = { provider, model, effort ->
                    onSwitchModel(provider, model, effort)
                    showModels = false
                },
            )
        }
    }
}

/**
 * 模型选择面板：按 provider 分组，选中项打勾。
 * 有推理强度的模型，选中那一行下面会展开强度选项。
 */
@Composable
private fun ModelPicker(
    catalog: ModelCatalog,
    switching: Boolean,
    onPick: (String, String, String?) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val cur = catalog.current

    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            "切换模型" + (cur?.let { "　当前：${it.provider} / ${it.model}" + (it.effort?.let { e -> " · $e" } ?: "") } ?: ""),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = scheme.primary,
        )
        if (switching) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (catalog.groups.isEmpty()) {
            Text(
                "没读到模型列表（检查连接）",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        catalog.groups.forEach { g ->
            Text(
                g.name.ifBlank { g.id },
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            g.models.forEach { m ->
                val isCurrent = cur != null && cur.provider == g.id && cur.model == m.id
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(g.id, m.id, m.defaultEffort) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.name.ifBlank { m.id },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) scheme.primary else scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) Text("✓", color = scheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        m.id + (m.description?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 只有"当前正用着、且支持推理强度"的模型才展开强度选项
                    if (isCurrent && m.efforts.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Row {
                            m.efforts.forEach { e ->
                                val on = cur?.effort == e.id
                                if (on) {
                                    FilledTonalButton(
                                        onClick = { onPick(g.id, m.id, e.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text(e.name, style = MaterialTheme.typography.labelSmall) }
                                } else {
                                    OutlinedButton(
                                        onClick = { onPick(g.id, m.id, e.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) { Text(e.name, style = MaterialTheme.typography.labelSmall) }
                                }
                                Spacer(Modifier.width(6.dp))
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }

        if (catalog.failures.isNotEmpty()) {
            Text(
                "部分 provider 加载失败：" + catalog.failures.joinToString("；"),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
            )
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage, onOpenFile: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isUser = m.role == "user"
    val isTool = m.role == "tool"
    val isToolResult = m.role == "tool-result"

    var expandReasoning by remember(m.seq) { mutableStateOf(false) }
    var expandArgs by remember(m.seq) { mutableStateOf(false) }
    var expandResult by remember(m.seq) { mutableStateOf(false) }

    // Box(contentAlignment=) 要的是 Alignment，Alignment.Start/End 是
    // Alignment.Horizontal，传进去编译不过，所以用 TopStart/TopEnd。
    val bg = when {
        isUser -> scheme.primaryContainer
        isTool || isToolResult -> scheme.surface
        else -> scheme.surfaceVariant
    }
    val textColor = when {
        isUser -> scheme.onPrimaryContainer
        isTool || isToolResult -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    val align: Alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = align) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(10.dp),
        ) {
            val tag = when {
                isUser -> if (m.isInjected) "注入 · ${m.source}" else "你"
                m.role == "assistant" -> "Harness"
                isTool -> "工具 · ${m.name ?: ""}"
                else -> "工具结果" + (if (m.isError) " · 失败" else "")
            }
            Text(
                tag + "  " + formatClock(m.time),
                style = MaterialTheme.typography.labelSmall,
                color = if (m.isError) scheme.error else scheme.onSurfaceVariant,
            )

            // 思考：和电脑上一样折叠成独立区块，默认收起，点标题展开
            val reasoning = m.reasoning
            if (!reasoning.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (expandReasoning) "▾ " else "▸ ") + "思考（${reasoning.length} 字）",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                    modifier = Modifier.clickable { expandReasoning = !expandReasoning },
                )
                if (expandReasoning) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 工具调用的摘要行：一眼能看出干了什么，细节收在"参数"里
            if (isTool) {
                val title = m.title
                if (!title.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    val path = m.titlePath
                    SelectionContainer {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            // 是文件路径就上色 + 下划线 + 可点：点开看渲染好的文档
                            color = if (path != null) scheme.primary else textColor,
                            textDecoration = if (path != null) TextDecoration.Underline else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (path != null) {
                                Modifier.clickable { onOpenFile(path) }
                            } else {
                                Modifier
                            },
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        (if (expandArgs) "▾ " else "▸ ") + "参数",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary,
                        modifier = Modifier.clickable { expandArgs = !expandArgs },
                    )
                    if (expandArgs) {
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                m.text.ifBlank { "(空)" },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (m.text.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            m.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            color = textColor,
                        )
                    }
                }
            } else if (m.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                if (isToolResult) {
                    // 工具结果默认收起：一般很长，展开会把整屏读的东西全挤走。
                    // 收起时给一行摘要 + 首行预览，要看细节再点开。
                    val lines = m.text.count { it == '\n' } + 1
                    Text(
                        (if (expandResult) "▾ " else "▸ ") +
                            "结果 · $lines 行 · ${m.text.length} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.isError) scheme.error else scheme.primary,
                        modifier = Modifier.clickable { expandResult = !expandResult },
                    )
                    Spacer(Modifier.height(4.dp))
                    if (expandResult) {
                        SelectionContainer {
                            Text(
                                m.text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                ),
                                color = if (m.isError) scheme.error else textColor,
                            )
                        }
                    } else {
                        val preview = m.text.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                        if (preview.isNotBlank()) {
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    // 人和助手的正文都过 markdown：**粗体**、`代码`、链接、列表…
                    // 外面套 SelectionContainer，长按即可选中 + 系统工具条的「复制」。
                    //
                    // 之前这里为了"怕长按选择和点链接抢手势"去掉了 SelectionContainer，
                    // 结果就是正文整个没法复制 —— 那是想多了：
                    // Compose 里点按归 LinkAnnotation、长按归选择，两者不冲突。
                    SelectionContainer {
                        MarkdownText(
                            text = m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (m.isError) scheme.error else textColor,
                            onOpenFile = onOpenFile,
                        )
                    }
                }
            }

            if (m.interrupted) {
                Spacer(Modifier.height(4.dp))
                Text("（被中断）", style = MaterialTheme.typography.labelSmall, color = scheme.error)
            }
        }
    }
}

/**
 * harness 提问卡片 —— 对应电脑上的选项框。
 *
 * 单选：点选项直接作答（和电脑上的手感一致）。
 * 多选：勾完点「提交」。
 * 两种都额外给一个自由文本输入，对应协议里的 custom 字段。
 */
@Composable
private fun QuestionCard(
    pending: PendingQuestion,
    onAnswer: (List<QuestionAnswer>) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val multi = pending.questions.count { it.multiSelect } > 0
    var picked by remember(pending.id) { mutableStateOf(setOf<String>()) }
    var custom by remember(pending.id) { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.primaryContainer)
            .padding(12.dp),
    ) {
        Text(
            "harness 在等你的回答",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onPrimaryContainer,
        )
        Spacer(Modifier.height(6.dp))

        pending.questions.forEach { q ->
            if (!q.header.isNullOrBlank()) {
                Text(q.header, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
            Text(q.question, style = MaterialTheme.typography.bodyLarge, color = scheme.onPrimaryContainer)
            if (!q.detail.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(q.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))

            if (q.multiSelect) {
                q.options.forEach { opt ->
                    val on = picked.contains(opt.label)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                picked = if (on) picked - opt.label else picked + opt.label
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Text(
                            opt.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer,
                        )
                    }
                }
            } else {
                q.options.forEach { opt ->
                    OutlinedButton(
                        onClick = {
                            onAnswer(
                                listOf(
                                    QuestionAnswer(
                                        id = q.id,
                                        selected = listOf(opt.label),
                                        custom = custom.ifBlank { null },
                                    )
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                            if (!opt.description.isNullOrBlank()) {
                                Text(
                                    opt.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it },
            label = { Text("或自己填一个答案") },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (multi) {
                FilledTonalButton(
                    enabled = picked.isNotEmpty(),
                    onClick = {
                        onAnswer(
                            listOf(
                                QuestionAnswer(
                                    id = pending.questions.first().id,
                                    selected = picked.toList(),
                                    custom = custom.ifBlank { null },
                                )
                            )
                        )
                    },
                ) { Text("提交") }
            } else {
                FilledTonalButton(
                    enabled = custom.isNotBlank(),
                    onClick = {
                        onAnswer(
                            listOf(
                                QuestionAnswer(
                                    id = pending.questions.first().id,
                                    selected = emptyList(),
                                    custom = custom,
                                )
                            )
                        )
                    },
                ) { Text("用这个回答") }
            }
        }
    }
}

@Composable
private fun StreamingRow(text: String, reasoning: String) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.TopStart) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceVariant)
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text("Harness 正在输出…", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            if (reasoning.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // 流式输出也过 markdown。未闭合的标记在流中途会短暂显示成字面量，
                // 但定稿后就会被解析掉 —— 比全程显示 ** 要好。
                MarkdownText(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

/* ================================= 设置 ================================= */

@Composable
fun SettingsScreen(
    endpointsRaw: String,
    activeBaseUrl: String,
    token: String,
    showInjected: Boolean,
    notifyEnabled: Boolean,
    errorLog: List<String>,
    onClearErrorLog: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
    onTest: (String, String) -> Unit,
    onToggleInjected: (Boolean) -> Unit,
) {
    var eps by remember { mutableStateOf(endpointsRaw) }
    var tok by remember { mutableStateOf(token) }
    var notify by remember { mutableStateOf(notifyEnabled) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("桥接地址") {
            Text(
                "一行一个地址，按顺序挑第一个能连上的。典型两行：\n" +
                    "  http://127.0.0.1:3080             在家插线（adb reverse）\n" +
                    "  http://dsh-home.xxx.ts.net:3080   在外走 Tailscale\n" +
                    "这样从家里出门不需要改任何设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            KeyValue("当前使用", activeBaseUrl, mono = true)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = eps,
                onValueChange = { eps = it },
                label = { Text("端点（一行一个）") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tok,
                onValueChange = { tok = it },
                label = { Text("令牌（桥接未设 token 时留空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row {
                FilledTonalButton(onClick = { onTest(eps, tok) }) { Text("逐个测试") }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = { onSave(eps, tok, notify) }) { Text("保存并重连") }
            }
        }

        SectionCard("显示") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("显示注入消息", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "harness 注入的运行时提醒、技能目录等上下文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showInjected, onCheckedChange = onToggleInjected)
            }
        }

        SectionCard("通知栏状态") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("常驻显示 harness 状态", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "开启后不打开 App 也能在通知栏看到运行中的会话与任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = notify, onCheckedChange = { notify = it })
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "改完这里记得点上面的「保存并重连」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 错误记录：出错提示以前只在屏幕上闪一下就没了，事后问"刚才报的什么错"
        // 电脑侧什么也查不到。落盘在这里，不用数据线也能看。
        SectionCard("错误记录（最近 ${errorLog.size} 条）") {
            Text(
                "这里只记「看起来是错」的提示（带「失败/错误/连不上」等字样），" +
                    "最新的在最上面。电脑侧的桥接不记请求，所以这份是手机独有的现场。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorLog.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还没有记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(6.dp))
                errorLog.take(20).forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
                if (errorLog.size > 20) {
                    Text(
                        "（只显示最近 20 条，共 ${errorLog.size} 条）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onClearErrorLog) { Text("清空记录") }
            }
        }

        SectionCard("关于") {
            KeyValue("应用", "DSH 手机端")
            KeyValue("桥接", "/phone/* (HTTP + SSE)")
            KeyValue("安装", "adb install，不 root、不解锁")
        }

        Spacer(Modifier.height(24.dp))
    }
}

/* ============================== 排队消息 ============================== */

/**
 * 「跑着的时候又发的那几条」。
 *
 * 为什么必须显示：消息发出去之后被 harness 收进队列，要等这一轮跑完才执行 ——
 * 如果界面上什么都不显示，用户会以为"发出去了却没人理"，
 * 然后重复发一遍。所以条数、内容、能不能改/撤都要摆在明面上。
 */
@Composable
fun QueueCard(
    items: List<QueuedMessage>,
    /** 有未保存修改的那一条（界面上打个标，免得用户不知道还能捡回来）。 */
    draftItemId: String? = null,
    onEdit: (String, String) -> Unit,
    onRetract: (String) -> Unit,
    onSteer: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⏳", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Text(
                    "${items.size} 条排队中 · 等这一轮跑完依次发出",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起 ▾" else "展开 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (expanded) {
                items.forEachIndexed { i, item ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(
                        item.text.ifBlank { "（空）" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (draftItemId == item.id) {
                        Text(
                            "● 有未保存的修改（点「改」接着写）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onEdit(item.id, item.text) }) { Text("改") }
                        TextButton(onClick = { onRetract(item.id) }) {
                            Text("撤回", color = MaterialTheme.colorScheme.error)
                        }
                        if (item.placement == "queued") {
                            TextButton(onClick = { onSteer(item.id) }) { Text("立刻插话") }
                        }
                    }
                }
            }
        }
    }
}

/** 改一条还没执行的排队消息。 */
@Composable
fun EditQueueDialog(
    original: String,
    /** 上次没保存完的修改（有就恢复，并明说）。 */
    restored: String? = null,
    onDismiss: () -> Unit,
    onChange: (String) -> Unit = {},
    onDiscard: () -> Unit = {},
    onConfirm: (String) -> Unit,
) {
    var text by remember(original, restored) { mutableStateOf(restored ?: original) }
    AlertDialog(
        // 返回手势/点外面 = 意外：**留着草稿**，下次打开自动恢复
        onDismissRequest = onDismiss,
        title = { Text("改这条排队消息") },
        text = {
            Column {
                Text(
                    "改完还是排在队列里，等这一轮跑完才发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (restored != null && restored != original) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已恢复上次没保存的修改（返回手势不会丢）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    // 每敲一下都落盘：被电话/返回手势打断也能捡回来
                    onValueChange = { text = it; onChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 8,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("保存") }
        },
        // 「放弃」是明确表态：把草稿也删掉，免得下次又被恢复出来
        dismissButton = { TextButton(onClick = onDiscard) { Text("放弃修改") } },
    )
}

/* ========================= 会话里的"后台在跑什么" ========================= */

/**
 * 聊天页顶上那张"在跑什么"的卡。
 *
 * 为什么需要：电脑端能一眼看到这个会话挂了几个后台任务、派了几个子代理，
 * 手机上原来什么都看不到 —— 于是会出现"它到底是在干活还是卡住了"的疑问。
 * 只在**有东西**的时候出现，免得平时占地方。
 */
@Composable
fun ActivityCard(
    activity: SessionActivity,
    onOpenSession: (String) -> Unit,
) {
    // 没在跑、也没有刚结束的，就不占地方
    val now = System.currentTimeMillis()
    if (!activity.worthShowing(now)) return
    // 默认展开在跑的那些；全都停了就收起，别抢注意力
    var expanded by remember { mutableStateOf(!activity.nothingRunning) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val rj = activity.runningJobs.size
                val rs = activity.runningSubagents.size
                Text(
                    buildString {
                        append("⚙ ")
                        if (rj > 0) append("$rj 个后台任务在跑") else append("无后台任务")
                        append(" · ")
                        append("👥 ")
                        if (rs > 0) append("$rs 个子代理在跑") else append("无子代理")
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起 ▾" else "展开 ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                if (activity.jobs.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("后台任务", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    activity.jobs.forEach { j ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                if (j.status == "running") "▶" else if (j.status == "stopping") "⏹" else "✓",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(20.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    j.label.ifBlank { j.kind.ifBlank { j.id } },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    buildString {
                                        append(j.status)
                                        if (j.kind.isNotBlank()) append(" · ").append(j.kind)
                                        if (j.startedAt > 0) {
                                            append(" · 已跑 ")
                                            append(formatDuration(j.startedAt, j.finishedAt))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                j.detail?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (activity.subagents.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("子代理（点头部可以进去看）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    activity.subagents.forEach { sa ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpenSession(sa.id) }
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (sa.running) "▶" else "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sa.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                // 层级用缩进表示：子代理下面还挂着孙代理时一眼能看出来
                                (if (sa.depth > 1) "↳ ".repeat(sa.depth - 1) else "") + sa.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                if (sa.running) "在跑" else relativeTime(sa.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sa.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ============================ 会话仪表盘 ============================ */

/** 目标的阶段 → 中文（认不出来就原样显示，别装作懂）。 */
private fun goalPhaseText(phase: String): String = when (phase) {
    "active" -> "进行中"
    "blocked" -> "受阻"
    "paused" -> "已暂停"
    "complete", "completed" -> "已完成"
    else -> phase.ifBlank { "—" }
}

/**
 * 聊天页顶上那条仪表盘：跑到第几轮第几步、上下文用了多少、有没有目标在推进。
 *
 * 为什么放在聊天页最上面而不是塞进消息流：这些是**随时要瞄一眼**的状态，
 * 不是历史。收在一条窄条里，点开才展开细节 —— 不抢聊天的地方。
 *
 * 目标**有才显示**（用户明确要求）：没有目标的时候不该出现一行"无目标"。
 */
@Composable
fun SessionDashboard(
    progress: SessionProgress,
    goal: GoalInfo?,
    onClearGoal: () -> Unit = {},
    fullScreen: Boolean = false,
) {
    // 已完成的目标只"刚完成时"露一下脸，之后收起来 ——
    // 宿主把完成的目标当墓碑留在注册表里，不清就永远在，挂在顶上纯噪音
    val shownGoal = goal?.takeIf { goalWorthShowing(it, System.currentTimeMillis()) }
    if (!progress.hasAnything && shownGoal == null) return
    var expanded by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val ratio = progress.contextRatio

    if (confirmClear && shownGoal != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除这个目标？") },
            text = {
                Text(
                    "会把当前目标从 harness 的目标注册表里清掉（会话日志里的历史仍然保留）。\n\n" +
                        "「${shownGoal.objective.take(60)}」",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearGoal() }) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔄", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(4.dp))
                Text(
                    progress.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (shownGoal != null) {
                    val blocked = shownGoal.phase == "blocked"
                    Text(
                        "🎯 " + goalPhaseText(shownGoal.phase) +
                            if (shownGoal.maxGoalRounds != null && shownGoal.maxGoalRounds > 0) {
                                " ${shownGoal.roundsStarted}/${shownGoal.maxGoalRounds}"
                            } else if (shownGoal.roundsStarted > 0) {
                                " ${shownGoal.roundsStarted} 轮"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (ratio != null) {
                    // 刚开场时是 0.02%，写"0%"会让人以为没在统计 —— 小于 1% 就写 <1%
                    val pct = (ratio * 100).toInt()
                    Text(
                        "📊 " + if (pct <= 0 && ratio > 0f) "<1%" else "$pct%",
                        style = MaterialTheme.typography.labelMedium,
                        color = contextColor(ratio),
                    )
                }
            }

            // 上下文进度条：只在**知道窗口**的时候画 —— 不知道就只显示数字，
            // 画一根瞎猜的条比不画更糟
            if (ratio != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = contextColor(ratio),
                    trackColor = MaterialTheme.colorScheme.surface,
                )
            }

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("当前：第 ").append(progress.turn).append(" 轮")
                        if (progress.step > 0) append("，第 ").append(progress.step).append(" 步")
                        append("（累计 ").append(maxOf(progress.turns, progress.turn)).append(" 轮）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.contextUsed > 0) {
                    // 与电脑端右下角**同一个数、同一种写法**（~617.9K / 1M，62%）。
                    // 以前这里只显示 inputTokens，也没算缓存命中，所以两边对不上
                    val pct = contextPercent(progress.contextUsed, progress.contextWindow)
                    Text(
                        if (pct != null) {
                            "上下文：~${formatTokens(progress.contextUsed)} / " +
                                "${formatTokens(progress.contextWindow)}（$pct%）"
                        } else {
                            "上下文：~${formatTokens(progress.contextUsed)}（窗口大小未知）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                shownGoal?.let { g ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "🎯 目标（${goalPhaseText(g.phase)}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (g.phase == "blocked") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        g.objective,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 8 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (g.phase == "blocked" && !g.blockedReason.isNullOrBlank()) {
                        Text(
                            "受阻原因：" + g.blockedReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (g.phase == "complete" || g.phase == "completed" || g.phase == "blocked") {
                        TextButton(onClick = { confirmClear = true }) {
                            Text("清除这个目标", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 上下文占用越高越显眼：过半转黄、八成转红。 */
@Composable
private fun contextColor(ratio: Float): Color = when {
    ratio >= 0.85f -> MaterialTheme.colorScheme.error
    ratio >= 0.6f -> Color(0xFFE5C07B)
    else -> MaterialTheme.colorScheme.primary
}

/**
 * 「查看 session id」。
 *
 * 为什么值得给一个入口：列表上显示的是**标题**，而标题可以重名、可以随时改；
 * session id 才是唯一准确的抓手 —— 去电脑上查日志、在命令行里 `dsh` 打开、
 * 或者在电脑端搜索时，要的都是它。
 *
 * 顺带**一键复制**：这种字符串在手机上手动划选是折磨。
 */
@Composable
private fun SessionIdDialog(
    session: WorkspaceSession,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("session id") },
        text = {
            Column {
                Text(
                    session.displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        session.id,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        ),
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "电脑上按这个 id 可以找回这个会话（列表里显示的是标题，标题可以重名）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(session.id))
                onDismiss()
            }) { Text("复制") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 上传上限（桥接那边也是这个数，超了会被 413 挡回来）。 */
private const val MAX_UPLOAD_MB = 64

/**
 * 读用户选中的文件：返回 (文件名, 字节)；读不到返回 null，太大返回 (名字, null)。
 *
 * 为什么在 UI 层读：SAF 给的是一次性授权 URI，读取要用 ContentResolver
 * （只有 Context 有），而 ViewModel 刻意不带 Context。
 */
private fun readPickedFile(context: android.content.Context, uri: android.net.Uri): Pair<String, ByteArray?>? {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_UPLOAD_MB * 1024 * 1024) return@use null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()
    return name to bytes
}
