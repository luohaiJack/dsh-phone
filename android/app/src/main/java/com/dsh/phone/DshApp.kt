package com.dsh.phone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class DshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }
}

const val CHANNEL_STATUS = "dsh-status"

fun ensureChannel(app: Application) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mgr = app.getSystemService(NotificationManager::class.java) ?: return
    if (mgr.getNotificationChannel(CHANNEL_STATUS) != null) return
    val channel = NotificationChannel(
        CHANNEL_STATUS,
        "Harness 状态",
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = "常驻显示本地 DSH harness 的实时状态"
        setShowBadge(false)
    }
    mgr.createNotificationChannel(channel)
}
