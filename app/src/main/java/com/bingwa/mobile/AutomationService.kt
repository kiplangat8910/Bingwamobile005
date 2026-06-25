package com.bingwa.mobile

import android.app.AlarmManager
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
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Calendar

class AutomationService : Service() {

    companion object {
        private const val TAG = "AutomationService"
        const val ACTION_RETRY_PENDING = "com.bingwa.mobile.ACTION_RETRY_PENDING"
        private const val ACTION_RETRY_MAINTENANCE = "com.bingwa.mobile.ACTION_RETRY_MAINTENANCE"
        private const val CHANNEL_ID = "automation_service"
        private const val NOTIFICATION_ID = 2014
    }

    private data class AutomationRequest(
        val code: String,
        val phoneNumber: String,
        val txId: Int,
        val mode: String,
        val offerId: Int,
        val offerName: String,
        val signatureEnabled: Boolean,
        val signatureMode: String,
        val signatureLearning: Boolean
    )

    private lateinit var patternManager: UssdResponsePatternManager

    override fun onCreate() {
        super.onCreate()
        patternManager = UssdResponsePatternManager(this)
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = buildRequest(intent) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RETRY_PENDING || intent?.action == ACTION_RETRY_MAINTENANCE) {
            Log.d(TAG, "Retry-pending alarm txId=${request.txId}")
        } else {
            Log.d(TAG, "onStartCommand mode=${request.mode} code=${request.code} txId=${request.txId}")
        }
        when (request.mode.uppercase()) {
            "ADVANCED" -> startAdvanced(request)
            else -> handleSimple(request)
        }
        return START_NOT_STICKY
    }

    private fun buildRequest(intent: Intent?): AutomationRequest? {
        val safeIntent = intent ?: return null
        val code = safeIntent.getStringExtra("code") ?: return null
        val rawPhoneNumber = safeIntent.getStringExtra("phoneNumber") ?: ""
        val ussdPhoneNumber = rawPhoneNumber.takeIf { it.isBlank() }
            ?: UssdHelper.normalizeRecipientForUssdInput(rawPhoneNumber)
        return AutomationRequest(
            code = code,
            phoneNumber = ussdPhoneNumber,
            txId = safeIntent.getIntExtra("txId", -1),
            mode = safeIntent.getStringExtra("mode") ?: "SIMPLE",
            offerId = safeIntent.getIntExtra("offerId", -1),
            offerName = safeIntent.getStringExtra("offerName") ?: "",
            signatureEnabled = safeIntent.getBooleanExtra("signatureEnabled", false),
            signatureMode = (safeIntent.getStringExtra("signatureMode") ?: "STOP").uppercase(),
            signatureLearning = safeIntent.getBooleanExtra("signatureLearning", false)
        )
    }

    private fun handleSimple(request: AutomationRequest) {
        val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val subId = prefs.getInt("selected_sim_id", -1)
        val baseTm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (baseTm == null) {
            processResponse(request, "Telephony unavailable on this phone", forcedStatus = "Failed")
            return
        }
        val tm = if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            baseTm.createForSubscriptionId(subId)
        } else {
            baseTm
        }
        val started = SilentUssd.execute(
            telephonyManager = tm,
            ussdCode = request.code,
            onSuccess = { response ->
                Log.d(TAG, "SIMPLE success txId=${request.txId} response='$response'")
                processResponse(request, response)
            },
            onFailure = { error ->
                Log.e(TAG, "SIMPLE failed txId=${request.txId} error='$error'")
                processResponse(request, error)
            }
        )
        if (!started) {
            processResponse(request, "Silent USSD is not supported on this phone", forcedStatus = "Failed")
        }
    }

    private fun startAdvanced(request: AutomationRequest) {
        val clean = request.code.trim().replace("%23", "#").trimEnd('#')
        val parts = clean.split("*").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            processResponse(request, "Invalid USSD code", forcedStatus = "Failed")
            return
        }
        val dialCode = "*${parts[0]}#"
        val steps = (1 until parts.size).map {
            if (parts[it].equals("pn", true)) "INPUT_PHONE" else parts[it]
        }
        val offer = request.offerId.takeIf { it >= 0 }?.let { OfferRepository.findById(this, it) }
        Log.d(TAG, "startAdvanced: dial=$dialCode steps=$steps txId=${request.txId}")

        UssdNavigationService.onDispatchComplete = { result ->
            Log.d(TAG, "ADVANCED COMPLETE txId=${request.txId} response='${result.finalResponse}'")
            handleAdvancedResult(request, result)
            UssdNavigationService.onDispatchComplete = null
        }

        // Arm the accessibility session before dialing so fast USSD popups are not missed.
        UssdNavigationService.advancedSteps = steps
        UssdNavigationService.advancedPhoneNumber = request.phoneNumber
        UssdNavigationService.advancedDialCode = dialCode
        UssdNavigationService.retryCount = 0
        UssdNavigationService.advancedActive = true
        UssdNavigationService.advancedInProgress = true
        UssdNavigationService.currentStep = 0
        UssdNavigationService.advancedOfferId = request.offerId
        UssdNavigationService.advancedOfferName = offer?.name ?: request.offerName
        UssdNavigationService.signatureGuardEnabled = request.signatureEnabled && !request.signatureLearning
        UssdNavigationService.signatureAction = request.signatureMode
        UssdNavigationService.signatureLearningMode = request.signatureLearning
        UssdNavigationService.loadedSignatureSteps = offer?.learnedSignature ?: emptyList()
        UssdNavigationService.resetSignatureTracking()

        try {
            val callIntent = UssdHelper.buildCallIntent(this, dialCode)
            if (callIntent.resolveActivity(packageManager) != null) {
                startActivity(callIntent)
            } else {
                UssdNavigationService.advancedSteps = emptyList()
                UssdNavigationService.advancedActive = false
                UssdNavigationService.advancedInProgress = false
                UssdNavigationService.onDispatchComplete = null
                processResponse(request, "No dialer available", forcedStatus = "Failed")
                return
            }
        } catch (e: Exception) {
            UssdNavigationService.advancedSteps = emptyList()
            UssdNavigationService.advancedActive = false
            UssdNavigationService.advancedInProgress = false
            UssdNavigationService.onDispatchComplete = null
            processResponse(request, "Dial error: ${e.message}", forcedStatus = "Failed")
            return
        }
    }

    private fun handleAdvancedResult(request: AutomationRequest, result: AdvancedDispatchResult) {
        if (request.signatureLearning) {
            handleSignatureLearningResult(request, result)
            return
        }

        if (result.changeDetected) {
            val title = if (result.autoAdjusted) "USSD Code Auto-Adjusted" else "USSD Code Change Detected"
            val message = buildSignatureSummary(request, result)
            OfferNotifications.notify(this, title, message)
        }

        val finalResponse = mergeSignatureResponse(request, result)
        val forcedStatus = if (result.changeDetected && !result.autoAdjusted) "Failed" else null
        processResponse(request, finalResponse, forcedStatus, buildPopupTranscript(result))
    }

    private fun handleSignatureLearningResult(request: AutomationRequest, result: AdvancedDispatchResult) {
        val offerLabel = request.offerName.ifBlank { "offer #${request.offerId}" }
        if (request.offerId < 0) {
            OfferNotifications.notify(
                this,
                "USSD Signature Learning",
                "USSD signature learning finished, but the offer could not be identified for saving."
            )
            stopSelf()
            return
        }
        if (result.learnedSignature.isEmpty() && result.learningCaptures.isEmpty()) {
            OfferNotifications.notify(
                this,
                "USSD Signature Learning",
                "The system could not learn a signature for $offerLabel. Open the offer and run Save & Learn again while the USSD menu is available."
            )
            stopSelf()
            return
        }
        val updated = OfferRepository.stageSignatureReview(
            this,
            request.offerId,
            result.learnedSignature,
            result.learningCaptures
        )
        val learnedLabel = updated?.name ?: offerLabel
        val captureSummary = when {
            result.learningCaptures.isEmpty() -> ""
            else -> " Captured ${result.learningCaptures.size} USSD popup(s), the selected option, and the recorded text for each step."
        }
        val finalPopup = result.learningCaptures.lastOrNull()?.popupText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
            ?.takeIf { it.isNotBlank() }
            ?.let { " Last popup: $it" }
            .orEmpty()
        val learnedSummary = when {
            result.learnedSignature.isNotEmpty() ->
                "The system learned ${result.learnedSignature.size} USSD menu step(s) for $learnedLabel using test number 0700000000."
            else ->
                "The system recorded the USSD learning transcript for $learnedLabel using test number 0700000000."
        }
        OfferNotifications.notify(
            this,
            "USSD Signature Ready For Approval",
            "$learnedSummary$captureSummary$finalPopup Review the learned steps in Bingwa Mobile, then approve or relearn before this signature replaces the saved one."
        )
        sendBroadcast(
            Intent("com.bingwa.mobile.OFFER_SIGNATURE_LEARNED")
                .setPackage(packageName)
                .putExtra("offerId", request.offerId)
        )
        stopSelf()
    }

    private fun mergeSignatureResponse(request: AutomationRequest, result: AdvancedDispatchResult): String {
        if (!result.changeDetected) return result.finalResponse
        val prefix = buildSignatureSummary(request, result)
        return if (result.finalResponse.isBlank()) prefix else "$prefix\n\nNetwork response:\n${result.finalResponse}"
    }

    private fun buildPopupTranscript(result: AdvancedDispatchResult): String {
        val entries = result.popupTranscript.toMutableList()
        val normalizedFinal = result.finalResponse.replace(Regex("\\s+"), " ").trim()
        if (normalizedFinal.isNotBlank() && entries.lastOrNull() != normalizedFinal) {
            entries += normalizedFinal
        }
        return entries.mapIndexed { index, text -> "${index + 1}. $text" }.joinToString("\n\n")
    }

    private fun buildSignatureSummary(request: AutomationRequest, result: AdvancedDispatchResult): String {
        val offerLabel = request.offerName.ifBlank { "this offer" }
        val suggestion = result.suggestedCode.takeIf { it.isNotBlank() }?.let {
            " Suggested updated code: $it."
        }.orEmpty()
        return if (result.autoAdjusted) {
            "The system detected a change in the USSD menu for $offerLabel and automatically matched the correct option.${if (result.changeSummary.isNotBlank()) " ${result.changeSummary}." else ""} Open the offer, update the saved USSD code, then run Save & Learn again to relearn the signature for future dispatches.$suggestion"
        } else {
            "The system detected changes in the USSD menu for $offerLabel and stopped the dispatch to avoid selecting the wrong bundle.${if (result.changeSummary.isNotBlank()) " ${result.changeSummary}." else ""} Review and update the offer, then run Save & Learn again to relearn the signature before dispatching again.$suggestion"
        }
    }

    private fun processResponse(
        request: AutomationRequest,
        response: String,
        forcedStatus: String? = null,
        transcript: String? = null
    ) {
        Log.d(TAG, "processResponse txId=${request.txId} mode=${request.mode} response='${response.take(150)}'")

        // Handle token purchase callback
        UssdNavigationService.tokenPurchaseCallback?.let { cb ->
            val success = patternManager.matchesSuccessPattern(response) ||
                    response.contains("you have transferred", ignoreCase = true) ||
                    response.contains("transferred successfully", ignoreCase = true)
            Log.d(TAG, "Token purchase callback: success=$success")
            cb(success)
            UssdNavigationService.tokenPurchaseCallback = null
            stopSelf()
            return
        }

        // Handle balance callback
        UssdNavigationService.balanceCallback?.let { cb ->
            Log.d(TAG, "Balance callback invoked")
            cb(response)
            stopSelf()
            return
        }

        // Save transaction with response
        if (request.txId < 0) {
            stopSelf()
            return
        }

        val status = forcedStatus ?: patternManager.determineResponseStatus(response)
        Log.d(TAG, "Status='$status' for txId=${request.txId}")

        // Save to storage
        saveTransactionResponse(request.txId, status, response, transcript)
        // Broadcast update to UI
        sendBroadcastUpdate(request.txId, status, response)

        // Admin alerts for failed transactions
        if (status == "Failed") {
            MpesaReceiver.checkAndSendAlerts(this, "Failed", response.take(100))
        }

        when (status) {
            "Pending" -> handleDailyLimitPending(request, response)
            "UnderMaintenance" -> {
                val retries = getMaintenanceRetryCount(request.txId)
                if (retries < patternManager.getMaxMaintenanceRetries()) {
                    val nextRetryCount = retries + 1
                    val scheduled = scheduleMaintenanceRetry(request, nextRetryCount)
                    if (!scheduled) {
                        Log.w(TAG, "Maintenance retry scheduling failed for txId=${request.txId}")
                    } else {
                        incrementMaintenanceRetryCount(request.txId)
                    }
                } else {
                    clearMaintenanceRetryCount(request.txId)
                }
            }
            else -> clearMaintenanceRetryCount(request.txId)
        }
        stopSelf()
    }

    private fun handleDailyLimitPending(request: AutomationRequest, response: String) {
        val originalTx = loadTransactionById(this, request.txId)
        val originalOffer = request.offerId.takeIf { it >= 0 }?.let { OfferRepository.findById(this, it) }
        val originalPrice = originalOffer?.price ?: originalTx?.amountValue?.toInt() ?: 0
        val fallbackOffers = DailyLimitPolicy.resolveFallbackOffers(
            context = this,
            originalOfferId = request.offerId,
            originalPrice = originalPrice
        )

        fallbackOffers.forEachIndexed { index, fallbackOffer ->
            val fallbackStarted = startFallbackDispatch(request, fallbackOffer, originalTx)
            if (fallbackStarted) {
                val note = buildString {
                    append("Original offer stopped because of the daily limit. Fallback offer started: ${fallbackOffer.name}.")
                    if (index > 0) {
                        append(" It was selected after earlier fallback plan(s) could not be started.")
                    }
                }
                val message = "$response\n\n$note"
                saveTransactionResponse(request.txId, "Cancelled", message)
                sendBroadcastUpdate(request.txId, "Cancelled", message)
                OfferNotifications.notify(
                    this,
                    "Fallback Dispatched",
                    "${request.offerName.ifBlank { "Original offer" }} hit the daily limit. ${fallbackOffer.name} was started for ${request.phoneNumber}."
                )
                return
            }
        }

        val config = DailyLimitPolicy.load(this)
        if (config.mode == DailyLimitPolicy.MODE_NOTICE_ONLY) {
            val note = if (config.repeatNoticeEnabled) {
                "Reply 1 to send another number today, or reply 2 to confirm tomorrow morning dispatch."
            } else {
                "Waiting for an alternative number or a manual retry tomorrow."
            }
            val message = "$response\n\n$note"
            saveTransactionResponse(request.txId, "Pending", message)
            sendBroadcastUpdate(request.txId, "Pending", message)
            if (config.repeatNoticeEnabled) {
                originalTx?.phoneNumber?.takeIf { it.isNotBlank() }?.let { customerPhone ->
                    DailyLimitPolicy.beginReplyMenu(this, customerPhone, request.txId)
                }
            }
            sendCustomerOutcomeSms(this, "limit_notice", originalTx)
            OfferNotifications.notify(
                this,
                "Daily Limit Notice",
                "${request.phoneNumber} has already received today's offer. A notice was sent instead of auto-queueing."
            )
        } else {
            sendCustomerOutcomeSms(this, "pending", originalTx)
            scheduleRetryTomorrow(request)
        }
    }

    private fun startFallbackDispatch(
        request: AutomationRequest,
        fallbackOffer: OfferItem,
        originalTx: Transaction?
    ): Boolean {
        return if (RelayManager.isPrimary(this) && fallbackOffer.targetDevice.uppercase() == "RELAY") {
            RelayManager.forwardBuyAmount(this, request.phoneNumber, fallbackOffer.price)
        } else {
            val finalCode = UssdHelper.normalizeUssdCode(fallbackOffer.ussdCode, request.phoneNumber)
            if (finalCode.isBlank()) return false
            val fallbackTxId = createPendingTransaction(
                this,
                fallbackOffer.name,
                "KSh ${fallbackOffer.price}",
                request.phoneNumber,
                finalCode,
                originalTx?.clientName.orEmpty(),
                status = if (originalTx?.showInRecent == true) TransactionStatus.PROCESSING.value else TransactionStatus.PENDING.value,
                source = originalTx?.source ?: TX_SOURCE_AUTOMATED,
                showInRecent = originalTx?.showInRecent ?: true,
                offerId = fallbackOffer.id
            )
            if (fallbackTxId < 0) return false
            startOfferAutomation(
                offer = fallbackOffer,
                phoneNumber = request.phoneNumber,
                txId = fallbackTxId,
                finalCode = finalCode,
                mode = fallbackOffer.executionMode
            )
            true
        }
    }

    private fun saveTransactionResponse(txId: Int, status: String, response: String, transcript: String? = null) {
        val saved = saveTransactionOutcome(this, txId, status, response, transcript)
        if (saved) {
            Log.d(TAG, "SAVED txId=$txId status=$status response='${response.take(80)}'")
        } else {
            Log.w(TAG, "Transaction $txId not found in list")
        }
    }

    private fun getMaintenanceRetryCount(txId: Int): Int =
        getSharedPreferences("maint_retries", Context.MODE_PRIVATE).getInt("tx_$txId", 0)

    private fun incrementMaintenanceRetryCount(txId: Int) {
        getSharedPreferences("maint_retries", Context.MODE_PRIVATE)
            .edit().putInt("tx_$txId", getMaintenanceRetryCount(txId) + 1).apply()
    }

    private fun clearMaintenanceRetryCount(txId: Int) {
        if (txId < 0) return
        getSharedPreferences("maint_retries", Context.MODE_PRIVATE)
            .edit()
            .remove("tx_$txId")
            .apply()
    }

    private fun buildAutomationIntent(context: Context, request: AutomationRequest, action: String? = null): Intent =
        Intent(context, AutomationService::class.java).apply {
            this.action = action
            putExtra("mode", request.mode)
            putExtra("code", request.code)
            putExtra("phoneNumber", request.phoneNumber)
            putExtra("txId", request.txId)
            putExtra("offerId", request.offerId)
            putExtra("offerName", request.offerName)
            putExtra("signatureEnabled", request.signatureEnabled)
            putExtra("signatureMode", request.signatureMode)
            putExtra("signatureLearning", request.signatureLearning)
        }

    private fun scheduleMaintenanceRetry(request: AutomationRequest, nextRetryCount: Int): Boolean {
        return runCatching {
            val retryDelayMs = patternManager.getMaintenanceRetryDelayMs().coerceAtLeast(5_000L)
            val triggerAt = System.currentTimeMillis() + retryDelayMs
            val intent = buildAutomationIntent(this, request, ACTION_RETRY_MAINTENANCE)
            val pi = PendingIntent.getService(
                this,
                request.txId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val scheduled = AlarmCompat.scheduleRtcWakeup(
                context = this,
                triggerAtMillis = triggerAt,
                pendingIntent = pi,
                preferExact = false,
                allowWhileIdle = true
            )
            if (scheduled) {
                Log.d(
                    TAG,
                    "Scheduled maintenance retry #$nextRetryCount for txId=${request.txId} in ${retryDelayMs}ms"
                )
            }
            scheduled
        }.getOrElse { error ->
            Log.e(TAG, "scheduleMaintenanceRetry failed for txId=${request.txId}", error)
            false
        }
    }

    private fun scheduleRetryTomorrow(request: AutomationRequest) {
        try {
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val intent = buildAutomationIntent(this, request, ACTION_RETRY_PENDING)
            val pi = PendingIntent.getService(
                this, request.txId, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val scheduled = AlarmCompat.scheduleRtcWakeup(
                context = this,
                triggerAtMillis = tomorrow.timeInMillis,
                pendingIntent = pi,
                preferExact = true,
                allowWhileIdle = true
            )
            Log.d(TAG, "Retry scheduled=$scheduled for ${tomorrow.time}")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRetryTomorrow failed", e)
        }
    }

    private fun sendBroadcastUpdate(txId: Int, status: String, response: String) {
        Handler(Looper.getMainLooper()).post {
            sendBroadcast(
                Intent("com.bingwa.mobile.TX_UPDATED")
                    .setPackage(packageName)
                    .putExtra("txId", txId)
                    .putExtra("status", status)
                    .putExtra("response", response)
            )
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "USSD Automation", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bingwa Mobile")
            .setContentText("Running a USSD automation task")
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
