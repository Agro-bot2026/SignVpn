package com.ghostvpn

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class TunnelService : Service() {
    companion object {
        const val CHANNEL_ID = "ghostvpn_tunnel"
        const val NOTIFY_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFY_ID, buildNotification("GhostVpn activo"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "GhostVpn", NotificationManager.IMPORTANCE_LOW)
        channel.description = "T\u00fanel GhostVpn"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GhostVpn")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}
