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
)

/** 会话当前的长任务目标。 */
data class GoalInfo(
    val objective: String,
    val phase: String,
    val roundsStarted: Int,
    val maxGoalRounds: Int?,
    val blockedReason: String?,
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

/** 一个 harness 会话的概要。 */
data class SessionInfo(
    val id: String,
    val title: String?,
    val running: Boolean,
    val blank: Boolean,
    val updatedAt: Long,
    val cwd: String?,
    val origin: String?,
    val live: Boolean,
    val jobs: List<JobInfo>,
    val goal: GoalInfo?,
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
            )
        )
    }
    return out
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
                    live = o.optBoolean("live", false),
                    jobs = parseJobs(o.optJSONArray("jobs")),
                    goal = parseGoal(if (o.isNull("goal")) null else o.optJSONObject("goal")),
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

/** 等待手机回答的一组问题（harness 的 ask_user_question）。 */
data class PendingQuestion(val id: String, val questions: List<QuestionItem>)

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
    return PendingQuestion(id, qs)
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
    val lines: List<AnsiLine> = emptyList(),
    val currentLine: AnsiLine = emptyList(),
    val exited: Boolean = false,
    val exitCode: Int? = null,
) {
    val alive: Boolean get() = id.isNotEmpty() && !exited
    val statusText: String
        get() = when {
            opening -> "正在电脑上开终端…"
            error != null -> error
            id.isEmpty() -> "未打开"
            exited -> "已退出" + (exitCode?.let { "（code $it）" } ?: "")
            connected -> "已连接"
            else -> "连接中断，重连中…"
        }
}
