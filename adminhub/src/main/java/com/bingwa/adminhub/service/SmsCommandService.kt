package com.bingwa.adminhub.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class SmsCommandService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SmsCommandService started")
        return START_STICKY
    }

    companion object {
        const val TAG = "SmsCommandService"
        const val ACTION_SEND_SMS = "com.bingwa.adminhub.action.SEND_SMS"
        const val ACTION_SCHEDULE = "com.bingwa.adminhub.action.SCHEDULE"
    }
}
