package com.dsh.phone

import org.json.JSONArray
import org.json.JSONObject

/** DSH 当前模型选择。 */
data class ModelInfo(
    val provider: String,
    val model: String,
    val effort: String?,
) {
    val label: String get() = if (effort.isNullOrBlank()) "$provider / $model" else "$provider / $model · $effort"
}

/** 一条后台任务（job）。 */
data class JobInfo(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val detail: String?,
    /** 属于哪个会话（电脑端按会话分组显示后台任务，手机也照这个分）。 */
    val owner: String? = null,
)

/** 会话当前的长任务目标。 */
data class GoalInfo(
    val objective: String,
    val phase: String,
    val roundsStarted: Int,
    val maxGoalRounds: Int?,
    val blockedReason: String?,
    /** 宿主的 GoalRef：清除目标时要用（少了它手机就"看得见清不掉"）。 */
    val id: String = "",
    val revision: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** 会话的"最近有没有助手回复"依据（由桥接提供，已读状态在手机侧）。 */
data class ActivityInfo(
    val lastReplySeq: Long,
    val lastReplyAt: Long,
    val agentSpokeLast: Boolean,
) {
    companion object { val NONE = ActivityInfo(0L, 0L, false) }
}

/** 一条会话是否算「未读」：助手最后说话 + 手机还没看过这条 + 在时间窗内。 */
fun isUnread(activity: ActivityInfo?, seenSeq: Long, now: Long, windowMs: Long): Boolean {
    val a = activity ?: return false
    if (!a.agentSpokeLast) return false
    if (a.lastReplySeq <= seenSeq) return false
    return a.lastReplyAt > 0 && now - a.lastReplyAt < windowMs
}

/**
 * 把长文件名缩成"头…尾"。
 *
 * 为什么不用系统的省略号（尾部截断）：文件名最有信息量的是**扩展名**和**开头**，
 * 尾部截断会把 `.jpg` 直接吃掉（小米截图的名字有 60 多个字符：
 * `Screenshot_2026-10-16-10-07-42-951_com.ss.android.ugc.aweme.jpg`）。
 *
 * 这条直接关系到"能不能看到删除按钮"：chip 里的文字没有宽度约束时，
 * 长名字会把整条 chip 撑出屏幕，末尾的 ✕ 就跑到屏幕外去了
 * —— 用户两次反馈"没有删除标识"就是这个原因。
 */
fun shortFileName(name: String, max: Int = 30): String {
    if (name.length <= max || max <= 6) return name
    val tail = 8   // 留出扩展名 + 一点后缀
    val head = max - tail - 1
    return name.take(head) + "…" + name.takeLast(tail)
}

/* ------------------------------ 错误记录 ------------------------------ */

/**
 * 日志时间戳：`MM-dd HH:mm:ss`。
 *
 * 带上日期是因为"昨天那个错"和"刚刚那个错"必须能分开 ——
 * 只有时分秒的话，隔天回看就分不清了。
 */
fun formatLogTime(now: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = now
    fun p(v: Int) = if (v < 10) "0$v" else "$v"
    return "${p(c.get(java.util.Calendar.MONTH) + 1)}-${p(c.get(java.util.Calendar.DAY_OF_MONTH))} " +
        "${p(c.get(java.util.Calendar.HOUR_OF_DAY))}:${p(c.get(java.util.Calendar.MINUTE))}:" +
        p(c.get(java.util.Calendar.SECOND))
}

/**
 * 这条提示算不算"出错了"？—— 决定要不要写进错误记录。
 *
 * 为什么用关键词而不是给每个调用点加一个 fail()：出错提示有三十来处
 * （上传、刷新、归档、切模型、终端……），逐个改既啰嗦又容易漏；
 * 而「话里带"失败/错误/连不上"」这件事本身就很可靠 ——
 * 谁会写一句"上传失败"来表示成功呢。真要精确的时候再单开接口。
 */
fun looksLikeError(text: String): Boolean =
    listOf("失败", "错误", "连不上", "超时", "异常", "没拿到", "没有拿到", "不能为空", "不可用")
        .any { text.contains(it) }

/**
 * 往错误记录里追加一行：**最新的在最上面**，只留 [keep] 条。
 *
 * 最新的在最上面是为了"出事了立刻看一眼" —— 让用户往下翻找刚发生的错很反人类。
 */
fun appendErrorLog(current: List<String>, line: String, keep: Int = 50): List<String> {
    if (line.isBlank()) return current
    val next = ArrayList<String>(current.size + 1)
    next.add(line.trim())
    for (i in 0 until minOf(current.size, keep - 1)) next.add(current[i])
    return next
}

/** 会话页上次离开时的阅读位置（也是"这次进来落在哪儿"的输入）。 */
data class SavedScroll(val index: Int, val offset: Int, val followTail: Boolean)

/**
 * 刚加载到的历史里，有"上次看过之后"的新回复吗？
 *
 * 判据是**最后一条助手消息的 seq** 与进入会话时的已读水位线比较。
 *
 * 为什么不看 `activity.lastReplySeq`（会话摘要里那个）：**冷启动不可靠**。
 * 从通知或 Intent 直接进会话时，state / workspaces 还没拉回来，
 * `activityOf()` 是空的，会永远判成"没有新回复"，把用户丢回上次那一段
 * —— 2026-10-16 真机验证时就是这么翻的车（见 docs/技术路线.md §47）。
 * 而"加载到的最后一条助手消息"任何时候都在手上。
 *
 * [running] = 会话还在跑：那一定是在产出新内容（哪怕还没定稿成消息），也要看最新。
 * 顺带说明：这里**不看 7 天窗口** —— 角标关心"回复新不新鲜"，
 * 落点只关心"内容有没有变多"。
 */
fun hasNewRepliesInHistory(messages: List<ChatMessage>, seenSeq: Long, running: Boolean): Boolean {
    if (running) return true
    val last = messages.lastOrNull { it.role == "assistant" } ?: return false
    return last.seq > seenSeq
}

/**
 * 进入会话时该落在哪儿。
 *
 * 规则（用户 2026-10-16 明确要求）：
 *   - **有新回复**（这个会话在「未读回复」区）→ 直奔**最新内容**。
 *     不能把用户留在上次那一段让他自己往下翻 —— 他点进来就是要看新回复的；
 *   - **没有新回复** → 回到上次看的位置。哪怕这个会话已经被挤出
 *     「最近会话」前 6 条、甚至从列表里看不见了，也必须回得去
 *     （位置只按会话 id 存，与列表顺序无关）。
 */
fun planEntryScroll(hasNewReplies: Boolean, saved: SavedScroll?): SavedScroll {
    if (hasNewReplies) return SavedScroll(index = 0, offset = 0, followTail = true)
    val s = saved ?: return SavedScroll(index = 0, offset = 0, followTail = true)
    // 上次就在尾部、或"位置"其实是第一条：都当成跟随最新
    if (s.followTail || s.index <= 0) return SavedScroll(index = 0, offset = 0, followTail = true)
    return s
}

/** 一个 harness 会话的概要。 */
data class SessionInfo(
    val id: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val updatedAt: Long,
    val cwd: String?,
    val origin: String?,
    /** 父会话 id：非空 = 这是一条子代理会话。 */
    val parent: String? = null,
    val live: Boolean,
    val jobs: List<JobInfo>,
    val goal: GoalInfo?,
    val progress: SessionProgress = SessionProgress(),
    val activity: ActivityInfo = ActivityInfo.NONE,
) {
    val displayTitle: String
        get() = when {
            !title.isNullOrBlank() -> title
            !cwd.isNullOrBlank() -> cwd.substringAfterLast('/').ifBlank { cwd }
            else -> id.take(12)
        }
}

/** `/phone/state` 的完整快照。 */
data class HarnessState(
    val ok: Boolean,
    val ts: Long,
    val model: ModelInfo?,
    val sessions: List<SessionInfo>,
    val jobs: List<JobInfo>,
    val error: String?,
    val totalSessions: Int,
    /**
     * 手机正开着的那个会话上挂着的待答问题（桥接每轮状态都带一份）。
     *
     * 为什么要从状态里也拿：会话流是快通道、会断，状态轮询是稳定通道。
     * 只看会话流的话，"推的那一刻没连着"就等于用户永远看不到这个问题。
     */
    val question: PendingQuestion? = null,
) {
    val runningCount: Int get() = sessions.count { it.running }
    val activeJobs: List<JobInfo> get() = jobs.filter { it.status == "running" || it.status == "stopping" }
    companion object {
        val EMPTY = HarnessState(false, 0L, null, emptyList(), emptyList(), null, 0)
    }
}

/** 聊天页里的一条消息。 */
data class ChatMessage(
    val seq: Int,
    val time: Long,
    val role: String,
    val text: String,
    val source: String? = null,
    val name: String? = null,
    /** 工具的调用摘要（命令 / 路径 / 查询），和电脑上卡片的标题一个意思。 */
    val title: String? = null,
    /** 摘要本身就是个文件路径时才有值 —— 这种标题可以点开看文档。 */
    val titlePath: String? = null,
    /** 助手的思考过程，独立于正文 —— 电脑上是折叠区块，手机上也分开渲染。 */
    val reasoning: String? = null,
    /** 这条消息属于第几轮第几步（仪表盘的"累计轮数"就是从这儿来的）。 */
    val turn: Int = 0,
    val step: Int = 0,
    /** 这条助手回复那次请求实际用了多少输入 token（= 当时的上下文占用）。 */
    val contextUsed: Int = 0,
    val isError: Boolean = false,
    val interrupted: Boolean = false,
    val usageIn: Int? = null,
    val usageOut: Int? = null,
) {
    /** harness 注入的上下文（插件、技能目录、运行时提醒）不是人说的话。 */
    val isInjected: Boolean get() = role == "user" && !source.isNullOrBlank() && source != "user"
}

/* ----------------------------- JSON 解析 ----------------------------- */

internal fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = optString(key, "")
    return v.ifBlank { null }
}

