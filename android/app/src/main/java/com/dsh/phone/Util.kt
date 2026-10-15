package com.dsh.phone

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dayTime = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

fun formatClock(ms: Long): String = if (ms <= 0L) "" else clock.format(Date(ms))

fun formatDayTime(ms: Long): String = if (ms <= 0L) "" else dayTime.format(Date(ms))

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 日期。 */
fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "—"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> formatDayTime(ms)
    }
}

/** 运行时长：1h02m / 5m30s / 42s。 */
fun formatDuration(startMs: Long, endMs: Long? = null): String {
    if (startMs <= 0L) return "—"
    val end = endMs ?: System.currentTimeMillis()
    val sec = ((end - startMs) / 1000L).coerceAtLeast(0L)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h}h${m.toString().padStart(2, '0')}m"
        m > 0 -> "${m}m${s.toString().padStart(2, '0')}s"
        else -> "${s}s"
    }
}

/** 路径缩写：只留最后两级目录，避免窄屏被长路径挤爆。 */
fun shortPath(path: String?): String {
    if (path.isNullOrBlank()) return ""
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) parts.joinToString("/") else parts.takeLast(2).joinToString("/")
}
