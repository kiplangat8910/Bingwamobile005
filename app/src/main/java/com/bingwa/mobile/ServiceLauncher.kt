package com.bingwa.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object ServiceLauncher {
    private const val TAG = "ServiceLauncher"
    private const val FALLBACK_START_DELAY_MS = 250L

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
            scheduleStartFallback(context, intent, label)
            OfferNotifications.notify(
                context,
                "Phone blocked background start",
                "$label could not start in the background. Open Bingwa Mobile and remove battery restrictions on this phone."
            )
            false
        }
    }

    private fun scheduleStartFallback(context: Context, intent: Intent, label: String) {
        runCatching {
            val safeContext = context.applicationContext
            val component = intent.component ?: return@runCatching
            val requestCode = resolveFallbackRequestCode(intent, label)
            val fallbackIntent = Intent(intent).setComponent(component)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    safeContext,
                    requestCode,
                    fallbackIntent,
                    flags
                )
            } else {
                PendingIntent.getService(
                    safeContext,
                    requestCode,
                    fallbackIntent,
                    flags
                )
            }
            AlarmCompat.scheduleRtcWakeup(
                context = safeContext,
                triggerAtMillis = System.currentTimeMillis() + FALLBACK_START_DELAY_MS,
                pendingIntent = pi,
                preferExact = false,
                allowWhileIdle = true
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to schedule fallback start for $label", error)
        }
    }

    private fun resolveFallbackRequestCode(intent: Intent, label: String): Int {
        val txId = intent.getIntExtra("txId", -1)
        if (txId >= 0) return txId
        return (label.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
