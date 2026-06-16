package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

object ServiceLauncher {
    private const val TAG = "ServiceLauncher"

    fun startBalanceChecker(context: Context): Boolean {
        return startForegroundServiceSafely(
            context = context,
            intent = Intent(context, BalanceChecker::class.java),
            label = "Background balance monitoring"
        )
    }

    fun startAutomationService(context: Context, intent: Intent): Boolean {
        return startForegroundServiceSafely(
            context = context,
            intent = intent,
            label = "USSD automation"
        )
    }

    fun startForegroundServiceSafely(context: Context, intent: Intent, label: String): Boolean {
        return runCatching {
            ContextCompat.startForegroundService(context, intent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Unable to start $label", error)
            OfferNotifications.notify(
                context,
                "Phone blocked background start",
                "$label could not start in the background. Open Bingwa Mobile and remove battery restrictions on this phone."
            )
            false
        }
    }
}
