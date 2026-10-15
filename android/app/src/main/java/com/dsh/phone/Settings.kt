package com.dsh.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * 极简设置存储。
 *
 * 端点是一份**有序列表**，第一个可达的胜出：
 *   1. http://127.0.0.1:3080            —— 在家插线时走 adb reverse（USB，最快最稳）
 *   2. http://dsh-home.xxx.ts.net:3080  —— 在外走 Tailscale（蜂窝网也能到）
 * 这样同一个 App 在家里和在外面都不用改设置。
 *
 * 注意：这里**不存任何会话数据**。会话的唯一权威存储是电脑上的
 * ~/.dsh/sessions，手机只持有内存里的视图和这几个连接参数。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("dsh-phone", Context.MODE_PRIVATE)

    /** 端点列表，逗号或换行分隔；纯文本存储，便于用 adb intent 直接灌进来。 */
    var endpointsRaw: String
        get() = prefs.getString(KEY_ENDPOINTS, DEFAULT_ENDPOINTS) ?: DEFAULT_ENDPOINTS
        set(value) = prefs.edit().putString(KEY_ENDPOINTS, value.trim()).apply()

    /** 解析成有序、去重、去掉空行的端点列表。 */
    fun endpointList(): List<String> {
        val seen = LinkedHashSet<String>()
        endpointsRaw.split(',', '\n', '\r', ' ')
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
            .forEach { seen.add(it) }
        return if (seen.isEmpty()) listOf(DEFAULT_PRIMARY) else seen.toList()
    }

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /** 聊天里是否显示 harness 注入的上下文消息（插件提醒、技能目录等）。 */
    var showInjected: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INJECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_INJECTED, value).apply()

    /** 是否开启常驻通知栏状态。 */
    var notificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY, value).apply()

    /** 上次打开的会话 id。 */
    var lastSessionId: String
        get() = prefs.getString(KEY_LAST_SESSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SESSION, value).apply()

    /**
     * 已读 watermark：每个会话「手机看过的最新回复 seq」。
     *
     * 只存在手机上 —— 已读是每个观察者自己的状态，不该写回电脑的会话存储。
     * 换手机/重装后重新计未读，这是符合预期的。
     */
    fun seenReplySeq(sessionId: String): Long = prefs.getLong(SEEN_PREFIX + sessionId, 0L)

    fun markReplySeen(sessionId: String, seq: Long) {
        if (sessionId.isBlank() || seq <= 0L) return
        if (prefs.getLong(SEEN_PREFIX + sessionId, 0L) >= seq) return
        prefs.edit().putLong(SEEN_PREFIX + sessionId, seq).apply()
    }

    /**
     * 未发送的输入草稿，按会话分开存。
     *
     * 为什么不用 rememberSaveable：那只能扛住配置变更和进程被回收，
     * **完全退出 App 就没了**。用户要的是"误退出也不丢"，所以落盘。
     */
    fun draft(sessionId: String): String =
        if (sessionId.isBlank()) "" else prefs.getString(DRAFT_PREFIX + sessionId, "") ?: ""

    fun setDraft(sessionId: String, text: String) {
        if (sessionId.isBlank()) return
        if (text.isEmpty()) prefs.edit().remove(DRAFT_PREFIX + sessionId).apply()
        else prefs.edit().putString(DRAFT_PREFIX + sessionId, text).apply()
    }

    /**
     * 正在改的那条排队消息（还没保存的修改）。
     *
     * 为什么落盘：改排队消息时被电话/返回手势打断是常事，而那段文字是用户
     * **刚打出来的**，丢了最气人。存成「itemId + 换行 + 正文」——
     * 一个会话同时只可能在改一条。
     */
    fun queueEditDraft(sessionId: String): Pair<String, String>? {
        if (sessionId.isBlank()) return null
        // 编解码在 Models.kt（纯函数，有单测：正文自己带换行时也不能切坏）
        return parseQueueEditDraft(prefs.getString(QUEUE_EDIT_PREFIX + sessionId, null))
    }

    fun setQueueEditDraft(sessionId: String, itemId: String, text: String) {
        if (sessionId.isBlank() || itemId.isBlank()) return
        prefs.edit().putString(QUEUE_EDIT_PREFIX + sessionId, formatQueueEditDraft(itemId, text)).apply()
    }

    fun clearQueueEditDraft(sessionId: String) {
        if (sessionId.isBlank()) return
        prefs.edit().remove(QUEUE_EDIT_PREFIX + sessionId).apply()
    }

    /**
     * 用户手动展开（没收起）的工作区 id。
     *
     * 落盘原因：展开状态原本是聊天页列表里的 remember，一进会话就被销毁，
     * 返回时全部收起 —— 用户就没法"看我最近看过哪些工作区"。
     */
    var expandedWorkspaces: Set<String>
        get() = (prefs.getStringSet(KEY_EXPANDED, emptySet()) ?: emptySet()).toSet()
        set(value) = prefs.edit().putStringSet(KEY_EXPANDED, value).apply()

    /**
     * 手机上最近打开过的会话：会话 id → 最后一次打开的时间（新的在前）。
     *
     * 这块记忆**只存在手机上**，电脑端的会话存储格式一个字节都不改 ——
     * 换手机就重新攒，这是符合预期的。
     *
     * 为什么需要：手机屏幕小，工作区树一展开就滚不到底，
     * 而「我刚才在看的那个会话」才是最高频的目标。
     */
    fun recentOpenedAt(): Map<String, Long> {
        val raw = prefs.getString(KEY_RECENT, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        val cutoff = System.currentTimeMillis() - RECENT_TTL_MS
        val out = LinkedHashMap<String, Long>()
        raw.split('\n').forEach { line ->
            // 用 lastIndexOf：会话 id 里没有冒号，但万一以后有了也不会解析错
            val i = line.lastIndexOf(':')
            if (i <= 0) return@forEach
            val id = line.substring(0, i).trim()
            val at = line.substring(i + 1).trim().toLongOrNull() ?: return@forEach
            if (id.isEmpty() || at < cutoff || out.containsKey(id)) return@forEach
            out[id] = at
        }
        return out
    }

    /** 记一笔「刚打开过这个会话」，新的排最前，最旧的挤出去。 */
    fun markSessionOpened(sessionId: String, at: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank()) return
        val next = LinkedHashMap<String, Long>()
        next[sessionId] = at
        // recentOpenedAt() 顺手丢掉过期的，所以这张表自己会收敛
        recentOpenedAt().forEach { (id, t) ->
            if (next.size < RECENT_MAX && !next.containsKey(id)) next[id] = t
        }
        writeRecent(next)
    }

    /** 丢掉已经不存在的会话（被归档了，或整个工作区被删了）。 */
    fun pruneRecent(existing: Set<String>) {
        val cur = recentOpenedAt()
        if (cur.keys.all { existing.contains(it) }) return
        val next = LinkedHashMap<String, Long>()
        cur.forEach { (id, t) -> if (existing.contains(id)) next[id] = t }
        writeRecent(next)
    }

    fun clearRecent() = prefs.edit().remove(KEY_RECENT).apply()

    private fun writeRecent(map: Map<String, Long>) {
        if (map.isEmpty()) {
            prefs.edit().remove(KEY_RECENT).apply()
            return
        }
        val sb = StringBuilder()
        map.forEach { (id, t) ->
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(id).append(':').append(t)
        }
        prefs.edit().putString(KEY_RECENT, sb.toString()).apply()
    }

    /**
     * 每个会话上次离开时的阅读位置（滚动到第几条、偏移、是否在跟随最新）。
     *
     * 存盘是为了扛住"退出 App 再进来"—— 只靠 ViewModel 的话，
     * 进程一没就回到最新了。
     */
    fun chatScroll(sessionId: String): SavedScroll? {
        if (sessionId.isBlank()) return null
        val raw = prefs.getString(SCROLL_PREFIX + sessionId, null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 3) return null
        return runCatching {
            SavedScroll(parts[0].toInt(), parts[1].toInt(), parts[2] == "1")
        }.getOrNull()
    }

    fun saveChatScroll(sessionId: String, index: Int, offset: Int, followTail: Boolean) {
        if (sessionId.isBlank()) return
        prefs.edit()
            .putString(SCROLL_PREFIX + sessionId, "$index,$offset," + (if (followTail) "1" else "0"))
            .apply()
    }

    companion object {
        private const val SCROLL_PREFIX = "scroll_"
        private const val KEY_EXPANDED = "expandedWorkspaces"
        private const val KEY_RECENT = "recentSessions"

        /**
         * 「最近会话」最多记这么多条。
         *
         * 比界面上显示的条数（`RECENT_SHOWN` = 6）多留一倍：某几条因为
         * "正在跑"或"有未读"被另外两栏借走时，还能往下补位，
         * 免得最近会话一眼看上去是空的。
         */
        private const val RECENT_MAX = 12

        /** 超过 30 天没点开过的自动忘掉。 */
        private const val RECENT_TTL_MS = 30L * 24 * 3600 * 1000
        private const val DRAFT_PREFIX = "draft_"
        private const val QUEUE_EDIT_PREFIX = "queue_edit_"
        private const val SEEN_PREFIX = "seen_"

        /** 在家：USB 反向映射，手机回环就是电脑。 */
        const val DEFAULT_PRIMARY = "http://127.0.0.1:3080"

        const val DEFAULT_ENDPOINTS = DEFAULT_PRIMARY

        private const val KEY_ENDPOINTS = "endpoints"
        private const val KEY_TOKEN = "token"
        private const val KEY_SHOW_INJECTED = "showInjected"
        private const val KEY_NOTIFY = "notify"
        private const val KEY_LAST_SESSION = "lastSessionId"
    }
}
