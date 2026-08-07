package com.bingwa.adminhub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Boot completed, starting SmsCommandService")
            val serviceIntent = Intent(context, SmsCommandService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(serviceIntent)
            }
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}