internal fun parseModel(o: JSONObject?): ModelInfo? {
    if (o == null) return null
    val provider = o.optString("provider", "")
    val model = o.optString("model", "")
    if (provider.isBlank() && model.isBlank()) return null
    return ModelInfo(provider, model, o.optStringOrNull("reasoningEffort"))
}

internal fun parseJobs(arr: JSONArray?): List<JobInfo> {
    if (arr == null) return emptyList()
    val out = ArrayList<JobInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            JobInfo(
                id = o.optString("id", ""),
                kind = o.optString("kind", ""),
                label = o.optString("label", ""),
                status = o.optString("status", ""),
                startedAt = o.optLong("startedAt", 0L),
                finishedAt = if (o.isNull("finishedAt")) null else o.optLong("finishedAt", 0L),
                detail = o.optStringOrNull("detail"),
                owner = o.optStringOrNull("owner"),
            )
        )
    }
    return out
}

internal fun parseProgress(o: JSONObject?): SessionProgress {
    if (o == null || o == JSONObject.NULL) return SessionProgress()
    return SessionProgress(
        turn = o.optInt("turn", 0),
        step = o.optInt("step", 0),
        turns = o.optInt("turns", 0),
        contextWindow = o.optInt("contextWindow", 0),
        contextUsed = o.optInt("contextUsed", 0),
    )
}

