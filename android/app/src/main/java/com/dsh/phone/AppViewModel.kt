package com.dsh.phone

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import org.json.JSONObject

enum class ConnPhase { IDLE, CONNECTING, LIVE, ERROR }

data class ConnStatus(
    val phase: ConnPhase = ConnPhase.IDLE,
    val latencyMs: Long = -1L,
    val message: String? = null,
)

/**
 * 全局状态：harness 快照 + 当前会话消息 + 两条 SSE 长连接。
 *
 * 实时性来源有两层：
 *  1. `/phone/events`  — 整机状态（会话列表、运行中任务、goal），事件驱动推送
 *  2. `/phone/sessions/<id>/live` — 会话内消息与模型输出增量
 * 两者断开时都有兜底轮询，因此界面不会卡死在旧数据上。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    /**
     * 当前选中的端点。空串表示还没解析过。
     *
     * 端点是有序的（在家 USB = 127.0.0.1，在外 = tailnet 地址），
     * 每次要用网络时先看当前端点还活着吗，不活就按顺序重挑一个 ——
     * 这样手机从家里出门（USB 断、tailnet 通）会自动切过去，反之亦然。
     */
    @Volatile
    private var activeBase: String = ""

    private val _state = MutableStateFlow(HarnessState.EMPTY)
    val state: StateFlow<HarnessState> = _state.asStateFlow()

    private val _conn = MutableStateFlow(ConnStatus())
    val conn: StateFlow<ConnStatus> = _conn.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _streamReasoning = MutableStateFlow("")
    val streamReasoning: StateFlow<String> = _streamReasoning.asStateFlow()

    private val _openSessionId = MutableStateFlow("")
    val openSessionId: StateFlow<String> = _openSessionId.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * 聊天页自己的错误提示。
     *
     * 必须单独记一份：聊天页的空白和"真的没有消息"看起来一模一样，
     * 而 adb reverse 掉线（拔插数据线、USB 重协商）是常态而非异常。
     */
    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _baseUrl = MutableStateFlow(settings.endpointList().first())
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _endpoints = MutableStateFlow(settings.endpointList())
    val endpoints: StateFlow<List<String>> = _endpoints.asStateFlow()

    private val _token = MutableStateFlow(settings.token)
    val token: StateFlow<String> = _token.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(settings.notificationEnabled)
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    /** 电脑上的工作区（历史 + 当前），会话按工作区分组。 */
    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceInfo>> = _workspaces.asStateFlow()

    /** 新建会话时的目录浏览状态。 */
    private val _listing = MutableStateFlow(DirListing.EMPTY)
    val listing: StateFlow<DirListing> = _listing.asStateFlow()

    private val _listingBusy = MutableStateFlow(false)
    val listingBusy: StateFlow<Boolean> = _listingBusy.asStateFlow()

    /** 未读回复的会话 id 集合。 */
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread.asStateFlow()

    /**
     * 现在算「正在工作」的会话 id 集合。
     *
     * 为什么要单独一份：三个板块（未读 / 正在工作 / 最近）必须**互斥**，
     * 而"是否在跑"这个判断在两个板块里要用同一个答案，
     * 各处自己算就迟早会打架 —— 表现就是一个会话同时出现在两块里。
     */
    private val _working = MutableStateFlow<Set<String>>(emptySet())
    val working: StateFlow<Set<String>> = _working.asStateFlow()

    /**
     * 手机上最近打开过的会话（新 → 旧，值 = 最后一次打开的时间）。
     *
     * 纯手机侧的记忆，电脑端完全不变。只用来喂「最近会话」板块。
     */
    private val _recentAt = MutableStateFlow(settings.recentOpenedAt())
    val recentAt: StateFlow<Map<String, Long>> = _recentAt.asStateFlow()

    /** 只有这个时间窗内的回复才算「未读提醒」，免得翻出几个月的旧会话。 */
    private val unreadWindowMs = 7L * 24 * 3600 * 1000

    /**
     * 记下来见过的 activity，且只增不减。
     *
     * 为什么需要：标记已读要用「这个会话最新的回复 seq」，而那个值只有在
     * 状态/工作区刷新之后才知道。用户点进去看一眼马上退出时，还没等到那次刷新，
     * 于是 watermark 从来没写进去 —— 表现就是"点过了还是未读"。
     * 用这张表，点进去的瞬间就能标。
     */
    private val knownActivity = HashMap<String, ActivityInfo>()

    /**
     * 「这个会话现在在跑吗」的实时答案。
     *
     * 不能只靠 5 秒一次的状态轮询 —— 那样停止按钮该出现的时候不出现。
     * 会话实时流里的 turn/start、turn/end 一到就更新。
     */
    private val _liveRunning = MutableStateFlow(false)
    val liveRunning: StateFlow<Boolean> = _liveRunning.asStateFlow()

    /**
     * 用户展开着的工作区（没收起的那些）。
     *
     * 放这里而不是会话页的 remember：进聊天页再返回、甚至退出 App 重进，
     * 展开状态都还在 —— 这是"知道我最近看过什么"的前提。
     */
    private val _expandedWorkspaces = MutableStateFlow(settings.expandedWorkspaces)
    val expandedWorkspaces: StateFlow<Set<String>> = _expandedWorkspaces.asStateFlow()

    /**
     * 会话页的滚动位置。
     *
     * 放这里的原因和展开状态一样：会话页的 remember 在进会话时会被销毁，
     * 返回时列表就跳回顶部了 —— 用户就看不到"我刚看的是哪个工作区的哪个会话"。
     * LazyListState 本身是个稳定对象，跨组合持有是安全的。
     */
    val sessionsListState = LazyListState()

    /** 点一下标题：展开 ↔ 收起（只有用户这个动作会收起）。 */
    fun toggleWorkspace(workspaceId: String) {
        if (workspaceId.isEmpty()) return
        val cur = _expandedWorkspaces.value
        val next = if (cur.contains(workspaceId)) cur - workspaceId else cur + workspaceId
        _expandedWorkspaces.value = next
        settings.expandedWorkspaces = next
    }

    /** 只展开、不收起（打开会话时用，避免把用户手动收起的又弹开）。 */
    private fun expandWorkspace(workspaceId: String) {
        if (workspaceId.isEmpty() || _expandedWorkspaces.value.contains(workspaceId)) return
        val next = _expandedWorkspaces.value + workspaceId
        _expandedWorkspaces.value = next
        settings.expandedWorkspaces = next
    }

    /**
     * 聊天页的滚动位置与"是否跟随最新"。
     *
     * 和会话页一样，原本是 ChatScreen 里的 remember，一退出就被销毁。
     * 用户"看上一轮看到一半、退出、再点回来"就要接着看，所以必须跨组合存活。
     */
    private val _chatListState = MutableStateFlow(LazyListState())
    val chatListState: StateFlow<LazyListState> = _chatListState.asStateFlow()

    private val _chatFollowTail = MutableStateFlow(true)
    val chatFollowTail: StateFlow<Boolean> = _chatFollowTail.asStateFlow()

    /** 这两个状态属于哪个会话 —— 换会话才重置，同会话保留。 */
    private var chatStateSessionId = ""

    /** 待恢复的阅读位置（等历史加载完再滚过去）。 */
    private var pendingScrollRestore: SavedScroll? = null

    fun setChatFollowTail(value: Boolean) {
        _chatFollowTail.value = value
    }

    /** 把当前阅读位置存盘（退出聊天页、App 转后台时调）。 */
    fun saveChatScroll() {
        val sid = chatStateSessionId
        if (sid.isEmpty()) return
        val st = _chatListState.value
        settings.saveChatScroll(
            sid,
            st.firstVisibleItemIndex,
            st.firstVisibleItemScrollOffset,
            _chatFollowTail.value,
        )
    }

    /**
     * 当前会话未发送的草稿。
     *
     * 放 ViewModel 而不是 ChatScreen 的 remember：离开聊天页再回来、
     * 甚至退出 App 重进，草稿都还在。落盘见 Settings.draft。
     */
    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()
    private var draftSaveJob: Job? = null

    /** 正在看的文档（null = 没打开）。 */
    private val _doc = MutableStateFlow<DocState?>(null)
    val doc: StateFlow<DocState?> = _doc.asStateFlow()

    /**
     * 上面还有没有更早的消息（聊天页往上翻的边界）。
     *
     * 手机一次只拿一页；没有这个标记的话，用户拉到顶也分不清
     * "到头了"和"没加载"。
     */
    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * 已经传到电脑、等着跟下一条消息一起发出去的文件。
     *
     * 两步走（宿主的设计）：先 upload 换一张 receipt，再放进 prompt 的 content。
     * 所以在"传完"和"发出去"之间会有一小段挂着的时间 —— 这段时间要看得见、能删。
     */
    private val _attachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val attachments: StateFlow<List<PendingAttachment>> = _attachments.asStateFlow()

    /** 正在传（用来显示个转圈，别让用户以为没反应）。 */
    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading.asStateFlow()

    /**
     * 正在改的那条排队消息的草稿（itemId → 正文）。
     *
     * 落盘原因和聊天草稿一样：改排队消息时被返回手势/电话打断是常事，
     * 而那段文字是刚打出来的 —— 丢了最气人。
     */
    private val _queueEditDraft = MutableStateFlow<Pair<String, String>?>(null)
    val queueEditDraft: StateFlow<Pair<String, String>?> = _queueEditDraft.asStateFlow()

    /**
     * 这个会话里"跑着的时候发出去、还没轮到执行"的消息。
     *
     * 来源两条：连上实时流时桥接先推一份全量，之后每次变动推增量；
     * 打开会话时再主动 GET 一次兜底。
     */
    private val _queue = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queue: StateFlow<List<QueuedMessage>> = _queue.asStateFlow()

    /**
     * 当前会话的仪表盘进度（轮/步、上下文窗口与占用）。
     *
     * 分两层，**不能一锅炖**（见 [overlayProgress]）：
     *   - 权威层 = 桥接给的（state 镜像 + 实时 progress 事件）。上下文那个数就是
     *     电脑右下角显示的同一个（宿主的 `contextPressure` 投影，含缓存命中）；
     *   - 兜底层 = 从历史消息里按同口径推的，只在权威层还没有值的时候顶上
     *     （冷会话、桥接刚重载时）。
     *
     * 以前是把所有来源取最大值，结果是"手机上显示的和电脑上不一样" ——
     * 我原来只数了 inputTokens，没把缓存命中/写入算进去。
     */
    private var progressAuthoritative = SessionProgress()
    private var progressFallback = SessionProgress()
    private val _progress = MutableStateFlow(SessionProgress())
    val progress: StateFlow<SessionProgress> = _progress.asStateFlow()

    private fun publishProgress() {
        _progress.value = overlayProgress(progressAuthoritative, progressFallback)
    }

    /** 桥接来的进度（权威）：上下文以它为准。 */
    private fun setAuthoritativeProgress(vararg sources: SessionProgress?) {
        progressAuthoritative = mergeProgress(progressAuthoritative, *sources)
        publishProgress()
    }

    /** 历史里推出来的进度（兜底）：权威缺项时才显示它。 */
    private fun setFallbackProgress(source: SessionProgress?) {
        progressFallback = mergeProgress(progressFallback, source)
        publishProgress()
    }

    /** 主机侧可路由的模型目录。 */
    private val _models = MutableStateFlow(ModelCatalog.EMPTY)
    val models: StateFlow<ModelCatalog> = _models.asStateFlow()

    private val _switchingModel = MutableStateFlow(false)
    val switchingModel: StateFlow<Boolean> = _switchingModel.asStateFlow()

    /** harness 正在等手机回答的问题（ask_user_question）。 */
    private val _question = MutableStateFlow<PendingQuestion?>(null)
    val question: StateFlow<PendingQuestion?> = _question.asStateFlow()

    /**
     * App 是否在前台。
     *
     * 后台时即使聊天页还开着也不报心跳 —— 否则提问会被推给一个用户看不见的屏幕，
     * 而电脑那边要干等超时。切回前台心跳立刻恢复。
     */
    @Volatile
    private var foreground = false

    fun setForeground(value: Boolean) {
        if (!value) saveChatScroll()
        foreground = value
        // 切前台时重算一次，未读角标立刻更新
        refreshUnread()
    }

    /**
     * 重算「正在工作」的会话集合。
     *
     * 两份快照都来自电脑上同一次 sessionController.list()：
     * state 每 ~5 秒一次（桥接还会推 state 事件，更快），
     * 工作区树 ~20 秒一次。所以按「谁新用谁」：state 里有这个会话就用 state 的，
     * 不在 state 最近 60 条里才回退到工作区树那份。
     *
     * **不取并集**：并集会让一个 20 秒前就做完的会话被旧快照按在「正在工作」
     * 里出不来，于是"未读"永远不出现 —— 那正是要修的问题。
     */
    private fun refreshWorking() {
        val fresh = HashMap<String, Boolean>()
        _state.value.sessions.forEach { fresh[it.id] = it.running }
        val out = HashSet<String>()
        _workspaces.value.forEach { w ->
            w.sessions.forEach { s ->
                if (fresh[s.id] ?: s.running) out.add(s.id)
            }
        }
        // 手机正开着的那个会话：实时流的 turn/start 比 5 秒轮询还快
        val open = _openSessionId.value
        if (open.isNotEmpty() && _liveRunning.value) out.add(open)
        _working.value = out
    }

    /** 实时流报来的运行态：立刻生效，三个板块的分类也要跟着动。 */
    private fun setLiveRunning(value: Boolean) {
        if (_liveRunning.value == value) return
        _liveRunning.value = value
        // refreshUnread 内部会先重算「正在工作」，再据此重算未读
        refreshUnread()
    }

    /**
     * 重算未读，并顺手把「正开着的会话 + 前台」标成已读。
     *
     * 把"标记已读"放在这里而不是散在各处：只要满足
     * 「前台 && 开着这个会话」，它就不可能长期显示为未读。
     */
    private fun refreshUnread() {
        val now = System.currentTimeMillis()
        val open = _openSessionId.value
        if (foreground && open.isNotEmpty()) {
            val a = activityOf(open)
            if (a != null) settings.markReplySeen(open, a.lastReplySeq)
        }
        refreshWorking()
        val working = _working.value
        val out = HashSet<String>()
        // 先把两个来源都记进已知表（只增不减），标记已读时才有据可依
        _workspaces.value.forEach { w -> w.sessions.forEach { rememberActivity(it.id, it.activity) } }
        _state.value.sessions.forEach { rememberActivity(it.id, it.activity) }
        // 只以工作区为来源：会话页的「未读」区块也是遍历工作区，
        // 两边同源才不会出现"角标 21、区块列 20"这种对不上的情况。
        // （工作区覆盖全部会话，比 state 里那 60 条最近会话更全。）
        //
        // ★ 还在跑的会话一律不算未读。
        //   一轮里助手可能已经发过好几条消息（调工具前后的说明、中间结论），
        //   它们都带 seq，但都不是**最终回复**。把这些算成「未读回复」等于骗人：
        //   点进去只看到半截。它该待的地方是「正在工作」；跑完、人再看，才算真未读。
        _workspaces.value.forEach { w ->
            w.sessions.forEach { sess ->
                if (!working.contains(sess.id) &&
                    isUnread(sess.activity, settings.seenReplySeq(sess.id), now, unreadWindowMs)
                ) {
                    out.add(sess.id)
                }
            }
        }
        _unread.value = out
        refreshProgress()
    }

    /** 打开一个会话时记一笔（工作区树、未读卡片、通知点进来都走 openSession）。 */
    private fun rememberOpened(sessionId: String) {
        if (sessionId.isBlank()) return
        settings.markSessionOpened(sessionId)
        _recentAt.value = settings.recentOpenedAt()
    }

    /** 「最近会话」板块右上角的「清空」。 */
    fun clearRecent() {
        settings.clearRecent()
        _recentAt.value = emptyMap()
        say("已清空最近会话（电脑上的会话没有任何变化）")
    }

    /** 归档/删除之后，最近列表里也不该再留着找不到的会话。 */
    private fun pruneRecent(list: List<WorkspaceInfo>) {
        val existing = HashSet<String>()
        list.forEach { w -> w.sessions.forEach { existing.add(it.id) } }
        if (_recentAt.value.keys.all { existing.contains(it) }) return
        settings.pruneRecent(existing)
        _recentAt.value = settings.recentOpenedAt()
    }

    /** 从整机快照里取某个会话的进度（桥接镜像的那份）。 */
    private fun stateProgress(sessionId: String): SessionProgress {
        if (sessionId.isEmpty()) return SessionProgress()
        return _state.value.sessions.firstOrNull { it.id == sessionId }?.progress ?: SessionProgress()
    }

    /**
     * 应用一份整机快照：状态、未读、以及**待答问题**。
     *
     * 三个入口（状态 SSE、兜底轮询、手动刷新）都必须走这里 ——
     * 谁也不能忘了问题那一步。
     */
    private fun applyState(parsed: HarnessState) {
        _state.value = parsed
        refreshUnread()
        syncQuestion(parsed)
    }

    /**
     * 把状态里带的待答问题接到界面上。
     *
     * 会话流（快通道）断了也不会漏：状态轮询每 3~5 秒一次，
     * 桥接只要还在替手机拿着这个问题，下一轮就会带上它。
     *
     * **只增不清**：清除只由 question-done、回答成功、切会话负责。
     * 若这里"没看到就清"，一份早于问题到达的快照会把刚显示出来的卡片抹掉。
     */
    private fun syncQuestion(parsed: HarnessState) {
        _question.value = questionToShow(_question.value, parsed.question, _openSessionId.value)
    }

    /** 每次状态刷新时把当前会话的进度补进来。 */
    private fun refreshProgress() {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return
        setAuthoritativeProgress(stateProgress(sid))
    }

    /** 从已知表 / 状态 / 工作区里找某个会话的 activity。 */
    private fun activityOf(sessionId: String): ActivityInfo? {
        knownActivity[sessionId]?.let { return it }
        _state.value.sessions.firstOrNull { it.id == sessionId }?.let { return it.activity }
        _workspaces.value.forEach { w ->
            w.sessions.firstOrNull { it.id == sessionId }?.let { return it.activity }
        }
        return null
    }

    private fun rememberActivity(id: String, a: ActivityInfo?) {
        if (a == null || a.lastReplySeq <= 0L) return
        val prev = knownActivity[id]
        if (prev == null || a.lastReplySeq >= prev.lastReplySeq) knownActivity[id] = a
    }

    /** 立刻把某个会话标成已读（点进去/退出的瞬间用）。 */
    private fun markSeenNow(sessionId: String) {
        if (sessionId.isEmpty()) return
        activityOf(sessionId)?.let { settings.markReplySeen(sessionId, it.lastReplySeq) }
    }

    /** 当前是否应该向桥接声明"我在看这个会话"。 */
    private fun watchingNow(): String? {
        if (!foreground) return null
        val id = _openSessionId.value
        return id.ifEmpty { null }
    }

    private var stateCall: Call? = null
    private var sessionCall: Call? = null
    private var stateLoop: Job? = null
    private var pollLoop: Job? = null

    init {
        startStateStream()
        startPollLoop()
        refreshWorkspaces()
    }

    fun currentEndpointsRaw(): String = settings.endpointsRaw
    fun currentToken(): String = settings.token
    fun showInjected(): Boolean = settings.showInjected

    fun setShowInjected(value: Boolean) {
        settings.showInjected = value
    }

    /** 设置变更后重挑端点并重连。 */
    fun applySettings(endpointsRaw: String, token: String, notify: Boolean) {
        settings.endpointsRaw = endpointsRaw
        settings.token = token
        settings.notificationEnabled = notify
        _endpoints.value = settings.endpointList()
        _token.value = settings.token
        _notifyEnabled.value = notify
        activeBase = ""
        reconnect()
    }

    /**
     * 解析出一个可用的客户端。
     *
     * 先用当前端点快速探活（2.5s）——不活就按顺序试下一个。
     * 全部不可达时返回第一个端点的客户端，让错误信息指向用户预期的地址，
     * 而不是一个他看不懂的备用地址。
     */
    private fun resolveClient(): BridgeClient {
        val eps = settings.endpointList()
        val token = settings.token

        val current = activeBase
        if (current.isNotEmpty() && eps.contains(current) && BridgeClient.probe(current, token)) {
            return BridgeClient(current, token)
        }
        for (ep in eps) {
            if (BridgeClient.probe(ep, token)) {
                if (activeBase != ep) {
                    activeBase = ep
                    _baseUrl.value = ep
                }
                return BridgeClient(ep, token)
            }
        }
        val fallback = eps.firstOrNull() ?: com.dsh.phone.Settings.DEFAULT_PRIMARY
        activeBase = fallback
        _baseUrl.value = fallback
        return BridgeClient(fallback, token)
    }

    /** 关掉聊天页，回到标签页。 */
    fun closeSession() {
        // 退出时按"最新已知"再标一次，覆盖停留期间新到的回复
        markSeenNow(_openSessionId.value)
        // 立刻把草稿落盘，不走防抖 —— 这一下就是要防丢
        flushDraft()
        // 阅读位置也存一份：连退出 App 再进来都能接着看
        saveChatScroll()
        sessionCall?.cancel()
        sessionCall = null
        _openSessionId.value = ""
        // ★ 刻意不清空 messages：清空会让 LazyListState 的索引被夹到 0，
        //   再点回来位置就没了 —— 而这正是用户要的"接着看"。
        _chatError.value = null
        _question.value = null
        setLiveRunning(false)
        _doc.value = null
        _queue.value = emptyList()
        progressAuthoritative = SessionProgress()
        progressFallback = SessionProgress()
        _progress.value = SessionProgress()
        _hasMoreHistory.value = false
        _loadingOlder.value = false
        _queueEditDraft.value = null
        _attachments.value = emptyList()
        _uploading.value = null
        refreshUnread()
    }

    /** 手动刷新一次整机快照。 */
    fun refreshNow() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val t0 = System.currentTimeMillis()
                val s = resolveClient().state(watching = watchingNow())
                applyState(s)
                _conn.value = ConnStatus(ConnPhase.LIVE, latencyMs = System.currentTimeMillis() - t0)
            }.onFailure {
                say("刷新失败：${it.message}")
                _conn.value = ConnStatus(ConnPhase.ERROR, message = it.message)
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    private fun say(text: String) {
        _toast.value = text
    }

    fun reconnect() {
        activeBase = ""
        stateCall?.cancel()
        sessionCall?.cancel()
        stateLoop?.cancel()
        stateCall = null
        sessionCall = null
        startStateStream()
        val open = _openSessionId.value
        if (open.isNotEmpty()) openSession(open)
    }

    /* ------------------------- 整机状态流 ------------------------- */

    private fun startStateStream() {
        _conn.value = ConnStatus(ConnPhase.CONNECTING)
        stateLoop = viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (true) {
                val finished = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                val call = resolveClient().openSse(
                    path = "/phone/events",
                    onEvent = { event, data ->
                        if (event == "hello" || event == "state") {
                            runCatching { parseState(data) }.onSuccess { parsed ->
                                applyState(parsed)
                                _conn.value = ConnStatus(ConnPhase.LIVE)
                                attempt = 0
                            }
                        }
                    },
                    onClosed = { err -> finished.complete(err) },
                )
                stateCall = call
                val err = finished.await()
                if (err == null) {
                    _conn.value = ConnStatus(ConnPhase.ERROR, message = "连接已关闭，重连中")
                } else {
                    _conn.value = ConnStatus(ConnPhase.ERROR, message = err.message ?: err.javaClass.simpleName)
                }
                attempt += 1
                delay(backoffMs(attempt))
            }
        }
    }

    /** 兜底轮询：SSE 在某些省电策略下会被切，这里保证界面仍能刷新。 */
    private fun startPollLoop() {
        pollLoop = viewModelScope.launch(Dispatchers.IO) {
            var tick = 0
            while (true) {
                delay(if (_conn.value.phase == ConnPhase.LIVE) 5000L else 3000L)
                tick += 1
                if (tick % 4 == 0) {
                    runCatching { applyWorkspaces(resolveClient().workspaces()) }
                }
                runCatching {
                    val t0 = System.currentTimeMillis()
                    val s = resolveClient().state(watching = watchingNow())
                    val dt = System.currentTimeMillis() - t0
                    applyState(s)
                    _conn.value = ConnStatus(ConnPhase.LIVE, latencyMs = dt)
                }.onFailure { e ->
                    if (_conn.value.phase != ConnPhase.LIVE) {
                        _conn.value = ConnStatus(ConnPhase.ERROR, message = e.message)
                    }
                }
            }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val step = attempt.coerceAtMost(6)
        return (500L shl (step - 1).coerceAtLeast(0)).coerceAtMost(8000L)
    }

    /* ------------------------- 单会话实时流 ------------------------- */

    fun openSession(sessionId: String) {
        sessionCall?.cancel()
        sessionCall = null
        _openSessionId.value = sessionId
        settings.lastSessionId = sessionId
        // 「最近会话」板块的数据来源就是这一句：只要是从手机上点开的，
        // 不管是点未读卡片、点工作区树、还是从通知进来，都记一笔
        rememberOpened(sessionId)
        // 点进去的瞬间就标已读 —— 不能等下一次轮询，否则快速进出会漏标
        markSeenNow(sessionId)
        _chatError.value = null
        _question.value = null
        setLiveRunning(false)
        _draft.value = settings.draft(sessionId)
        progressAuthoritative = stateProgress(sessionId)
        progressFallback = SessionProgress()
        publishProgress()
        _queueEditDraft.value = settings.queueEditDraft(sessionId)
        _attachments.value = emptyList()
        _uploading.value = null
        _queue.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().queue(sessionId) }.onSuccess { _queue.value = it }
        }

        // 同会话再来（退出看别的、又点回来）：消息与滚动位置都保留，直接接着看。
        // 换会话才重置 —— 并顺手把上次的阅读位置读回来。
        if (chatStateSessionId != sessionId) {
            chatStateSessionId = sessionId
            _messages.value = emptyList()
            _streamText.value = ""
            _streamReasoning.value = ""
            _chatListState.value = LazyListState()
            val saved = settings.chatScroll(sessionId)
            _chatFollowTail.value = saved?.followTail ?: true
            pendingScrollRestore = saved?.takeIf { !it.followTail && it.index > 0 }
        }
        // 打开会话时把它所属的工作区展开：返回列表时一眼能看到刚读的是哪个
        _workspaces.value.firstOrNull { w -> w.sessions.any { it.id == sessionId } }
            ?.let { expandWorkspace(it.id) }
        // 顺便把模型目录拉一次（含这个会话当前的选择）
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().models(sessionId) }.onSuccess { _models.value = it }
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().history(sessionId, 60) }
                .onSuccess { page ->
                    val msgs = page.messages
                    _messages.value = msgs
                    _hasMoreHistory.value = page.hasMore
                    _chatError.value = null
                    // 历史里的轮/步/用量最可靠（读的就是会话日志），拿来校正仪表盘
                    setFallbackProgress(progressFromMessages(msgs))
                    // 必须等消息到位再滚：列表还空着时滚，索引会被夹到 0，位置就丢了
                    pendingScrollRestore?.let { r ->
                        pendingScrollRestore = null
                        withContext(Dispatchers.Main) {
                            runCatching { _chatListState.value.scrollToItem(r.index, r.offset) }
                        }
                    }
                }
                .onFailure {
                    _chatError.value = "读取历史失败：${it.message}"
                    say("读取历史失败：${it.message}")
                }
        }

        viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (_openSessionId.value == sessionId) {
                val finished = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                val call = resolveClient().openSse(
                    path = "/phone/sessions/$sessionId/live?max=60",
                    onEvent = { event, data -> handleSessionEvent(event, data) },
                    onClosed = { err -> finished.complete(err) },
                )
                sessionCall = call
                val err = finished.await()
                if (_openSessionId.value != sessionId) return@launch
                _chatError.value = if (err == null) {
                    "实时连接已断开，重连中…"
                } else {
                    "实时连接失败：${err.message ?: err.javaClass.simpleName}，重连中…"
                }
                attempt += 1
                delay(backoffMs(attempt))
            }
        }
    }

    private fun handleSessionEvent(event: String, data: JSONObject) {
        when (event) {
            "snapshot" -> {
                _chatError.value = null
                val msgs = parseMessages(data.optJSONArray("messages"))
                if (msgs.isNotEmpty() || _messages.value.isEmpty()) _messages.value = msgs
                _streamText.value = ""
                _streamReasoning.value = ""
            }
            "messages" -> {
                val incoming = parseMessages(data.optJSONArray("messages"))
                if (incoming.isEmpty()) return
                // 定稿消息到达：清掉流式草稿，再按 seq 去重合并
                _streamText.value = ""
                _streamReasoning.value = ""
                val current = _messages.value
                val seen = current.map { it.seq }.toHashSet()
                val merged = ArrayList(current)
                for (m in incoming) if (seen.add(m.seq)) merged.add(m)
                merged.sortBy { it.seq }
                _messages.value = merged
            }
            "delta" -> {
                val chunk = data.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "text-delta" -> _streamText.value += chunk.optString("text", "")
                    "reasoning-delta" -> _streamReasoning.value += chunk.optString("text", "")
                    "finish" -> Unit
                    "usage" -> Unit
                }
            }
            "attempt" -> {
                val phase = data.optString("phase")
                if (phase == "start") {
                    setLiveRunning(true)
                    _streamText.value = ""
                    _streamReasoning.value = ""
                } else if (phase == "end") {
                    _streamText.value = ""
                    _streamReasoning.value = ""
                }
            }
            "turn" -> {
                // 即时切换运行态，停止按钮据此显隐
                setLiveRunning(data.optString("phase") == "start")
            }
            "progress" -> {
                setAuthoritativeProgress(parseProgress(data))
            }
            "queue" -> {
                _queue.value = parseQueue(data.optJSONArray("items"))
            }
            "question" -> {
                // harness 在等回答。只有手机开着这个会话时桥接才会把问题发过来
                // （否则它会直接交给电脑上的 UI），所以收到就可以展示。
                runCatching { parsePendingQuestion(data) }.getOrNull()?.let { _question.value = it }
            }
            "question-done" -> {
                val id = data.optString("id")
                if (_question.value?.id == id) _question.value = null
            }
            "error" -> say("会话流出错：${data.optString("message")}")
            "end" -> Unit
        }
    }

    /* ---------------------------- 动作 ---------------------------- */

    fun send(text: String) {
        val sessionId = _openSessionId.value
        if (sessionId.isEmpty()) return
        val body = text.trim()
        val files = _attachments.value
        // 允许"只发附件、不写字" —— 传个文件过去本来就是常见动作
        if (body.isEmpty() && files.isEmpty()) return
        _busy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().prompt(sessionId, body, files.map { it.receiptId }) }
                .onSuccess {
                    // 发出去了才清空附件（失败要留着，让用户能重发）
                    _attachments.value = emptyList()
                }
                .onFailure { say("发送失败：${it.message}") }
            _busy.value = false
        }
    }

    /** 把一个已经读进内存的文件传给电脑。 */
    fun attachFile(name: String, bytes: ByteArray) {
        val sessionId = _openSessionId.value
        if (sessionId.isEmpty()) {
            say("先打开一个会话再传文件")
            return
        }
        _uploading.value = name
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().uploadFile(sessionId, name, bytes) }
                .onSuccess { file ->
                    if (file.receiptId.isEmpty()) {
                        say("上传没有拿到凭据，可能没传成功")
                    } else {
                        _attachments.value = _attachments.value + file
                        say("已传到电脑：${file.name}（${file.sizeText}）—— 写字或不写都能发")
                    }
                }
                .onFailure { say("传文件失败：${it.message}") }
            _uploading.value = null
        }
    }

    fun removeAttachment(receiptId: String) {
        _attachments.value = _attachments.value.filterNot { it.receiptId == receiptId }
    }

    /** 输入变化：更新内存 + 防抖落盘（每敲一个字都写盘没必要）。 */
    fun updateDraft(text: String) {
        _draft.value = text
        val sid = _openSessionId.value
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(400)
            settings.setDraft(sid, text)
        }
    }

    /** 立即落盘（退出聊天页 / 发出去之后用）。 */
    fun flushDraft() {
        draftSaveJob?.cancel()
        draftSaveJob = null
        settings.setDraft(_openSessionId.value, _draft.value)
    }

    /**
     * 打开一个文档：从电脑读回来，交给文档页渲染。
     *
     * 相对路径由桥接按**当前会话的 cwd** 解析 —— 正文里点的通常是
     * docs/xxx.md 这种相对写法。
     */
    fun openDocument(path: String) {
        if (path.isBlank()) return
        _doc.value = DocState(path = path, loading = true)
        val sid = _openSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().fileText(path, sid) }
                .onSuccess { f ->
                    _doc.value = DocState(
                        path = f.path.ifBlank { path },
                        content = f.text,
                        truncated = f.truncated,
                    )
                }
                .onFailure { e ->
                    _doc.value = DocState(path = path, error = e.message)
                }
        }
    }

    fun closeDocument() {
        _doc.value = null
    }

    /* ------------------------------ 终端 ------------------------------ */

    private val _term = MutableStateFlow(TermState())
    val term: StateFlow<TermState> = _term.asStateFlow()

    /** 终端页是否"跟着最新走"（和聊天页同一个思路，见 §22）。 */
    private val _termFollowTail = MutableStateFlow(true)
    val termFollowTail: StateFlow<Boolean> = _termFollowTail.asStateFlow()

    private var termCall: Call? = null
    private var termLoop: Job? = null
    private var termScreen: TermScreen? = null
    private var termRev = 0L
    private var lastTermPublish = 0L
    private var pendingTermSend: String? = null
    /** 上次向电脑申请的终端尺寸：终端没了要重开时，还按这个开。 */
    private var lastTermCols = 100
    private var lastTermRows = 32
    /** 终端页是不是正开着（决定"终端没了"要不要立刻重开）。 */
    private var termWatching = false
    private var termPublishJob: Job? = null
    private val TERM_PUBLISH_MS = 50L

    fun setTermFollowTail(value: Boolean) {
        _termFollowTail.value = value
    }

    /**
     * 确保电脑上有一个终端，并接上输出流。
     *
     * cols/rows 只有第一次分配时用得上 —— PTY 尺寸没有 resize 接口，
     * 所以由终端页按自己实际能显示多少列来传。
     */
    fun ensureTerminal(cols: Int, rows: Int) {
        lastTermCols = cols
        lastTermRows = rows
        termWatching = true
        val cur = _term.value
        if (cur.opening) return
        if (cur.id.isNotEmpty() && !cur.exited) {
            // 已经有一个活着的：只需要把流接回来（切走再切回来就是这条路）
            if (!cur.connected) startTermLoop(cur.id)
            return
        }
        _term.value = TermState(opening = true)
        termScreen = null
        termRev = 0
        _termFollowTail.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().termOpen(cols, rows) }
                .onSuccess { r ->
                    // 屏幕尺寸按**电脑上实际分配到的**来：服务端会把列数夹到合法区间
                    termScreen = TermScreen(r.cols, r.rows)
                    _term.value = TermState(
                        id = r.id,
                        pid = r.pid,
                        cwd = r.cwd,
                        cols = r.cols,
                        rows = r.rows,
                    )
                    startTermLoop(r.id)
                }
                .onFailure { e ->
                    _term.value = TermState(error = e.message ?: "打开终端失败")
                }
        }
    }

    /** 输出流的重连循环：手机切后台、蜂窝换基站都会断，断了要自己接回来。 */
    private fun startTermLoop(id: String) {
        termLoop?.cancel()
        termCall?.cancel()
        termLoop = viewModelScope.launch(Dispatchers.IO) {
            var attempt = 0
            while (_term.value.id == id && !_term.value.exited) {
                val finished = kotlinx.coroutines.CompletableDeferred<Throwable?>()
                val call = resolveClient().openTermStream(
                    id = id,
                    onEvent = { event, data -> handleTermEvent(event, data) },
                    onClosed = { err -> finished.complete(err) },
                )
                termCall = call
                val err = finished.await()
                if (_term.value.id != id) return@launch
                // ★ 404 = 电脑上这个终端已经不存在了（harness 重启、插件重载、
                //   30 分钟空闲回收都会这样）。这时**不能再重连**，那会永远失败；
                //   正确做法是把本地状态清掉，然后按原尺寸重开一个。
                if (err is BridgeException && err.code == 404) {
                    termScreen = null
                    _term.value = TermState(
                        error = if (termWatching) null else "电脑上的终端已经关掉了",
                    )
                    if (termWatching) {
                        say("电脑上的终端已关闭，已重新开一个")
                        ensureTerminal(lastTermCols, lastTermRows)
                    }
                    return@launch
                }
                if (_term.value.connected) {
                    _term.value = _term.value.copy(connected = false)
                }
                attempt += 1
                delay(backoffMs(attempt))
            }
        }
    }

    private fun handleTermEvent(event: String, data: JSONObject) {
        when (event) {
            "hello" -> {
                // 桥接那边留着最近一段滚动缓冲：重连时以它为准重建屏幕，
                // 这样"切走几分钟再回来"看到的是完整的，而不是从中间接上的碎片。
                val cols = data.optInt("cols", _term.value.cols).coerceAtLeast(20)
                val rows = data.optInt("rows", _term.value.rows).coerceAtLeast(6)
                termScreen = TermScreen(cols, rows)
                val buffered = data.optString("buffered", "")
                if (buffered.isNotEmpty()) termScreen?.feed(buffered)
                _term.value = _term.value.copy(
                    id = data.optString("id", _term.value.id),
                    pid = data.optInt("pid", _term.value.pid),
                    cwd = data.optString("cwd", _term.value.cwd),
                    cols = data.optInt("cols", _term.value.cols),
                    rows = data.optInt("rows", _term.value.rows),
                    connected = true,
                    detached = false,
                    error = null,
                    exited = data.optBoolean("exited", false),
                    exitCode = if (data.isNull("exitCode")) null else data.optInt("exitCode"),
                )
                publishTerm()
                flushPendingTerm()
            }
            "out" -> {
                val text = data.optString("data", "")
                val screen = termScreen
                if (text.isNotEmpty() && screen != null) {
                    screen.feed(text)
                    publishTerm()
                }
            }
            "exit" -> {
                _term.value = _term.value.copy(
                    exited = true,
                    exitCode = if (data.isNull("code")) null else data.optInt("code"),
                )
                publishTerm()
            }
            "closed" -> {
                _term.value = _term.value.copy(connected = false, exited = true)
                publishTerm()
            }
        }
    }

    /**
     * 发布一次终端内容。
     *
     * 带 50ms 合并：`yes`、大文件 `cat` 这类命令一秒钟能推几百块，
     * 每块都刷一次界面纯属浪费；合并到最多 20 次/秒，肉眼看还是即时。
     * rev 是"内容变了"的信号 —— 不能只比较 lines 的引用，那个列表一直在被追加。
     */
    private fun publishTerm() {
        val now = System.currentTimeMillis()
        val gap = now - lastTermPublish
        if (gap >= TERM_PUBLISH_MS) {
            lastTermPublish = now
            doPublishTerm()
            return
        }
        if (termPublishJob?.isActive == true) return
        termPublishJob = viewModelScope.launch {
            delay(TERM_PUBLISH_MS - gap)
            lastTermPublish = System.currentTimeMillis()
            doPublishTerm()
        }
    }

    private fun doPublishTerm() {
        val screen = termScreen ?: return
        termRev += 1
        // 渲染列表 = 历史滚动 + 当前屏幕。外层列表每次新建（Compose 靠 rev 判断变化），
        // 里面的行对象是共享的，不复制内容。
        val combined = ArrayList<AnsiLine>(screen.scrollbackSize + screen.rows)
        combined.addAll(screen.scrollback)
        combined.addAll(screen.screenLines())
        _term.value = _term.value.copy(
            rev = termRev,
            lines = combined,
            // 全屏程序即使隐藏光标也给出行号：界面要画一个淡的提示，
            // 否则"vim 里看不见光标"就变成盲操作
            cursorLine = screen.cursorLine()
                ?: if (screen.usingAltScreen) screen.scrollbackSize + screen.cursorRow else null,
            cursorVisible = screen.cursorVisible,
            cursorCol = screen.cursorCol,
            fullScreen = screen.usingAltScreen,
            cols = screen.cols,
            rows = screen.rows,
        )
    }

    /**
     * 等终端就绪再发一段命令。
     *
     * 给 intent 深链用（`--es termSend "ls"`）：PTY 是异步开出来的，
     * 立刻发会打在空气里。也可以在电脑上把常用命令做成快捷方式。
     */
    fun sendTermWhenReady(text: String) {
        pendingTermSend = text
        flushPendingTerm()
    }

    private fun flushPendingTerm() {
        val text = pendingTermSend ?: return
        val t = _term.value
        if (t.id.isEmpty() || !t.connected) return
        pendingTermSend = null
        sendTerm(text, submit = true)
    }

    /** 发一行给终端（submit=true 时补回车，等于按了 Enter）。 */
    fun sendTerm(text: String, submit: Boolean = true) {
        val id = _term.value.id
        if (id.isEmpty()) {
            say("终端还没打开")
            return
        }
        // 回车用 \r 而不是 \n：raw 模式（vim）里 CR 才是 Enter，
        // 而命令行的行规程有 ICRNL，\r 同样等于回车 —— 两边都对。
        val data = if (submit) text + "\r" else text
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().termInput(id, data) }
                .onFailure { say("发送失败：${it.message}") }
        }
    }

    /**
     * 裸按键 / 逐键直通：Tab、方向键、Esc、Ctrl-C，以及全屏程序里敲的每个字。
     *
     * 全屏程序（vim）会把 PTY 设成 raw 模式，这时**回车是 CR 不是 LF** ——
     * 命令模式下发 `\n` 没问题（行规程的 ICRNL 会翻译），raw 模式下
     * 发 `\n` 只会插一个换行符。所以这里统一翻译成 `\r`。
     */
    fun sendTermRaw(data: String) {
        if (data.isEmpty()) return
        sendTerm(data.replace('\n', '\r'), submit = false)
    }

    /** Ctrl-C：给前台进程组发 SIGINT，终端本身留着。 */
    fun interruptTerm() {
        val id = _term.value.id
        if (id.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().termSignal(id, "SIGINT") }
                .onFailure { say("打断失败：${it.message}") }
        }
    }

    /**
     * 手动重开一个终端（电脑上那个没了、或者退出了，界面上给一个按钮）。
     */
    fun reopenTerminal() {
        termScreen = null
        _term.value = TermState()
        termRev = 0
        ensureTerminal(lastTermCols, lastTermRows)
    }

    /** 只清手机上的显示，不影响电脑上的终端。 */
    fun clearTerminal() {
        termScreen?.clear()
        publishTerm()
    }

    /** 关掉电脑上的那个 shell。 */
    fun killTerminal() {
        val id = _term.value.id
        termCall?.cancel()
        termCall = null
        termLoop?.cancel()
        termLoop = null
        if (id.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { resolveClient().termClose(id) }
            }
        }
        termScreen = null
        termRev = 0
        _term.value = TermState()
        say("终端已关闭")
    }

    /**
     * 终端页退出时调：**只断开输出流，不关电脑上的 shell**。
     *
     * 用户的实际用法是"在会话里复制一段命令 → 切到终端粘上跑"，
     * 中间会离开终端页 —— 所以进程必须留着。真正没人要了由电脑侧
     * 的空闲回收兜底（30 分钟无人连接就关）。
     */
    fun detachTerminal() {
        termWatching = false
        termCall?.cancel()
        termCall = null
        termLoop?.cancel()
        termLoop = null
        // 只标"挂起"，电脑上的 shell 不动（用户就是在"会话 ↔ 终端"之间来回切）
        if (_term.value.id.isNotEmpty() && !_term.value.exited) {
            _term.value = _term.value.copy(connected = false, detached = true)
        }
    }

    /** 重拉一次整机状态（重命名/归档之后用）。 */
    private fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().state(watching = watchingNow()) }
                .onSuccess {
                    _state.value = it
                    refreshUnread()
                    refreshProgress()
                }
        }
    }

    /* ------------------------- 会话/工作区管理 ------------------------- */

    fun renameSession(sessionId: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().renameSession(sessionId, title) }
                .onSuccess {
                    say("已重命名为「$it」")
                    refreshWorkspaces()
                    refreshState()
                }
                .onFailure { say("重命名失败：${it.message}") }
        }
    }

    /** 分支会话：分出来之后直接打开新的那个。 */
    fun forkSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().forkSession(sessionId) }
                .onSuccess { id ->
                    refreshWorkspaces()
                    refreshState()
                    if (id.isNotEmpty()) {
                        say("已分支出新会话")
                        openSession(id)
                    }
                }
                .onFailure { say("分支失败：${it.message}") }
        }
    }

    fun archiveSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().archiveSession(sessionId) }
                .onSuccess {
                    say("已归档（会话日志仍然保留在电脑上）")
                    refreshWorkspaces()
                    refreshState()
                }
                .onFailure { say("归档失败：${it.message}") }
        }
    }

    fun renameWorkspace(workspaceId: String, title: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().renameWorkspace(workspaceId, title) }
                .onSuccess {
                    say("工作区已改名为「$it」")
                    refreshWorkspaces()
                }
                .onFailure { say("改名失败：${it.message}") }
        }
    }

    fun deleteWorkspace(workspaceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().deleteWorkspace(workspaceId) }
                .onSuccess {
                    _expandedWorkspaces.value = _expandedWorkspaces.value - workspaceId
                    settings.expandedWorkspaces = _expandedWorkspaces.value
                    say("已删除工作区注册（目录和会话都还在）")
                    refreshWorkspaces()
                }
                .onFailure { say("删除失败：${it.message}") }
        }
    }

    /** 拉一次模型目录（带上当前会话，以便标出选中的那个）。 */
    fun refreshModels() {
        val sid = _openSessionId.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().models(sid) }
                .onSuccess { _models.value = it }
                .onFailure { say("读取模型列表失败：${it.message}") }
        }
    }

    /**
     * 切换模型 / 只换推理强度。
     *
     * effort 传 null 表示沿用该模型自己的默认强度。
     */
    fun switchModel(provider: String, model: String, effort: String?) {
        val sid = _openSessionId.value
        if (sid.isEmpty()) {
            say("先打开一个会话再切模型")
            return
        }
        _switchingModel.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().selectModel(sid, provider, model, effort) }
                .onSuccess { sel ->
                    _models.value = _models.value.copy(current = sel)
                    // 说清楚"下一轮生效"：正跑着的时候切，当前这一轮用的还是老模型 ——
                    // 不写清楚的话，用户会以为没切成（因为眼前的回答还是旧模型生成的）
                    say(
                        "已切到 ${sel.model}" + (sel.effort?.let { " · $it" } ?: "") +
                            if (_liveRunning.value) "（下一轮生效）" else "",
                    )
                    refreshModels()
                }
                .onFailure { say("切换失败：${it.message}") }
            _switchingModel.value = false
        }
    }

    /** 停止当前会话正在进行的思考 / 生成。 */
    fun stopThinking() {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().cancel(sid) }
                .onSuccess { setLiveRunning(false) }
                .onFailure { say("停止失败：${it.message}") }
        }
    }

    /** 把手机的答案回给 harness。 */
    fun answerQuestion(answers: List<QuestionAnswer>) {
        val q = _question.value ?: return
        if (answers.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().answer(q.id, answers) }
                .onSuccess {
                    if (it) _question.value = null else say("这个问题已经结束了")
                }
                .onFailure { say("回答失败：${it.message}") }
        }
    }

    fun interrupt() {
        val sessionId = _openSessionId.value
        if (sessionId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().cancel(sessionId) }
                .onFailure { say("打断失败：${it.message}") }
        }
    }

    /** 清掉当前会话的目标（已完成的目标会一直留在注册表里，纯噪音）。 */
    fun clearGoal() {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().clearGoal(sid) }
                .onSuccess {
                    say("目标已清除（会话日志里的历史还在）")
                    refreshState()
                    refreshWorkspaces()
                }
                .onFailure { say("清除失败：${it.message}") }
        }
    }

    /* ------------------------- 往上翻历史 ------------------------- */

    /**
     * 往更早翻一页，并**保住当前的阅读位置**。
     *
     * 关键在"保住位置"：新消息是插在列表**头部**的，
     * 插进去 N 条之后原来第一条的下标就变成了 i+N ——
     * 不补偿的话画面会往下跳 N 行，用户正在看的地方就飞了。
     *
     * @return 插进来了多少条（0 = 没有更早的了，或者拿失败）
     */
    private suspend fun loadOlderOnce(): Int {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return 0
        val current = _messages.value
        val before = current.minOfOrNull { it.seq } ?: 0
        if (before <= 0) return 0

        val page = runCatching { resolveClient().history(sid, 60, beforeSeq = before) }.getOrElse { e ->
            say("读取更早消息失败：${e.message}")
            return 0
        }
        val older = page.messages.filter { m -> current.none { it.seq == m.seq } }
        _hasMoreHistory.value = page.hasMore
        if (older.isEmpty()) return 0

        // 记下当前阅读位置（头部插入前）
        val st = _chatListState.value
        val firstIdx = st.firstVisibleItemIndex
        val firstOff = st.firstVisibleItemScrollOffset

        val merged = ArrayList<ChatMessage>(current.size + older.size)
        merged.addAll(older)
        merged.addAll(current)
        merged.sortBy { it.seq }
        _messages.value = merged

        // 头部插了多少条，就把滚动位置整体后移多少 —— 画面不动
        withContext(Dispatchers.Main) {
            runCatching { st.scrollToItem(firstIdx + older.size, firstOff) }
        }
        return older.size
    }

    /** 界面上的"载入更早的消息"。 */
    fun loadOlder() {
        if (_loadingOlder.value) return
        _loadingOlder.value = true
        viewModelScope.launch {
            loadOlderOnce()
            _loadingOlder.value = false
        }
    }

    /**
     * 跳到"上一条我发的消息"。
     *
     * 为什么需要：一轮里工具调用能有几十上百行，"我上次问了什么"要往上翻很久。
     * 找不到就自动再翻几页 —— 用户点一下就该看到，而不是点一下再自己翻。
     */
    fun jumpToPreviousUser() {
        if (_loadingOlder.value) return
        viewModelScope.launch {
            val st = _chatListState.value
            var idx = st.firstVisibleItemIndex
            var rounds = 0
            while (rounds < 5) {
                val found = lastUserIndexBefore(idx)
                if (found >= 0) {
                    setChatFollowTail(false)
                    withContext(Dispatchers.Main) { runCatching { st.scrollToItem(found) } }
                    return@launch
                }
                if (!_hasMoreHistory.value) {
                    say("已经到这个会话最早的地方了")
                    return@launch
                }
                _loadingOlder.value = true
                val added = loadOlderOnce()
                _loadingOlder.value = false
                if (added <= 0) {
                    say("没有更早的消息了")
                    return@launch
                }
                idx += added // 前面插进来 added 条，原下标整体后移
                rounds += 1
            }
            say("翻了 5 页还没找到更早的提问")
        }
    }

    /** [limit] 之前最后一条"人发的"消息下标；没有就 -1。判据在 Models.kt（有单测）。 */
    private fun lastUserIndexBefore(limit: Int): Int = lastUserIndexBefore(_messages.value, limit)

    /* ------------------------- 排队消息的改/撤 ------------------------- */

    /** 撤回：从队列里拿掉，不会发出去。 */
    fun retractQueued(itemId: String) {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return
        // 先本地拿掉，点下去立刻有反馈；权威值随后由实时流覆盖
        _queue.value = _queue.value.filterNot { it.id == itemId }
        // 撤回掉的那条如果正存着草稿，草稿也一起丢（否则它指向一条不存在的消息）
        if (_queueEditDraft.value?.first == itemId) {
            _queueEditDraft.value = null
            settings.clearQueueEditDraft(sid)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().queueAction(sid, itemId, "remove") }
                .onFailure {
                    say("撤回失败：${it.message}")
                    runCatching { _queue.value = resolveClient().queue(sid) }
                }
        }
    }

    /** 边改边存：对话框里每敲一下都落盘，意外退出也能捡回来。 */
    fun setQueueEditDraft(itemId: String, text: String) {
        val sid = _openSessionId.value
        if (sid.isEmpty() || itemId.isEmpty()) return
        _queueEditDraft.value = itemId to text
        settings.setQueueEditDraft(sid, itemId, text)
    }

    /** 明确放弃这次修改（点「放弃」才算，返回手势不算）。 */
    fun discardQueueEditDraft() {
        val sid = _openSessionId.value
        _queueEditDraft.value = null
        if (sid.isNotEmpty()) settings.clearQueueEditDraft(sid)
    }

    /** 改内容：还是排在队里，只是把要发的那句话换掉。 */
    fun editQueued(itemId: String, text: String) {
        val sid = _openSessionId.value
        if (sid.isEmpty() || text.isBlank()) return
        _queue.value = _queue.value.map { if (it.id == itemId) it.copy(text = text) else it }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().queueAction(sid, itemId, "edit", text) }
                .onSuccess {
                    say("排队消息已改（等这一轮跑完再发）")
                    // 存成功了草稿就没用了
                    _queueEditDraft.value = null
                    settings.clearQueueEditDraft(sid)
                }
                .onFailure {
                    say("改失败：${it.message}")
                    runCatching { _queue.value = resolveClient().queue(sid) }
                }
        }
    }

    /** 插话：不排队了，下一轮立刻带上它。 */
    fun steerQueued(itemId: String) {
        val sid = _openSessionId.value
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().queueAction(sid, itemId, "steer") }
                .onSuccess { say("已改成「立刻插话」") }
                .onFailure { say("插话失败：${it.message}") }
        }
    }

    fun createSession() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().newSession() }
                .onSuccess { id ->
                    if (id.isNotEmpty()) {
                        refreshWorkspaces()
                        openSession(id)
                    }
                }
                .onFailure { say("新建会话失败：${it.message}") }
        }
    }

    /* ------------------------- 工作区 / 目录 ------------------------- */

    fun refreshWorkspaces() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { applyWorkspaces(resolveClient().workspaces()) }
                .onFailure { /* 静默：状态页已经会报连接问题 */ }
        }
    }

    /**
     * 工作区快照到手后的统一处理：记下来、清掉「最近会话」里已经找不到的、
     * 再重算三个板块的分类。两处（轮询 / 主动刷新）都走这里，免得以后只改一处。
     */
    private fun applyWorkspaces(list: List<WorkspaceInfo>) {
        _workspaces.value = list
        // 空快照不拿来清「最近」：那多半是电脑刚重启、工作区还没读完，
        // 真删掉了的话反正也映射不出卡片（显示层按 id 查找）。
        if (list.isNotEmpty()) pruneRecent(list)
        refreshUnread()
    }

    /** 打开某个目录（path 为空 = 从家目录开始）。 */
    fun browse(path: String?) {
        _listingBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().fsList(path) }
                .onSuccess { _listing.value = it }
                .onFailure { say("浏览失败：${it.message}") }
            _listingBusy.value = false
        }
    }

    /** 在当前浏览到的目录下新建文件夹，然后刷新列表。 */
    fun makeFolder(name: String) {
        val parent = _listing.value.path
        if (parent.isBlank()) {
            say("先打开一个目录")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().fsMkdir(parent, name) }
                .onSuccess {
                    say("已创建文件夹：" + it.substringAfterLast('/'))
                    browse(parent)
                }
                .onFailure { say("创建失败：${it.message}") }
        }
    }

    /** 在当前目录建工作区并开会话（新建会话的主路径）。 */
    fun createSessionHere(title: String?) {
        val dir = _listing.value.path
        if (dir.isBlank()) {
            say("先打开一个目录")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ws = resolveClient().createWorkspace(dir, title)
                ws.id to resolveClient().newSession(cwd = ws.path, workspaceId = ws.id)
            }
                .onSuccess { (_, id) ->
                    if (id.isNotEmpty()) {
                        refreshWorkspaces()
                        openSession(id)
                    }
                }
                .onFailure { say("新建会话失败：${it.message}") }
        }
    }

    /** 在已有工作区里再开一个会话。 */
    fun createSessionInWorkspace(workspaceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { resolveClient().newSession(workspaceId = workspaceId) }
                .onSuccess { id ->
                    if (id.isNotEmpty()) {
                        refreshWorkspaces()
                        openSession(id)
                    }
                }
                .onFailure { say("新建会话失败：${it.message}") }
        }
    }

    /** 逐个端点探活，把结果原样报给用户 —— 这比"连不上"有用得多。 */
    fun testConnection(endpointsRaw: String, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val eps = endpointsRaw.split(',', '\n', '\r', ' ')
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
            if (eps.isEmpty()) {
                say("请先填至少一个地址")
                return@launch
            }
            val report = eps.joinToString("\n") { ep ->
                val t0 = System.currentTimeMillis()
                if (BridgeClient.probe(ep, token)) {
                    val dt = System.currentTimeMillis() - t0
                    "✓ $ep  (${dt}ms)"
                } else {
                    "✗ $ep  不可达"
                }
            }
            say(report)
        }
    }

    override fun onCleared() {
        stateCall?.cancel()
        sessionCall?.cancel()
        super.onCleared()
    }
}
