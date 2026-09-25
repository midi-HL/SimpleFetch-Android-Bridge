package com.deepseekhn.wbtest.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.deepseekhn.wbtest.R

/**
 * 前台服务：保活 MessageApi 监听，使手机在后台时手环快应用仍可联网。
 */
class BridgeService : Service() {

    private val channelId = "simplefetch_bridge"
    private val notifId = 1001
    lateinit var bridge: WearableBridge
        private set

    inner class LocalBinder : Binder() {
        fun service(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(notifId, buildNotification())
        bridge = WearableBridge(this)
        bridge.refreshNodes()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        bridge.destroy()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "SimpleFetch 桥接",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "保持与手环的互联通道" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, channelId)
            .setContentTitle("SimpleFetch")
            .setContentText("桥接运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, BridgeService::class.java)
            ctx.startForegroundService(i)
        }
    }
}