internal fun parseGoal(o: JSONObject?): GoalInfo? {
    if (o == null || o == JSONObject.NULL) return null
    val objective = o.optString("objective", "")
    if (objective.isBlank()) return null
    return GoalInfo(
        objective = objective,
        phase = o.optString("phase", ""),
        roundsStarted = o.optInt("roundsStarted", 0),
        maxGoalRounds = if (o.isNull("maxGoalRounds")) null else o.optInt("maxGoalRounds", 0),
        blockedReason = o.optStringOrNull("blockedReason"),
        id = o.optString("id", ""),
        revision = o.optInt("revision", 0),
        createdAt = o.optLong("createdAt", 0L),
        updatedAt = o.optLong("updatedAt", 0L),
    )
}

internal fun parseState(root: JSONObject): HarnessState {
    val sessions = ArrayList<SessionInfo>()
    val arr = root.optJSONArray("sessions")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sessions.add(
                SessionInfo(
                    id = o.optString("id", ""),
                    title = o.optStringOrNull("title"),
                    running = o.optBoolean("running", false),
                    blank = o.optBoolean("blank", false),
                    updatedAt = o.optLong("updatedAt", 0L),
                    cwd = o.optStringOrNull("cwd"),
                    origin = o.optStringOrNull("origin"),
                    parent = o.optStringOrNull("parent"),
                    live = o.optBoolean("live", false),
                    jobs = parseJobs(o.optJSONArray("jobs")),
                    goal = parseGoal(if (o.isNull("goal")) null else o.optJSONObject("goal")),
                    progress = parseProgress(o.optJSONObject("progress")),
                    activity = parseActivity(o.optJSONObject("activity")),
                )
            )
        }
    }
    return HarnessState(
        ok = root.optBoolean("ok", false),
        ts = root.optLong("ts", 0L),
        model = parseModel(if (root.isNull("model")) null else root.optJSONObject("model")),
        sessions = sessions,
        jobs = parseJobs(root.optJSONArray("jobs")),
        error = root.optStringOrNull("error"),
        totalSessions = root.optInt("totalSessions", sessions.size),
        question = if (root.isNull("question")) {
            null
        } else {
            root.optJSONObject("question")?.let { parsePendingQuestion(it) }
        },
    )
}

internal fun parseMessages(arr: JSONArray?): List<ChatMessage> {
    if (arr == null) return emptyList()
    val out = ArrayList<ChatMessage>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val usage = if (o.isNull("usage")) null else o.optJSONObject("usage")
        out.add(
            ChatMessage(
                seq = o.optInt("seq", 0),
                time = o.optLong("time", 0L),
                role = o.optString("role", "assistant"),
                text = o.optString("text", ""),
                source = o.optStringOrNull("source"),
                name = o.optStringOrNull("name"),
                title = o.optStringOrNull("title"),
                titlePath = o.optStringOrNull("titlePath"),
                reasoning = o.optStringOrNull("reasoning"),
                turn = o.optInt("turn", 0),
                step = o.optInt("step", 0),
                // 与宿主同口径：上下文压力 = input + 缓存命中 + 缓存写入。
                // 只算 input 会和电脑右下角的数字差一个数量级（缓存命中往往是主体）
                contextUsed = usage?.let { u ->
                    val a = if (u.isNull("input")) 0 else u.optInt("input", 0)
                    val b = if (u.isNull("cacheRead")) 0 else u.optInt("cacheRead", 0)
                    val c = if (u.isNull("cacheWrite")) 0 else u.optInt("cacheWrite", 0)
                    a + b + c
                } ?: 0,
                isError = o.optBoolean("isError", false),
                interrupted = o.optBoolean("interrupted", false),
                usageIn = usage?.let { if (it.isNull("input")) null else it.optInt("input") },
                usageOut = usage?.let { if (it.isNull("output")) null else it.optInt("output") },
            )
        )
    }
    return out
}

/* --------------------------- 工作区 / 目录 --------------------------- */

/** 某个工作区下的一个会话。 */
data class WorkspaceSession(
    val id: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val updatedAt: Long,
    val cwd: String?,
    val activity: ActivityInfo = ActivityInfo.NONE,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: id.take(12)
}

/** harness 的一个工作区（项目文件夹）。历史与当前的一并在这里。 */
data class WorkspaceInfo(
    val id: String,
    val path: String,
    val title: String,
    val sessionCount: Int,
    val runningCount: Int,
    val updatedAt: String?,
    val sessions: List<WorkspaceSession>,
) {
    val displayTitle: String get() = title.ifBlank { path.substringAfterLast('/').ifBlank { path } }
}

/** 目录浏览里的一项。 */
data class DirEntry(val name: String, val path: String, val hidden: Boolean)

/** 一层目录列表 + 面包屑。 */
data class DirListing(
    val path: String,
    val home: String,
    val crumbs: List<DirEntry>,
    val entries: List<DirEntry>,
) {
    companion object { val EMPTY = DirListing("", "", emptyList(), emptyList()) }
}

