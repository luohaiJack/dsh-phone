package com.dsh.phone

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * 常驻前台服务：把 harness 状态实时显示在通知栏。
 *
 * 这是"实时把 harness 状态显示到手机上"在不开着 App 时的形态。
 * 走的是同一条 `/phone/events` SSE，不额外增加 PC 侧负担。
 */
class StatusService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile
    private var call: Call? = null

    @Volatile
    private var last: HarnessState = HarnessState.EMPTY

    /** 未读回复条数 —— 通知栏的「提醒」就靠它。 */
    @Volatile
    private var unreadCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(application)
        startForegroundCompat(buildNotification(HarnessState.EMPTY, "正在连接 harness…"))
        loop = scope.launch { runStream() }
        // 未读要跨全部工作区统计，状态流里只有最近 60 条会话，所以单独拉一次
        scope.launch { pollUnread() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun runStream() {
        val settings = Settings(this)
        var attempt = 0
        while (true) {
            val client = resolve(settings)
            val finished = CompletableDeferred<Throwable?>()
            call = client.openSse(
                path = "/phone/events",
                onEvent = { event, data ->
                    if (event == "hello" || event == "state") {
                        runCatching { parseState(data) }.onSuccess { s ->
                            last = s
                            attempt = 0
                            notify(buildNotification(s, null))
                        }
                    }
                },
                onClosed = { err -> finished.complete(err) },
            )
            val err = finished.await()
            attempt += 1
            notify(buildNotification(last, err?.message ?: "连接已断开，重连中"))
            delay((500L shl (attempt.coerceAtMost(6) - 1)).coerceAtMost(8000L))
        }
    }

    /** 和 App 一样：按顺序挑第一个可达的端点，于是切网后通知栏也能自己恢复。 */
    private fun resolve(settings: Settings): BridgeClient {
        val eps = settings.endpointList()
        for (ep in eps) {
            if (BridgeClient.probe(ep, settings.token)) return BridgeClient(ep, settings.token)
        }
        return BridgeClient(eps.first(), settings.token)
    }

    /**
     * 每分钟算一次未读。
     *
     * 已读 watermark 存在手机侧（Settings），和 App 内共用同一套，
     * 所以在 App 里点开过的会话，通知栏这边也不会再算它未读。
     */
    private suspend fun pollUnread() {
        val settings = Settings(this)
        val windowMs = 7L * 24 * 3600 * 1000
        while (true) {
            runCatching {
                val ws = resolve(settings).workspaces()
                val now = System.currentTimeMillis()
                unreadCount = ws.sumOf { w ->
                    // 还在跑的会话不算未读：一轮里助手可能已经发过好几条中间消息，
                    // 但那不是最终回复。跑完了才算。（和 App 内三个板块同一口径。）
                    w.sessions.count { sess ->
                        !sess.running && isUnread(sess.activity, settings.seenReplySeq(sess.id), now, windowMs)
                    }
                }
                notify(buildNotification(last, null))
            }
            delay(60_000L)
        }
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun notify(n: Notification) {
        val mgr = getSystemService(android.app.NotificationManager::class.java) ?: return
        runCatching { mgr.notify(NOTI_ID, n) }
    }

    private fun buildNotification(s: HarnessState, error: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title: String
        val text: String
        if (error != null) {
            title = "DSH · 未连接"
            text = error
        } else if (!s.ok && s.ts == 0L) {
            title = "DSH · 连接中"
            text = Settings(this).endpointList().first()
        } else {
            val running = s.sessions.filter { it.running }
            title = "DSH · " + (s.model?.model ?: "已连接")
            val parts = ArrayList<String>()
            parts.add("${s.totalSessions} 个会话")
            if (running.isNotEmpty()) parts.add("${running.size} 个运行中")
            val jobs = s.activeJobs
            if (jobs.isNotEmpty()) parts.add("${jobs.size} 个任务运行中")
            val goal = running.firstOrNull()?.goal
            if (goal != null) {
                parts.add("目标 ${goal.roundsStarted}" + (goal.maxGoalRounds?.let { "/$it" } ?: "") + " 轮")
            }
            if (unreadCount > 0) parts.add("${unreadCount} 条未读回复")
            text = parts.joinToString(" · ")
        }

        val style = NotificationCompat.BigTextStyle()
        val detail = StringBuilder(text)
        if (unreadCount > 0) {
            detail.append('\n').append("● 有 ").append(unreadCount).append(" 条未读回复，打开 App 看「会话」")
        }
        s.activeJobs.take(5).forEach { j ->
            detail.append('\n').append("▸ ").append(j.label.ifBlank { j.id })
        }
        style.bigText(detail.toString())

        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        call?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTI_ID = 4711

        fun start(ctx: Context) {
            val i = Intent(ctx, StatusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, StatusService::class.java))
        }
    }
}
