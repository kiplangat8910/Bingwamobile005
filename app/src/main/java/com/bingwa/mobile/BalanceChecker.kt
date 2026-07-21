package com.bingwa.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class BalanceChecker : Service() {

    companion object {
        private const val TAG = "BalanceChecker"
        private const val DEFAULT_BALANCE_USSD = "*144#"
        private const val AIRTEL_BALANCE_USSD = "*131#"
        private const val CHECK_INTERVAL = 5 * 60_000L
        private const val BALANCE_TIMEOUT_MS = 30_000L
        private const val EVENT_REFRESH_DELAY_MS = 2_000L
        private const val FOREGROUND_REFRESH_COOLDOWN_MS = 3_000L
        private const val CHANNEL_ID = "balance_checker"
        private const val NOTIFICATION_ID = 2013

        @Volatile var balanceCallback: ((String) -> Unit)? = null
        private val balanceResultListeners = CopyOnWriteArraySet<(BalanceCheckResult) -> Unit>()
        private val balanceDisplayBySelection = ConcurrentHashMap<Int, String>()
        @Volatile var balanceResultListener: ((BalanceCheckResult) -> Unit)? = null
            set(value) {
                field?.let(::removeBalanceResultListener)
                field = value
                value?.let(::addBalanceResultListener)
            }
        @Volatile var currentBalanceStr: String = ""
        @Volatile var currentBalance: Int = -1
        @Volatile var checking = false
        @Volatile private var lastCheckStartedAt: Long = 0L

        private val timeoutHandler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null
        private var pendingRefreshRunnable: Runnable? = null
        @Volatile private var activeRequestContext: BalanceRequestContext? = null

        data class BalanceCheckResult(
            val display: String,
            val selectionOverride: Int?,
            val persistResult: Boolean
        )

        fun addBalanceResultListener(listener: (BalanceCheckResult) -> Unit) {
            balanceResultListeners += listener
        }

        fun removeBalanceResultListener(listener: (BalanceCheckResult) -> Unit) {
            balanceResultListeners -= listener
        }

        fun notifyBalanceResult(result: BalanceCheckResult) {
            rememberBalanceResult(result)
            balanceResultListeners.forEach { listener ->
                runCatching { listener(result) }
                    .onFailure { Log.w(TAG, "Balance listener failed", it) }
            }
        }

        fun rememberBalanceResult(result: BalanceCheckResult) {
            val display = result.display
            val selection = result.selectionOverride
            if (selection != null) {
                if (display.isBlank()) {
                    balanceDisplayBySelection.remove(selection)
                } else {
                    balanceDisplayBySelection[selection] = display
                }
            }
            if (result.persistResult) {
                currentBalanceStr = display
            }
        }

        fun getBalanceDisplay(selectionOverride: Int? = null): String {
            return if (selectionOverride != null) {
                balanceDisplayBySelection[selectionOverride].orEmpty()
            } else {
                currentBalanceStr
            }
        }

        private data class BalanceRequestContext(
            val selectionOverride: Int? = null,
            val persistResult: Boolean = true
        )

        private data class BalanceCandidate(
            val amount: Double,
            val score: Int
        )

        internal fun resolveBalanceUssdCode(context: Context, selectionOverride: Int? = null): String {
            return if (isAirtelBalanceTarget(context, selectionOverride)) {
                AIRTEL_BALANCE_USSD
            } else {
                DEFAULT_BALANCE_USSD
            }
        }

        fun requestBalanceCheck(
            context: Context,
            selectionOverride: Int? = null,
            persistResult: Boolean = selectionOverride == null,
            ignoreCooldown: Boolean = false
        ): Boolean {
            val appContext = context.applicationContext
            val now = System.currentTimeMillis()
            if (checking) {
                Log.d(TAG, "check already in flight — skipping")
                return false
            }
            if (selectionOverride == null && isUssdWorkBusy(appContext)) {
                Log.d(TAG, "another USSD task is active — skipping balance check")
                return false
            }
            if (!ignoreCooldown && selectionOverride == null && now - lastCheckStartedAt < FOREGROUND_REFRESH_COOLDOWN_MS) {
                Log.d(TAG, "balance check cooldown active — skipping duplicate request")
                return false
            }
            checking = true
            lastCheckStartedAt = now
            val requestContext = BalanceRequestContext(
                selectionOverride = selectionOverride,
                persistResult = persistResult
            )
            activeRequestContext = requestContext
            armTimeout()
            val balanceUssd = resolveBalanceUssdCode(context, selectionOverride)
            Log.d(TAG, "Requesting balance via $balanceUssd (SILENT)")

            UssdNavigationService.balanceCallback = { raw ->
                Log.d(TAG, "balanceCallback raw='$raw'")
                cancelTimeout()
                checking = false
                UssdNavigationService.balanceCallback = null
                val completedRequest = activeRequestContext ?: requestContext
                activeRequestContext = null

                val display = parseBalanceDisplay(raw)
                val intVal = if (display.isNotEmpty()) parseBalanceInt(raw) else -1
                if (intVal >= 0) currentBalance = intVal
                rememberBalanceResult(
                    BalanceCheckResult(
                        display = display,
                        selectionOverride = completedRequest.selectionOverride,
                        persistResult = completedRequest.persistResult
                    )
                )
                if (completedRequest.persistResult && display.isNotEmpty()) {
                    RelayManager.syncPrimaryAirtimeBalance(context, display)
                }

                Handler(Looper.getMainLooper()).post {
                    balanceCallback?.invoke(display)
                    notifyBalanceResult(
                        BalanceCheckResult(
                            display = display,
                            selectionOverride = completedRequest.selectionOverride,
                            persistResult = completedRequest.persistResult
                        )
                    )
                    if (completedRequest.persistResult) {
                        // Trigger admin alerts after balance update
                        MpesaReceiver.checkAndSendAlerts(context)
                    }
                }
            }

            // Try UssdHelper first (silent), fallback to AutomationService SIMPLE
            val helperSuccess = UssdHelper.dialUssd(
                appContext,
                balanceUssd,
                silentOnly = true,
                subIdOverride = selectionOverride,
                onSuccess = { response ->
                    Log.d(TAG, "UssdHelper success: '$response'")
                    UssdNavigationService.balanceCallback?.invoke(response)
                },
                onFailure = { error ->
                    Log.e(TAG, "UssdHelper failed: $error, trying AutomationService")
                    if (!startBalanceFallback(appContext, balanceUssd, selectionOverride)) {
                        Log.w(TAG, "automation fallback skipped while another USSD task is active")
                        onBalanceCheckFailed()
                    }
                }
            )
            if (!helperSuccess) {
                Log.w(TAG, "UssdHelper returned false, using AutomationService")
                if (!startBalanceFallback(appContext, balanceUssd, selectionOverride)) {
                    Log.w(TAG, "balance fallback could not start")
                    onBalanceCheckFailed()
                    return false
                }
            }
            return true
        }

        fun scheduleAirtimeRefresh(
            context: Context,
            reason: String,
            delayMs: Long = EVENT_REFRESH_DELAY_MS
        ): Boolean {
            val appContext = context.applicationContext
            val automationEnabled = appContext
                .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getBoolean("automation_enabled", true)
            if (!automationEnabled) {
                Log.d(TAG, "Skipping scheduled airtime refresh for $reason because automation is disabled")
                return false
            }

            ServiceLauncher.startBalanceChecker(appContext)

            var scheduledRefresh: Runnable? = null
            scheduledRefresh = Runnable {
                if (pendingRefreshRunnable === scheduledRefresh) {
                    pendingRefreshRunnable = null
                }
                val started = requestBalanceCheck(
                    context = appContext,
                    ignoreCooldown = true
                )
                Log.d(TAG, "Scheduled airtime refresh reason=$reason started=$started")
            }
            val refreshRunnable = scheduledRefresh ?: return false

            pendingRefreshRunnable?.let(timeoutHandler::removeCallbacks)
            pendingRefreshRunnable = refreshRunnable
            timeoutHandler.postDelayed(refreshRunnable, delayMs.coerceAtLeast(0L))
            return true
        }

        fun onBalanceCheckFailed() {
            Log.w(TAG, "Balance check failed — resetting")
            cancelTimeout()
            checking = false
            UssdNavigationService.balanceCallback = null
            val failedRequest = activeRequestContext ?: BalanceRequestContext()
            activeRequestContext = null
            Handler(Looper.getMainLooper()).post {
                balanceCallback?.invoke("")
                notifyBalanceResult(
                    BalanceCheckResult(
                        display = "",
                        selectionOverride = failedRequest.selectionOverride,
                        persistResult = failedRequest.persistResult
                    )
                )
            }
        }

        fun parseBalanceDisplay(raw: String): String {
            Log.d(TAG, "parseBalanceDisplay input='$raw'")
            extractBalanceCandidate(raw)?.let { return formatAmount(it.amount) }
            Log.w(TAG, "No balance pattern matched for '$raw'")
            return ""
        }

        fun parseBalanceInt(raw: String): Int {
            extractBalanceCandidate(raw)?.amount?.toInt()?.let { return it }
            return -1
        }

        private fun extractBalanceCandidate(raw: String): BalanceCandidate? {
            val normalized = normalizeBalanceText(raw)
            val candidates = mutableListOf<BalanceCandidate>()

            fun addMatches(pattern: Regex, score: Int) {
                pattern.findAll(normalized).forEach { match ->
                    val amount = match.groupValues.getOrNull(1)
                        ?.replace(",", "")
                        ?.toDoubleOrNull()
                        ?: return@forEach
                    if (amount > 0 && amount < 1_000_000) {
                        candidates += BalanceCandidate(amount = amount, score = score)
                    }
                }
            }

            addMatches(
                Regex(
                    """your\s+balance\s+is\s+ksh[s]?\.\s*([\d,]+(?:\.\d{1,2})?)""",
                    RegexOption.IGNORE_CASE
                ),
                score = 650
            )
            addMatches(
                Regex(
                    """(?:airtime\s*bal(?:ance)?|balance|your\s+balance\s+is|salio|umbea|account\s+balance)[:\s-]*(?:is\s*)?(?:(?:ksh[s]?|kes)\.?\s*)?([\d,]+(?:\.\d{1,2})?)""",
                    RegexOption.IGNORE_CASE
                ),
                score = 500
            )
            addMatches(
                Regex("""(?:ksh[s]?|kes)\.?\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE),
                score = 420
            )
            addMatches(
                Regex("""([\d,]+(?:\.\d{1,2})?)\s*(?:ksh[s]?|kes)\.?""", RegexOption.IGNORE_CASE),
                score = 420
            )
            addMatches(
                Regex("""([\d,]+(?:\.\d{1,2})?)\s*/="""),
                score = 320
            )

            if (candidates.isEmpty() &&
                Regex("""balance|airtime|salio|umbea|tariff|mpesa""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(normalized)
            ) {
                Regex("""([\d,]+(?:\.\d{1,2})?)""")
                    .findAll(normalized)
                    .forEach { match ->
                        val amount = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
                        if (amount > 0 && amount < 1_000_000) {
                            candidates += BalanceCandidate(amount = amount, score = 120)
                        }
                    }
            }

            return candidates.maxWithOrNull(
                compareBy<BalanceCandidate> { it.score }
                    .thenBy { it.amount }
            )
        }

        private fun normalizeBalanceText(raw: String): String =
            raw.replace(Regex("""[_=~`|•]+"""), " ")
                .replace(Regex("""[^\S\r\n]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

        private fun isAirtelBalanceTarget(context: Context, selectionOverride: Int? = null): Boolean {
            val targetSubId = resolvePreferredUssdSubId(context, selectionOverride) ?: return false
            val targetSim = getAvailableSims(context).firstOrNull { it.subscriptionId == targetSubId } ?: return false
            val labels = buildList {
                add(targetSim.displayName?.toString().orEmpty())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(targetSim.carrierName?.toString().orEmpty())
                }
            }
                .joinToString(" ")
                .trim()

            return labels.contains("airtel", ignoreCase = true)
        }

        private fun formatAmount(amount: Double): String {
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

        private fun startBalanceFallback(
            context: Context,
            balanceUssd: String,
            selectionOverride: Int?
        ): Boolean {
            if (isUssdWorkBusy(context)) return false
            return ServiceLauncher.startAutomationService(
                context,
                Intent(context, AutomationService::class.java).apply {
                    putExtra("mode", "SIMPLE")
                    putExtra("code", balanceUssd)
                    putExtra("phoneNumber", "")
                    putExtra("simSelection", selectionOverride ?: OFFER_SIM_USE_GENERAL)
                }
            )
        }

        private fun isUssdWorkBusy(context: Context): Boolean {
            if (UssdNavigationService.isBusyForBalanceCheck()) return true
            return isServiceRunning(context, AutomationService::class.java)
        }

        private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return runCatching {
                @Suppress("DEPRECATION")
                manager.getRunningServices(Int.MAX_VALUE)
                    .any { it.service.className == serviceClass.name }
            }.getOrDefault(false)
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
        handler.post(periodicCheck)
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