internal fun parseWorkspaceSessions(arr: JSONArray?): List<WorkspaceSession> {
    if (arr == null) return emptyList()
    val out = ArrayList<WorkspaceSession>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            WorkspaceSession(
                id = o.optString("id", ""),
                title = o.optStringOrNull("title"),
                running = o.optBoolean("running", false),
                blank = o.optBoolean("blank", false),
                updatedAt = o.optLong("updatedAt", 0L),
                cwd = o.optStringOrNull("cwd"),
                activity = parseActivity(o.optJSONObject("activity")),
            )
        )
    }
    return out
}

internal fun parseActivity(o: JSONObject?): ActivityInfo {
    if (o == null || o == JSONObject.NULL) return ActivityInfo.NONE
    return ActivityInfo(
        lastReplySeq = o.optLong("lastReplySeq", 0L),
        lastReplyAt = o.optLong("lastReplyAt", 0L),
        agentSpokeLast = o.optBoolean("agentSpokeLast", false),
    )
}

internal fun parseWorkspaces(root: JSONObject): List<WorkspaceInfo> {
    val arr = root.optJSONArray("workspaces") ?: return emptyList()
    val out = ArrayList<WorkspaceInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            WorkspaceInfo(
                id = o.optString("id", ""),
                path = o.optString("path", ""),
                title = o.optString("title", ""),
                sessionCount = o.optInt("sessionCount", 0),
                runningCount = o.optInt("runningCount", 0),
                updatedAt = o.optStringOrNull("updatedAt"),
                sessions = parseWorkspaceSessions(o.optJSONArray("sessions")),
            )
        )
    }
    return out
}

internal fun parseDirEntries(arr: JSONArray?): List<DirEntry> {
    if (arr == null) return emptyList()
    val out = ArrayList<DirEntry>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            DirEntry(
                name = o.optString("name", ""),
                path = o.optString("path", ""),
                hidden = o.optBoolean("hidden", false),
            )
        )
    }
    return out
}

internal fun parseDirListing(root: JSONObject): DirListing = DirListing(
    path = root.optString("path", ""),
    home = root.optString("home", ""),
    crumbs = parseDirEntries(root.optJSONArray("crumbs")),
    entries = parseDirEntries(root.optJSONArray("entries")),
)

/* ----------------------------- 用户提问 ----------------------------- */

data class QuestionOption(val label: String, val description: String?)

data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String?,
    val header: String?,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
)

/**
 * 等待手机回答的一组问题（harness 的 ask_user_question）。
 *
 * [sessionId] 是**哪条会话在问**：状态轮询里带的那个问题可能属于别的会话，
 * 只有 openSessionId 与它一致时才该显示在当前聊天页上。
 */
data class PendingQuestion(
    val id: String,
    val questions: List<QuestionItem>,
    val sessionId: String = "",
)

/** 一条回答：选中的选项标签 + 可选的自由文本。 */
data class QuestionAnswer(val id: String, val selected: List<String>, val custom: String?)

internal fun parseQuestionOptions(arr: JSONArray?): List<QuestionOption> {
    if (arr == null) return emptyList()
    val out = ArrayList<QuestionOption>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val label = o.optString("label", "")
        if (label.isBlank()) continue
        out.add(QuestionOption(label, o.optStringOrNull("description")))
    }
    return out
}

internal fun parseQuestions(arr: JSONArray?): List<QuestionItem> {
    if (arr == null) return emptyList()
    val out = ArrayList<QuestionItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            QuestionItem(
                id = o.optString("id", "q$i"),
                question = o.optString("question", ""),
                detail = o.optStringOrNull("detail"),
                header = o.optStringOrNull("header"),
                options = parseQuestionOptions(o.optJSONArray("options")),
                multiSelect = o.optBoolean("multiSelect", false),
            )
        )
    }
    return out
}

internal fun parsePendingQuestion(root: JSONObject): PendingQuestion? {
    val id = root.optString("id", "")
    val qs = parseQuestions(root.optJSONArray("questions"))
    if (id.isBlank() || qs.isEmpty()) return null
    return PendingQuestion(id, qs, root.optString("sessionId", ""))
}

/**
 * 状态轮询里带的待答问题，该不该显示在当前聊天页上？
 *
 * 规则只有两条，但都很容易写错：
 *   1. **只显示本会话的**：状态是整机的，问题可能属于别的会话；
 *   2. **只增不清**：返回 null 表示"这次快照没说别的"，而不是"要把卡片撤掉"。
 *      清除只由 question-done、回答成功、切会话负责 —— 否则一份早于问题到达的
 *      快照会把刚显示出来的卡片抹掉（用户看到的就是"闪一下没了"）。
 */
fun questionToShow(
    current: PendingQuestion?,
    fromState: PendingQuestion?,
    openSessionId: String,
): PendingQuestion? {
    if (fromState == null) return current
    if (openSessionId.isEmpty() || fromState.sessionId != openSessionId) return current
    if (current != null && current.id == fromState.id) return current
    return fromState
}

/* ------------------------------ 模型目录 ------------------------------ */

data class ModelEffort(val id: String, val name: String)

data class ModelOption(
    val id: String,
    val name: String,
    val description: String?,
    val efforts: List<ModelEffort>,
    val defaultEffort: String?,
) {
    /** 当前会话选的是这个模型吗（不看 effort）。 */
    fun matches(provider: String, model: String): Boolean = id == model
}

data class ModelGroup(val id: String, val name: String, val models: List<ModelOption>)

data class ModelCatalog(
    val default: ModelSelection?,
    val current: ModelSelection?,
    val groups: List<ModelGroup>,
    val failures: List<String>,
) {
    companion object { val EMPTY = ModelCatalog(null, null, emptyList(), emptyList()) }
}

