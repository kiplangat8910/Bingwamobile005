package com.bingwa.adminhub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsCommandService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SmsCommandService started")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Command Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SMS command processing active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Bingwa Admin Hub")
        .setContentText("Processing SMS commands...")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setOngoing(true)
        .build()

    companion object {
        const val TAG = "SmsCommandService"
        const val ACTION_SEND_SMS = "com.bingwa.adminhub.action.SEND_SMS"
        const val ACTION_SCHEDULE = "com.bingwa.adminhub.action.SCHEDULE"
        const val CHANNEL_ID = "adminhub_sms_channel"
        const val NOTIFICATION_ID = 1001
    }
}
