package com.bingwa.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class BalanceChecker : Service() {

    companion object {
        private const val TAG = "BalanceChecker"
        private const val BALANCE_USSD = "*144#"
        private const val CHECK_INTERVAL = 5 * 60 * 1000L
        private const val BALANCE_TIMEOUT_MS = 30_000L
        private const val CHANNEL_ID = "balance_checker"
        private const val NOTIFICATION_ID = 2013

        @Volatile var balanceCallback: ((String) -> Unit)? = null
        @Volatile var currentBalanceStr: String = ""
        @Volatile var currentBalance: Int = -1
        @Volatile var checking = false

        private val timeoutHandler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        fun requestBalanceCheck(context: Context) {
            if (checking) { Log.d(TAG, "check already in flight — skipping"); return }
            checking = true
            armTimeout()
            Log.d(TAG, "Requesting balance via $BALANCE_USSD (SILENT)")

            UssdNavigationService.balanceCallback = { raw ->
                Log.d(TAG, "balanceCallback raw='$raw'")
                cancelTimeout()
                checking = false
                UssdNavigationService.balanceCallback = null

                val display = parseBalanceDisplay(raw)
                val intVal = if (display.isNotEmpty()) parseBalanceInt(raw) else -1
                currentBalance = intVal
                currentBalanceStr = display

                Handler(Looper.getMainLooper()).post {
                    balanceCallback?.invoke(display)
                    // Trigger admin alerts after balance update
                    MpesaReceiver.checkAndSendAlerts(context)
                }
            }

            // Try UssdHelper first (silent), fallback to AutomationService SIMPLE
            val helperSuccess = UssdHelper.dialUssd(
                context, BALANCE_USSD, silentOnly = true,
                onSuccess = { response ->
                    Log.d(TAG, "UssdHelper success: '$response'")
                    UssdNavigationService.balanceCallback?.invoke(response)
                },
                onFailure = { error ->
                    Log.e(TAG, "UssdHelper failed: $error, trying AutomationService")
                    ServiceLauncher.startAutomationService(context, Intent(context, AutomationService::class.java).apply {
                        putExtra("mode", "SIMPLE")
                        putExtra("code", BALANCE_USSD)
                        putExtra("phoneNumber", "")
                    })
                }
            )
            if (!helperSuccess) {
                Log.w(TAG, "UssdHelper returned false, using AutomationService")
                ServiceLauncher.startAutomationService(context, Intent(context, AutomationService::class.java).apply {
                    putExtra("mode", "SIMPLE")
                    putExtra("code", BALANCE_USSD)
                    putExtra("phoneNumber", "")
                })
            }
        }

        fun onBalanceCheckFailed() {
            Log.w(TAG, "Balance check failed — resetting")
            cancelTimeout()
            checking = false
            UssdNavigationService.balanceCallback = null
            Handler(Looper.getMainLooper()).post { balanceCallback?.invoke("") }
        }

        fun parseBalanceDisplay(raw: String): String {
            Log.d(TAG, "parseBalanceDisplay input='$raw'")

            // Pattern 1: "Airtime Bal: 726.10KSH" or "Balance: 150KSH"
            val p1 = Regex("""(?:Airtime\s*Bal|Balance|Salio|Umbea)[:\s]*([\d,]+(?:\.\d{1,2})?)\s*KSH""", RegexOption.IGNORE_CASE)
            p1.find(raw)?.groupValues?.get(1)?.let { return formatAmount(it) }

            // Pattern 2: Number followed by KSH anywhere: "726.10KSH"
            val p2 = Regex("""([\d,]+(?:\.\d{1,2})?)\s*KSH""", RegexOption.IGNORE_CASE)
            p2.find(raw)?.groupValues?.get(1)?.let { return formatAmount(it) }

            // Pattern 3: KSh/KES with space before amount
            val p3 = Regex("""(?:KSh[s]?|Ksh[s]?|KES)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
            p3.find(raw)?.groupValues?.get(1)?.let { return formatAmount(it) }

            // Pattern 4: Amount before KSh/KES
            val p4 = Regex("""([\d,]+(?:\.\d{1,2})?)\s*(?:KSh[s]?|Ksh[s]?|KES)""", RegexOption.IGNORE_CASE)
            p4.find(raw)?.groupValues?.get(1)?.let { return formatAmount(it) }

            // Pattern 5: Number with /=
            val p5 = Regex("""([\d,]+(?:\.\d{1,2})?)\s*/=""")
            p5.find(raw)?.groupValues?.get(1)?.let { return formatAmount(it) }

            // Pattern 6: Any decimal number near balance keywords
            if (Regex("""balance|airtime|salio|umbea|tariff|mpesa""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
                val numbers = Regex("""([\d,]+(?:\.\d{1,2})?)""").findAll(raw).toList()
                for (m in numbers) {
                    val amount = m.groupValues[1].replace(",", "").toDoubleOrNull()
                    if (amount != null && amount > 0 && amount < 1000000) {
                        return formatAmount(m.groupValues[1])
                    }
                }
            }

            Log.w(TAG, "No balance pattern matched for '$raw'")
            return ""
        }

        fun parseBalanceInt(raw: String): Int {
            val p1 = Regex("""([\d,]+)(?:\.\d+)?\s*KSH""", RegexOption.IGNORE_CASE)
            p1.find(raw)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { return it }
            val p2 = Regex("""(?:KSh[s]?|Ksh[s]?|KES)\s*([\d,]+)""", RegexOption.IGNORE_CASE)
            p2.find(raw)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { return it }
            val p3 = Regex("""([\d,]+)\s*/=""")
            p3.find(raw)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { return it }
            return -1
        }

        private fun formatAmount(rawAmount: String): String {
            val cleaned = rawAmount.replace(",", "")
            val amount = cleaned.toDoubleOrNull() ?: return ""
            return if (amount == kotlin.math.floor(amount)) {
                "KSh ${amount.toInt()}"
            } else {
                "KSh ${"%.2f".format(amount)}"
            }
        }

        private fun armTimeout() {
            cancelTimeout()
            val timeout = Runnable { onBalanceCheckFailed() }
            timeoutRunnable = timeout
            timeoutHandler.postDelayed(timeout, BALANCE_TIMEOUT_MS)
        }

        private fun cancelTimeout() {
            timeoutRunnable?.let { timeoutHandler.removeCallbacks(it) }
            timeoutRunnable = null
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val periodicCheck = object : Runnable {
        override fun run() {
            val prefs = applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("automation_enabled", true)) {
                handler.removeCallbacks(this)
                stopSelf()
                return
            }
            requestBalanceCheck(applicationContext)
            // Also check battery alerts (offline)
            MpesaReceiver.checkAndSendAlerts(applicationContext)
            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val automationEnabled = applicationContext
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("automation_enabled", true)
        if (!automationEnabled) {
            handler.removeCallbacks(periodicCheck)
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(periodicCheck)
        handler.postDelayed(periodicCheck, 2_000)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(periodicCheck)
        checking = false
        stopForegroundCompat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Balance Monitoring", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bingwa Mobile")
            .setContentText("Monitoring airtime balance in the background")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setOngoing(true)
            .build()

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }
}