/** provider + model + 可选推理强度。 */
data class ModelSelection(val provider: String, val model: String, val effort: String?) {
    val short: String get() = model
}

internal fun parseModelSelection(o: JSONObject?): ModelSelection? {
    if (o == null || o == JSONObject.NULL) return null
    val p = o.optString("provider", "")
    val m = o.optString("model", "")
    if (p.isBlank() && m.isBlank()) return null
    return ModelSelection(p, m, o.optStringOrNull("reasoningEffort"))
}

internal fun parseModelCatalog(root: JSONObject): ModelCatalog {
    val groups = ArrayList<ModelGroup>()
    val arr = root.optJSONArray("groups")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i) ?: continue
            val models = ArrayList<ModelOption>()
            val ms = g.optJSONArray("models")
            if (ms != null) {
                for (k in 0 until ms.length()) {
                    val m = ms.optJSONObject(k) ?: continue
                    val efforts = ArrayList<ModelEffort>()
                    val es = m.optJSONArray("efforts")
                    if (es != null) {
                        for (e in 0 until es.length()) {
                            val eo = es.optJSONObject(e) ?: continue
                            efforts.add(ModelEffort(eo.optString("id", ""), eo.optString("name", "")))
                        }
                    }
                    models.add(
                        ModelOption(
                            id = m.optString("id", ""),
                            name = m.optString("name", ""),
                            description = m.optStringOrNull("description"),
                            efforts = efforts,
                            defaultEffort = m.optStringOrNull("defaultEffort"),
                        )
                    )
                }
            }
            groups.add(ModelGroup(g.optString("id", ""), g.optString("name", ""), models))
        }
    }
    val fails = ArrayList<String>()
    root.optJSONArray("failures")?.let { fa ->
        for (i in 0 until fa.length()) {
            val f = fa.optJSONObject(i) ?: continue
            fails.add(f.optString("name", "") + ": " + f.optString("message", ""))
        }
    }
    return ModelCatalog(
        default = parseModelSelection(root.optJSONObject("default")),
        current = parseModelSelection(root.optJSONObject("current")),
        groups = groups,
        failures = fails,
    )
}

/** 从桥接读回来的一个文本文件。 */
data class FileText(val path: String, val text: String, val size: Int, val truncated: Boolean)

/** 文档阅读页的状态。 */
data class DocState(
    val path: String,
    val content: String? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val truncated: Boolean = false,
) {
    val fileName: String get() = path.substringAfterLast('/').ifBlank { path }
    val isMarkdown: Boolean
        get() {
            val n = fileName.lowercase()
            return n.endsWith(".md") || n.endsWith(".markdown")
        }
}

/** 电脑上开出来的一个终端（真 PTY）。 */
data class TermOpenResult(
    val id: String,
    val pid: Int,
    val cwd: String,
    val cols: Int,
    val rows: Int,
)

/**
 * 手机终端的界面状态。
 *
 * `lines` 是**只追加**的行表：桥接那边一段输出就 feed 一次，
 * 这里的 [rev] 每变一次就代表"内容变了"，Compose 靠它决定要不要重画
 * —— 不能只比较 `lines` 的引用，因为同一个列表还在被追加。
 */
data class TermState(
    val rev: Long = 0,
    val id: String = "",
    val opening: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val pid: Int = 0,
    val cwd: String = "",
    val cols: Int = 0,
    val rows: Int = 0,
    /** 渲染列表 = 历史滚动 + 当前屏幕（`TermScreen` 拼好给界面的那份）。 */
    val lines: List<AnsiLine> = emptyList(),
    /**
     * 光标在 [lines] 里的下标（没有光标时为 null）—— 自动滚动就跟着它走。
     *
     * 全屏程序里即使终端报告"隐藏光标"（`?25l`）也会给出行号，
     * 好让界面画一个淡的光标位置提示（见 TermScreen.cursorKind 的说明）。
     */
    val cursorLine: Int? = null,
    /** 光标列（0 基）。 */
    val cursorCol: Int = 0,
    /** 终端自己说光标可见吗（`?25h` / `?25l`）。 */
    val cursorVisible: Boolean = true,
    /**
     * 正在跑全屏程序（vim/htop/less 用的是备用屏）。
     *
     * 界面据此换一套输入方式：全屏程序要的是**逐键直通**，
     * 而不是"写好一行再回车"——后者会把 vim 的插入模式用废。
     */
    val fullScreen: Boolean = false,
    val exited: Boolean = false,
    val exitCode: Int? = null,
    /**
     * 终端页没开着（我们主动断开了输出流，但电脑上的 shell 还活着）。
     *
     * ★ 必须和"连接断了"分开：原来离开页面就把 connected 置 false，
     *   状态页于是显示"连接中断，重连中…" —— 明明一切正常，
     *   用户看到的是"终端断开或已关闭"。状态文案骗人比没有文案更糟。
     */
    val detached: Boolean = false,
) {
    val alive: Boolean get() = id.isNotEmpty() && !exited

    /** 电脑上还有一个我们能接上的终端（不管此刻有没有在收输出）。 */
    val resumable: Boolean get() = alive && detached

    val statusText: String
        get() = when {
            opening -> "正在电脑上开终端…"
            error != null -> error
            id.isEmpty() -> "未打开"
            exited -> "已退出" + (exitCode?.let { "（code $it）" } ?: "")
            detached -> "已挂起 · 电脑上还开着"
            connected -> "已连接"
            else -> "连接中断，重连中…"
        }
}

/**
 * 一条还没轮到执行的排队消息。
 *
 * placement 是宿主给的：`queued` 正常排队、`steering` 是"插话"（下一轮立刻带上）、
 * `context` 是塞进去当上下文。手机只对 queued 的显示「立刻插话」。
 */
data class QueuedMessage(
    val id: String,
    val text: String,
    val placement: String = "queued",
)

/** 桥接返回的排队项 → 手机侧模型。 */
internal fun parseQueue(arr: JSONArray?): List<QueuedMessage> {
    if (arr == null) return emptyList()
    val out = ArrayList<QueuedMessage>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val id = o.optString("id", "")
        if (id.isEmpty()) continue
        out.add(
            QueuedMessage(
                id = id,
                text = o.optString("text", ""),
                placement = o.optString("placement", "queued"),
            ),
        )
    }
    return out
}

/* ==================== 会话里的"后台在跑什么" ==================== */

/** 一个子代理（harness 里就是"父会话下的子会话"）。 */
data class SubagentInfo(
    val id: String,
    val title: String,
    val running: Boolean,
    /** 层级：直接子是 1，子的子 是 2 ……用来画缩进。 */
    val depth: Int,
    val updatedAt: Long,
)

/** 某个会话此刻的后台活动：后台任务 + 子代理。 */
data class SessionActivity(
    val jobs: List<JobInfo> = emptyList(),
    val subagents: List<SubagentInfo> = emptyList(),
) {
    val runningJobs: List<JobInfo> get() = jobs.filter { it.status == "running" || it.status == "stopping" }
    val runningSubagents: List<SubagentInfo> get() = subagents.filter { it.running }
    val isEmpty: Boolean get() = jobs.isEmpty() && subagents.isEmpty()
    val nothingRunning: Boolean get() = runningJobs.isEmpty() && runningSubagents.isEmpty()

    /**
     * 值不值得在聊天页占一张卡。
     *
     * 不是"有记录就显示"：一个会话昨天派过子代理，今天还挂着张卡就没意义了。
     * 规则是**有在跑的**，或者**刚结束的**（默认 10 分钟内）——
     * 后者是为了让"刚跑完，结果在这儿"也能被看见。
     */
    fun worthShowing(now: Long, freshMs: Long = 10 * 60 * 1000L): Boolean {
        if (runningJobs.isNotEmpty() || runningSubagents.isNotEmpty()) return true
        val cutoff = now - freshMs
        return jobs.any { (it.finishedAt ?: it.startedAt) > cutoff } ||
            subagents.any { it.updatedAt > cutoff }
    }
}

/**
 * 从整机快照里挑出"这个会话自己在跑什么"。
 *
 * 为什么不用额外请求：`/phone/state` 本来就每 5 秒拉一次，而且已经带了
 * 每个后台任务的 owner、每个会话的 parent —— 子代理在 harness 里
 * **就是一条子会话**，所以"谁是我的子代理"= `parent == 我`。
 * 再往下递归，孙代理也一并收进来（电脑端的树状列表就是这个意思）。
 *
 * 纯函数，好测：这是"看着像对、错了很难发现"的那类逻辑（漏掉孙代理、
 * 把别人的任务算进来）。
 */
fun sessionActivity(state: HarnessState, sessionId: String): SessionActivity {
    if (sessionId.isEmpty()) return SessionActivity()

    // 后台任务：owner 就是本会话的那些（按开始时间倒序，新的在前）
    val jobs = state.jobs
        .filter { it.owner == sessionId }
        .sortedByDescending { it.startedAt }

    // 子代理：从本会话出发按 parent 关系做宽度优先，收全部后代
    val byParent = HashMap<String, MutableList<SessionInfo>>()
    state.sessions.forEach { s ->
        val p = s.parent
        if (!p.isNullOrEmpty()) byParent.getOrPut(p) { ArrayList() }.add(s)
    }
    val out = ArrayList<SubagentInfo>()
    val seen = HashSet<String>()
    var frontier = listOf(sessionId)
    var depth = 0
    while (frontier.isNotEmpty() && depth < 8) {
        depth += 1
        val next = ArrayList<String>()
        frontier.forEach { pid ->
            byParent[pid].orEmpty().forEach { child ->
                if (!seen.add(child.id)) return@forEach
                out.add(
                    SubagentInfo(
                        id = child.id,
                        title = child.displayTitle,
                        running = child.running,
                        depth = depth,
                        updatedAt = child.updatedAt,
                    ),
                )
                next.add(child.id)
            }
        }
        frontier = next
    }
    // 在跑的排前面，其次按最近活动
    out.sortWith(compareByDescending<SubagentInfo> { it.running }.thenByDescending { it.updatedAt })
    return SessionActivity(jobs = jobs, subagents = out)
}

/* ==================== 会话仪表盘：轮/步、目标、上下文 ==================== */

/**
 * 会话进度。
 *
 * 三个来源，取"最全"的合并（见 [mergeProgress]）：
 *   1. `/phone/state` 里桥接镜像的那份 —— 冷会话也有（桥接挂载后攒的）；
 *   2. 会话实时流推的 `progress` 事件 —— 正在看的时候最即时；
 *   3. **历史消息自带的 turn/step/usage** —— 最可靠，
 *      因为它读的是会话日志本身，App 重开、桥接重启都不受影响。
 *
 * 为什么轮号要取"累计最大值"：`turn/start` 里的 turn 是 1 起递增的，
 * 所以历史里最大的 turn 就是"一共跑了多少轮"；而当前轮的"第几步"看 step。
 */
data class SessionProgress(
    /** 当前轮（1 起；0 = 还没有过）。 */
    val turn: Int = 0,
    /** 当前轮里的第几步。 */
    val step: Int = 0,
    /** 累计轮数。 */
    val turns: Int = 0,
    /** 模型这次可用的上下文窗口（0 = 还不知道）。 */
    val contextWindow: Int = 0,
    /** 上下文占用（与电脑端同口径：input + 缓存命中 + 缓存写入）。 */
    val contextUsed: Int = 0,
) {
    val hasAnything: Boolean get() = turn > 0 || turns > 0 || contextUsed > 0

    /** 上下文占用比例（0..1）；窗口未知时返回 null —— 不要瞎画进度条。 */
    val contextRatio: Float?
        get() = if (contextWindow > 0 && contextUsed > 0) {
            (contextUsed.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }

    /** 一句话摘要：`第 3 轮 · 第 7 步 · 累计 12 轮`。 */
    val text: String
        get() = buildString {
            if (turn > 0) append("第 ").append(turn).append(" 轮")
            if (step > 0) {
                if (isNotEmpty()) append(" · ")
                append("第 ").append(step).append(" 步")
            }
            val total = maxOf(turns, turn)
            if (total > 1) {
                if (isNotEmpty()) append(" · ")
                append("累计 ").append(total).append(" 轮")
            }
            if (isEmpty()) append("还没开始跑")
        }
}

/**
 * 合并三个来源的进度。
 *
 * 规则是一句话：**每个字段取"更完整"的那个**。
 * 桥接镜像可能是刚挂载的空壳（turns=0），历史里的 turn 却是真的；
 * 而 contextWindow 只有实时事件里才有。所以不能整体二选一，要逐字段取。
 */
fun mergeProgress(vararg sources: SessionProgress?): SessionProgress {
    var turn = 0
    var step = 0
    var turns = 0
    var window = 0
    var used = 0
    sources.forEach { p ->
        if (p == null) return@forEach
        if (p.turn > turn) turn = p.turn
        if (p.step > step) step = p.step
        if (p.turns > turns) turns = p.turns
        // 上下文这两项**取靠后那个非零值**，不取最大。
        // 取最大会在上下文被压缩后卡住旧的大数字（回合数取最大是对的，
        // 占用不是 —— 它随时会因为 compaction 变小）。
        if (p.contextWindow > 0) window = p.contextWindow
        if (p.contextUsed > 0) used = p.contextUsed
    }
    return SessionProgress(turn, step, turns, window, used)
}

/**
 * token 数量的缩写 —— **逐字照抄电脑端右下角那个表**。
 *
 * 电脑端用的是 locale 文案 `number.thousand` = `{value}K`、`number.million` = `{value}M`，
 * 缩放后 ≥100 取整、否则留一位小数（见 ui-conversation 里那份 formatTokens）。
 *
 * 为什么要连格式都抄：用户就是拿手机和电脑上这两个数字对着核对的。
 * 一个写 `617.9K`、一个写 `617879`，即使数值完全一样也会让人以为又没对上。
 */
fun formatTokens(value: Int): String {
    if (value < 1000) return value.toString()
    fun scaled(c: Double): String {
        val r = if (c >= 100) Math.round(c).toDouble() else Math.round(c * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }
    return if (value < 1_000_000) {
        scaled(value / 1000.0) + "K"
    } else {
        scaled(value / 1_000_000.0) + "M"
    }
}

/** 上下文占用百分比，与电脑端同一个算法（取整、封顶 100）。 */
fun contextPercent(used: Int, window: Int): Int? {
    if (used <= 0 || window <= 0) return null
    return Math.min(100, Math.round(used.toFloat() / window.toFloat() * 100f))
}

/**
 * 把"权威进度"和"兜底进度"叠起来。
 *
 * 权威 = 桥接给的（就是电脑右下角那个数：宿主 `contextPressure` 投影，
 * 把缓存命中也算进去了）；兜底 = 从历史消息里按同口径推的，
 * **只在权威那一项还没有值的时候顶上去**。
 *
 * 不能简单取最大值：两者口径一致时最大无妨，可一旦上下文被压缩，
 * 权威数会变小，取最大就永远显示旧的大数字。
 */
fun overlayProgress(authoritative: SessionProgress, fallback: SessionProgress): SessionProgress =
    SessionProgress(
        turn = maxOf(authoritative.turn, fallback.turn),
        step = maxOf(authoritative.step, fallback.step),
        turns = maxOf(authoritative.turns, fallback.turns),
        contextWindow = if (authoritative.contextWindow > 0) {
            authoritative.contextWindow
        } else {
            fallback.contextWindow
        },
        contextUsed = if (authoritative.contextUsed > 0) {
            authoritative.contextUsed
        } else {
            fallback.contextUsed
        },
    )

/**
 * 从历史消息里读进度：**最大的 turn** 就是累计轮数，最后一条的 step 是当前步，
 * 最后一条助手消息的 usage.input 是那次请求的上下文占用。
 *
 * 为什么以它为准：这些数字是会话日志里本来就有的（`assistant/message` 事件带
 * turn/step/usage），App 重开、桥接重载都不影响 —— 而桥接自己攒的计数会丢。
 */
fun progressFromMessages(messages: List<ChatMessage>): SessionProgress? {
    if (messages.isEmpty()) return null
    var maxTurn = 0
    var lastStep = 0
    var lastUsed = 0
    messages.forEach { m ->
        if (m.turn > maxTurn) maxTurn = m.turn
        if (m.turn >= maxTurn && m.step > 0) lastStep = m.step
        if (m.turn >= maxTurn && m.contextUsed > 0) lastUsed = m.contextUsed
    }
    val cur = messages.lastOrNull { it.turn > 0 }
    return SessionProgress(
        turn = cur?.turn ?: maxTurn,
        step = cur?.step ?: lastStep,
        turns = maxTurn,
        contextUsed = lastUsed,
    )
}

/**
 * 一页历史消息。
 *
 * [hasMore] = 更早的地方还有内容（往上还能翻）；[oldestSeq] = 这一页最早那条的 seq，
 * 下次翻页就拿它当"beforeSeq"。
 */
data class HistoryPage(
    val messages: List<ChatMessage>,
    val hasMore: Boolean,
    val oldestSeq: Int,
)

/**
 * [limit] 之前最后一条"人发的"消息在列表里的下标；没有就 -1。
 *
 * 单独拎出来是为了能测：这条是"跳到上一条我的提问"的判据，
 * 而一轮里 tool / tool-result 能占几十条 —— 判据写错的表现是
 * "点了跳到一条工具结果上"，很难一眼看出来。
 */
fun lastUserIndexBefore(messages: List<ChatMessage>, limit: Int): Int {
    val from = minOf(limit, messages.size) - 1
    for (i in from downTo 0) {
        if (messages[i].role == "user") return i
    }
    return -1
}

/**
 * 这个目标值不值得占仪表盘一行。
 *
 * 规则（按"用户此刻关心什么"排）：
 *   - **active**（进行中）：显示 —— 它正在推进；
 *   - **blocked**（受阻）：显示 —— 需要人管，藏起来是最糟的；
 *   - **paused**（已暂停）：显示 —— 它在等人恢复；
 *   - **complete**（已完成）：**默认不显示**，只在刚完成的一小段时间里显示一下
 *     （给一个"跑完了"的确认），之后就不再占地方。
 *
 * 为什么已完成要收起来：宿主把完成的目标留在注册表里当墓碑，
 * 不清就一直存在 —— 一个几天前完成的目标天天挂在聊天页顶上，纯噪音。
 * [graceMs] 就是"刚完成还愿意显示多久"。
 */
fun goalWorthShowing(goal: GoalInfo?, now: Long, graceMs: Long = 10 * 60 * 1000L): Boolean {
    if (goal == null) return false
    return when (goal.phase) {
        "complete", "completed" -> goal.updatedAt > now - graceMs
        else -> true
    }
}

/* ---------- 排队消息"改到一半"的草稿：编码与解码 ---------- */

/**
 * 草稿存成「itemId + 换行 + 正文」。
 *
 * 为什么把编解码单独拎出来：**正文自己就可能带换行**（用户改的是一段多行命令），
 * 所以只能按**第一个**换行切分 —— 按最后一个切、或者 split 全部，
 * 多行修改就会被切坏（而且坏得很隐蔽：单行的时候完全正常）。
 */
fun formatQueueEditDraft(itemId: String, text: String): String = itemId + "\n" + text

/** 解不出来就返回 null（宁可当没有草稿，也不要拿半截内容去覆盖用户的输入框）。 */
fun parseQueueEditDraft(raw: String?): Pair<String, String>? {
    if (raw.isNullOrBlank()) return null
    val i = raw.indexOf('\n')
    if (i <= 0) return null
    val id = raw.substring(0, i)
    if (id.isBlank()) return null
    return id to raw.substring(i + 1)
}

/**
 * 选中了、还没发出去的文件。
 *
 * ★ 它**还在手机上**（App 私有缓存里），点发送的那一刻才传给电脑 ——
 * 所以选了又删不会在电脑上留下任何孤儿文件。
 */
data class PendingAttachment(
    /** 手机本地缓存里的路径（选中那一刻写进去的）。 */
    val path: String,
    val name: String,
    val bytes: Int,
    /** 传给电脑后拿到的凭据；null = 还没传（发送时才传）。 */
    val receiptId: String? = null,
) {
    /** 列表里的稳定标识（用文件名即可，路径是每份草稿独立的）。 */
    val localId: String get() = path.substringAfterLast('/')

    val sizeText: String
        get() = when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
}

/**
 * 待发附件列表 ↔ 存档文本。
 *
 * 格式：每行 `localId \t name \t bytes \t path \t receiptId`。
 * 为什么用制表符而不是 JSON：这里的字段都是"不会有制表符"的东西
 * （id/数字/路径），而**文件名可能什么都有** —— 所以写的时候把文件名里的
 * 制表符与换行换成空格（它只用于显示与上传名的提示，换掉无害）。
 *
 * 为什么要落盘：附件属于"这条消息的草稿"。以前选中即上传，
 * 列表却只在内存里 —— 退出 App 就丢，而电脑上文件已经躺着了（孤儿）。
 * 现在改成"选中只落手机、发送才传"，那列表自然也得跟着草稿一起存下来。
 */
fun formatAttachList(list: List<PendingAttachment>): String =
    list.joinToString("\n") { a ->
        val clean = a.name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        listOf(a.localId, clean, a.bytes.toString(), a.path, a.receiptId ?: "").joinToString("\t")
    }

fun parseAttachList(text: String): List<PendingAttachment> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<PendingAttachment>()
    text.split('\n').forEach { line ->
        if (line.isBlank()) return@forEach
        val f = line.split('\t')
        if (f.size < 4) return@forEach
        val bytes = f[2].toIntOrNull() ?: 0
        val receipt = if (f.size > 4 && f[4].isNotBlank()) f[4] else null
        out.add(PendingAttachment(path = f[3], name = f[1], bytes = bytes, receiptId = receipt))
    }
    return out
}
