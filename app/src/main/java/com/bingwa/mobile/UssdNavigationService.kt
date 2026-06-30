package com.bingwa.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
class UssdNavigationService : AccessibilityService() {

    companion object {
        const val TAG = "UssdNavigation"

        var airtimeBalance          = "N/A"
        var balanceCallback         : ((String) -> Unit)?  = null
        var tokenPurchaseCallback   : ((Boolean) -> Unit)? = null

        var advancedSteps           : List<String> = emptyList()
        var advancedPhoneNumber     : String       = ""
        var advancedDialCode        : String       = ""
        var advancedOfferId         : Int          = -1
        var advancedOfferName       : String       = ""
        var advancedActive          : Boolean      = false
        var advancedInProgress      : Boolean      = false
        var currentStep             : Int          = 0
        var retryCount              : Int          = 0
        var lastRedialElapsed       : Long         = 0L
        var signatureGuardEnabled   : Boolean      = false
        var signatureAction         : String       = "STOP"
        var signatureLearningMode   : Boolean      = false
        var loadedSignatureSteps    : List<UssdSignatureStep> = emptyList()

        var onDispatchComplete      : ((result: AdvancedDispatchResult) -> Unit)? = null

        private const val MAX_RETRIES            = 5
        private const val STEP_DELAY_MS          = 90L
        private const val EVENT_HOT_POLL_MS      = 16L
        private const val FAST_VERIFY_POLL_MS    = 24L
        private const val HOT_SEND_RETRY_DELAY_MS = 28L
        private const val SEND_RETRY_DELAY_MS    = 45L
        private const val STEP_TIMEOUT_MS        = 25_000L
        private const val VERIFY_POLL_MS         = 40L
        private const val RAPID_POST_POPUP_POLL_MS = 20L
        private const val RAPID_POST_POPUP_VERIFY_MS = 18L
        private const val RAPID_POST_POPUP_SEND_RETRY_MS = 24L
        private const val MAX_VERIFY_ATTEMPTS    = 24
        private const val MAX_SEND_ATTEMPTS      = 10
        private const val NO_FIELD_PATIENCE      = 4
        private const val INPUT_TARGET_DEPTH     = 8
        private const val RECENT_INPUT_GRACE_MS  = 4_000L
        private const val RECENT_VERIFIED_INPUT_GRACE_MS = 4_500L
        private const val RECENT_UI_EVENT_GRACE_MS = 1_200L
        private const val GESTURE_SETTLE_MS      = 12L
        private const val POST_GESTURE_WAIT_MS   = 18L
        private const val POPUP_STABILITY_DELAY_MS = 20L
        private const val TAP_GESTURE_DURATION_MS = 40L
        private const val REDIAL_COOLDOWN_MS     = 700L
        private const val PENDING_ADVANCE_TIMEOUT_MS = 4_000L
        private const val PENDING_STEP_ADVANCE_TIMEOUT_MS = 3_500L
        private const val DIALOG_DISMISS_SETTLE_MS = 240L
        // Some devices emit extra events on the same USSD dialog after we click "Send".
        // If we process those events immediately, we can inject the NEXT step into the PREVIOUS screen.
        private const val STEP_TRANSITION_GUARD_MS = 650L
        private const val CHANNEL_ID             = "bingwa_ussd"
        private const val NOTIFICATION_ID        = 2001
        private val MULTI_SPACE_REGEX = Regex("\\s+")
        private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
        private val MENU_OPTION_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?\s*(.+)$""")
        private val MENU_OPTION_NUMBER_ONLY_REGEX = Regex("""^(\d+)\s*[\)\].:\-]?$""")

        private val USSD_PACKAGES = setOf(
            "com.android.phone", "com.android.server.telecom", "com.google.android.dialer",
            "com.samsung.android.incallui", "com.samsung.android.app.telephonyui",
            "com.samsung.android.dialer", "com.android.incallui", "com.android.dialer",
            "com.mediatek.phone", "com.transsion.phone", "com.infinix.phone",
            "com.tecno.phone", "com.itel.phone", "com.transsion.incallui",
            "com.mediatek.incallui", "com.android.contacts", "com.huawei.contacts",
            "com.huawei.incallui", "com.vivo.contacts", "com.vivo.dialer",
            "com.hihonor.dialer", "com.heytap.dialer", "com.coloros.dialer",
            "com.oplus.dialer", "com.oneplus.dialer", "com.realme.dialer",
            "com.miui.securitycenter", "com.miui.phone", "com.android.mms",
            "com.google.android.apps.tachyon", "com.sprd.contacts"
        )
        private val USSD_PACKAGE_HINTS = listOf(
            "phone", "dialer", "telecom", "incall", "callui", "telephony", "ussd",
            "miui", "coloros", "heytap", "oplus", "honor", "transsion", "vivo", "realme",
            "samsung", "huawei", "infinix", "tecno", "itel", "mediatek", "sprd"
        )
        private val BLOCKED_PACKAGES = setOf(
            "com.bingwa.mobile", "com.android.systemui", "com.android.launcher",
            "com.google.android.apps.nexuslauncher", "com.miui.home",
            "com.sec.android.app.launcher", "com.huawei.android.launcher",
            "com.android.settings", "com.google.android.gms", "com.android.keyguard",
            "com.android.packageinstaller"
        )
        private val USSD_HINTS = listOf(
            "enter", "ussd", "choose", "select", "option", "menu", "number", "amount",
            "sambaza", "tuma", "please enter", "enter phone", "enter amount",
            "safaricom", "airtel", "telkom", "faiba", "reply", "continue", "submit",
            "balance", "airtime", "ksh", "kes", "bundle", "data", "account", "pin",
            "send money", "confirm", "retry", "proceed", "voucher", "recipient", "mobile",
            "entrez", "montant", "numéro", "solde", "continuer", "confirmer",
            "أدخل", "رصيد", "تأكيد", "press", "dial", "call", "response", "respond", "tap"
        )
        private val SEND_BUTTON_LABELS = listOf(
            "send", "ok", "tuma", "call", "sambaza", "enda", "confirm",
            "reply", "next", "continue", "submit", "proceed", "accept", "yes", "done",
            "confirmar", "envoyer", "suivant", "continuer", "oui", "go", "enter", "dial",
            "execute", "موافق", "إرسال"
        )
        private val SEND_VIEW_ID_HINTS = listOf(
            "send", "submit", "reply", "continue", "next", "confirm", "positive", "ok",
            "button1", "positivebutton", "positive_button", "dialog_button", "send_button",
            "action_button", "btn_ok", "btn_confirm", "btn_send", "btn_positive",
            "alertdialog_button", "right_button", "primary_button"
        )
        private val INPUT_VIEW_ID_HINTS = listOf(
            "input", "reply", "entry", "message", "ussd", "number", "phone", "amount", "pin",
            "edit", "text", "answer", "field", "value", "data", "query", "response"
        )
        private val INPUT_FIELD_HINTS = listOf(
            "enter", "input", "reply", "phone", "number", "amount", "pin", "account", "mobile", "recipient",
            "text", "answer", "value", "type here", "write here"
        )
        private val PHONE_INPUT_HINTS = listOf(
            "phone", "phone number", "number", "mobile", "mobile number", "recipient", "recipient number",
            "customer", "customer number", "subscriber", "subscriber number", "beneficiary",
            "beneficiary number", "msisdn", "tel", "telephone", "contact", "line number",
            "enter phone", "enter number", "enter mobile", "enter recipient", "enter customer"
        )
        private val DISMISS_BUTTON_LABELS = listOf(
            "ok", "cancel", "close", "dismiss", "back", "no", "exit", "annuler", "fermer",
            "non", "إلغاء", "خروج"
        )
        private val DISMISS_VIEW_ID_HINTS = listOf(
            "cancel", "dismiss", "close", "negative", "back", "no", "exit",
            "button2", "negativebutton", "negative_button", "btn_cancel", "btn_dismiss",
            "btn_negative", "left_button"
        )
        private val NON_USSD_DIALOG_HINTS = listOf(
            "choose sim", "select sim", "sim 1", "sim 2", "sim1", "sim2", "default sim",
            "complete action", "use by default", "just once", "always",
            "allow", "deny", "permission", "grant", "not now",
            "isn't responding", "is not responding", "stopped", "keeps stopping", "close app"
        )
        private val EDITABLE_CLASS_HINTS = listOf(
            "EditText", "TextInputEditText", "AutoCompleteTextView",
            "MultiAutoCompleteTextView", "ExtractEditText",
            "com.samsung.android.widget.SamsungEditText", "android.widget.EditText"
        )

        private val adjustedStepInputs = linkedMapOf<Int, String>()
        private val learnedSignatureSteps = mutableListOf<UssdSignatureStep>()
        private val learningCaptures = mutableListOf<UssdLearningCapture>()
        private val popupTranscript = mutableListOf<String>()
        private val detectedChangeNotes = mutableListOf<String>()
        private var signatureChangeDetected = false
        private var signatureAutoAdjusted = false

        fun resetSignatureTracking() {
            adjustedStepInputs.clear()
            learnedSignatureSteps.clear()
            learningCaptures.clear()
            popupTranscript.clear()
            detectedChangeNotes.clear()
            signatureChangeDetected = false
            signatureAutoAdjusted = false
        }
    }

    private val handler            = Handler(Looper.getMainLooper())
    private var isProcessing       = false
    private var lastDialogText     = ""
    private var stepTimeoutRunnable: Runnable? = null
    private var processStepRunnable: Runnable? = null
    private var lastFinalResponse  = ""
    private var lastInputWriteValue = ""
    private var lastInputWriteElapsed = 0L
    private var lastVerifiedInputValue = ""
    private var lastVerifiedInputElapsed = 0L
    private var pendingProcessToken = 0L
    private var lastWindowPkg = ""
    private var lastWindowId = -1
    private var lastRelevantEventElapsed = 0L
    private var hasSeenAdvancedPopup = false
    private var lastMenuSignatureKey = ""
    private var lastMenuSignature: ParsedMenuSignature? = null
    private var lastScreenSignatureKey = ""
    private var pendingExpectedValue: String? = null
    private var pendingPhase: PendingPhase = PendingPhase.NONE
    private var pendingSinceElapsed: Long = 0L
    private var pendingAttempts: Int = 0
    private var lastStepActionKey: String = ""
    private var lastStepActionElapsed: Long = 0L
    private var pendingStepAdvanceFromKey: String = ""
    private var pendingStepAdvanceSinceElapsed: Long = 0L
    private var pendingStepAdvanceTimeoutRunnable: Runnable? = null

    private enum class PendingPhase { NONE, WAIT_VERIFY, WAIT_SEND }

    private val errorKeywords = listOf(
        "connection problem", "invalid mmi", "mmi code", "network error", "invalid", "failed",
        "cancelled", "try again", "unavailable", "problem", "request timeout", "ussd running",
        "busy", "sim error", "not available", "service unavailable", "temporary error",
        "session expired", "not registered"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes  = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.DEFAULT
            // Keep event delivery immediate so we reduce local UI latency without relaxing verification.
            notificationTimeout = 0
        }
    }

    override fun onInterrupt() { cleanupAdvanced(); clearCallbacks() }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundCompat()
        cleanupAdvanced()
        clearCallbacks()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        val pkg = event.packageName?.toString() ?: ""
        val allowSystemUi = pkg == "com.android.systemui" &&
                (advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode)
        if (pkg in BLOCKED_PACKAGES && !allowSystemUi) return
        val windowId = event.windowId
        val windowChanged = windowId != lastWindowId
        lastWindowId = windowId

        val root = getUssdRoot() ?: return
        try {
            val windowPkg = root.packageName?.toString() ?: ""
            val allowBlockedWindow = windowPkg == "com.android.systemui" && shouldAllowSystemUiDialogRoot(root, windowPkg)
            if (windowPkg in BLOCKED_PACKAGES && !allowBlockedWindow) return
            lastWindowPkg = windowPkg

            val eventDialogText = extractDialogTextFromEvent(event)
            val snapshot = if (
                eventDialogText.isBlank() ||
                signatureLearningMode ||
                advancedActive ||
                balanceCallback != null ||
                tokenPurchaseCallback != null
            ) {
                captureTreeSnapshot(root)
            } else {
                null
            }
            val dialogText = snapshot?.dialogText ?: normalizeCollapsedText(eventDialogText)
            if (dialogText.isBlank()) return
            val lower = dialogText.lowercase()
            if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return
            val looksLikeDialog = when {
                snapshot != null -> looksLikeUssdDialog(
                    root = root,
                    snapshot = snapshot,
                    allTextLower = lower,
                    windowPackageName = windowPkg
                )
                else -> looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = windowPkg)
            }
            if (!looksLikeDialog) return
            lastRelevantEventElapsed = SystemClock.elapsedRealtime()

            if (advancedActive && advancedSteps.isNotEmpty()) {
                hasSeenAdvancedPopup = true
                cancelStepTimeout()
                lastFinalResponse = dialogText
                capturePopupTranscript(dialogText)
                if (signatureLearningMode) captureLearningDialog(root, dialogText)

                // Prevent "next-step" injections on the same dialog right after we click Send/OK.
                if (shouldWaitForStepTransition(dialogText, windowChanged)) return

                if (errorKeywords.any { lower.contains(it) }) {
                    if (signatureLearningMode && currentStep >= advancedSteps.size) {
                        finishAdvancedDispatch(dialogText)
                    } else {
                        dismissErrorAndRestart()
                    }
                    return
                }
                if (pendingStepAdvanceFromKey.isNotBlank() &&
                    handlePendingStepAdvance(
                        windowId = windowId,
                        windowPkg = windowPkg,
                        root = root,
                        snapshot = snapshot,
                        dialogText = dialogText
                    )
                ) return
                val dialogChanged = windowChanged || dialogText != lastDialogText
                lastDialogText = dialogText
                if (!isProcessing) {
                    // If we already wrote an input and are waiting to verify/send, handle that immediately
                    // on the next relevant event (event-driven, no polling loops).
                    if (pendingPhase != PendingPhase.NONE) {
                        attemptPendingAdvance(root)
                        return
                    }
                    val screenKey = buildScreenSignatureKey(
                        stepIndex = currentStep,
                        windowId = windowId,
                        windowPkg = windowPkg,
                        root = root,
                        snapshot = snapshot,
                        dialogText = dialogText
                    )
                    if (!dialogChanged && screenKey == lastScreenSignatureKey) return
                    lastScreenSignatureKey = screenKey
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged)
                }
                return
            }

            handleCallbackDialogs(lower, dialogText)
        } finally {
            root.recycle()
        }
    }

    private fun extractDialogTextFromEvent(event: AccessibilityEvent): String {
        val parts = mutableListOf<String>()
        runCatching {
            event.text?.forEach { cs ->
                val s = cs?.toString()?.trim().orEmpty()
                if (s.isNotBlank()) parts += s
            }
        }
        return normalizeCollapsedText(parts.distinct().joinToString(" "))
    }

    private fun looksLikeUssdDialogFast(allTextLower: String, windowPackageName: String): Boolean {
        val hasUssdLanguage = USSD_HINTS.any { allTextLower.contains(it) } ||
            errorKeywords.any { allTextLower.contains(it) }
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(allTextLower)
        if (windowPackageName == "android" || windowPackageName.isBlank()) {
            if (!advancedActive) return false
            return hasUssdLanguage || menuLike
        }
        val likelyUssdPackage = isPotentialUssdPackage(windowPackageName)
        if (!likelyUssdPackage && !advancedActive) return false
        if (advancedActive) {
            if (hasSeenAdvancedPopup) return true
            return hasUssdLanguage || menuLike
        }
        return likelyUssdPackage && hasUssdLanguage
    }

    private fun looksLikeUssdDialog(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot,
        allTextLower: String,
        windowPackageName: String
    ): Boolean {
        val hasUssdLanguage = USSD_HINTS.any { allTextLower.contains(it) }
        val hasEditField = snapshot.hasEditableField
        val hasSendButton = snapshot.hasSendButton
        val hasDismissButton = snapshot.hasDismissButton
        val hasMenuOptions = parseMenuSignature(snapshot) != null
        val likelyUssdPackage = isPotentialUssdPackage(windowPackageName) ||
            ((windowPackageName == "android" || windowPackageName.isBlank()) && (hasUssdLanguage || hasMenuOptions))
        val hasDialogLayout = hasDialogLayout(root, snapshot)
        return (hasEditField && (hasSendButton || hasUssdLanguage || hasMenuOptions))
                || ((hasSendButton || hasDismissButton) && (hasUssdLanguage || hasMenuOptions))
                || (hasMenuOptions && hasUssdLanguage)
                || (hasDialogLayout && likelyUssdPackage && (hasEditField || hasSendButton || hasDismissButton))
                || (advancedActive && likelyUssdPackage && (hasEditField || hasSendButton || hasDismissButton || hasMenuOptions))
    }

    private fun buildScreenSignatureKey(
        stepIndex: Int,
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String
    ): String {
        val cls = root.className?.toString().orEmpty()
        val normalized = normalizeCollapsedText(dialogText)
        val flags = if (snapshot != null) {
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}"
        } else {
            ""
        }
        return "$stepIndex|$windowId|$windowPkg|$cls|$flags|$normalized"
    }

    private fun buildTransitionSignatureKey(
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String
    ): String {
        val cls = root.className?.toString().orEmpty()
        val normalized = normalizeCollapsedText(dialogText)
        val flags = if (snapshot != null) {
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}"
        } else {
            ""
        }
        return "$windowId|$windowPkg|$cls|$flags|$normalized"
    }

    private fun processStep() {
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        val root = getUssdRoot() ?: run {
            isProcessing = false
            handler.postDelayed({ restartFromBeginning() }, 700)
            return
        }
        try {
            val windowPkg = root.packageName?.toString() ?: ""
            val freshDialogText = normalizeCollapsedText(extractAllText(root))
            val dialogText = freshDialogText.ifBlank { lastFinalResponse }
            val lower = dialogText.lowercase()
            if (shouldWaitForStepTransition(dialogText, windowChanged = false)) {
                isProcessing = false
                pendingProcessToken = SystemClock.elapsedRealtime()
                scheduleProcessStep(dialogChanged = false)
                return
            }
            if (dialogText.isNotBlank()) {
                lastFinalResponse = dialogText
            }
            if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) {
                isProcessing = false
                return
            }
            if (!looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = windowPkg)) {
                isProcessing = false
                return
            }
            if (currentStep >= advancedSteps.size) {
                val finalText = lastFinalResponse
                Log.d(TAG, "All steps complete, finalText='$finalText'")
                finishAdvancedDispatch(finalText)
                return
            }

            val step = advancedSteps[currentStep]
            val menuSignature = parseMenuSignature(root, dialogText)
            if (step != "INPUT_PHONE") {
                captureSignatureStepIfNeeded(currentStep, step, menuSignature, dialogText)
            }
            val resolved = resolveStepInput(currentStep, step, menuSignature)
            if (!advancedActive) {
                isProcessing = false
                return
            }
            val valueToEnter = resolved.first
            val selectedMenuLabel = resolved.second

            val inputField = findEditableFieldForStep(root, step, dialogText)
            try {
                val dialogAllowsPhoneInput = step == "INPUT_PHONE" && dialogSuggestsPhoneInput(lower)
                if (step == "INPUT_PHONE" && inputField == null && !dialogAllowsPhoneInput) {
                    // Wait for the correct prompt/dialog instead of blindly injecting the phone number.
                    isProcessing = false
                    pendingProcessToken = SystemClock.elapsedRealtime()
                    scheduleProcessStep(dialogChanged = false)
                    return
                }
                if (step.all(Char::isDigit) && menuSignature != null && menuSignature.options.isNotEmpty()) {
                    // If the current screen is a menu, ensure our selection exists on THIS menu.
                    // This prevents "next step" inputs from being applied to the previous screen.
                    if (!menuSignature.options.containsKey(valueToEnter)) {
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false)
                        return
                    }
                }
                val shouldPreferTextInput = shouldTreatStepAsTextInput(step, valueToEnter, selectedMenuLabel) ||
                    (selectedMenuLabel == null && (dialogSuggestsTextInput(lower) || dialogAllowsPhoneInput))
                if (inputField == null && shouldPreferTextInput && !dialogSuggestsTextInput(lower) && !dialogAllowsPhoneInput) {
                    val hasEditableField = captureTreeSnapshot(root).hasEditableField
                    if (!hasEditableField) {
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false)
                        return
                    }
                }
                if (inputField != null || shouldPreferTextInput) {
                    val wroteValue = when {
                        inputField != null -> tryWriteValueToField(inputField, valueToEnter) || writeValueToField(valueToEnter)
                        else -> writeValueToField(valueToEnter)
                    }
                    val inlineVerified = verifyExpectedInputFromRoot(
                        root = root,
                        expectedValue = valueToEnter,
                        existingField = inputField
                    )
                    if (!isFinalSignatureLearningStep(currentStep) &&
                        wroteValue &&
                        inlineVerified &&
                        tryImmediateVerifiedSend(root, inputField, valueToEnter)
                    ) {
                        markStepAction(dialogText)
                        startPendingStepAdvance(root, dialogText)
                        return
                    }
                    // Fast path: stop polling loops. Set a pending phase and let the next accessibility
                    // event drive verification/send immediately (with a short safety kick).
                    if (isFinalSignatureLearningStep(currentStep)) {
                        // Keep legacy behavior for learning flows (we don't want to risk changing capture timing).
                        val delay = when {
                            wroteValue && hasSeenAdvancedPopup -> RAPID_POST_POPUP_VERIFY_MS
                            wroteValue -> FAST_VERIFY_POLL_MS
                            else -> VERIFY_POLL_MS
                        }
                        if (delay <= 0L) handler.post { verifyLearningFinalInputThenDismiss(valueToEnter, 0, 0) }
                        else handler.postDelayed({ verifyLearningFinalInputThenDismiss(valueToEnter, 0, 0) }, delay)
                    } else {
                        startPendingAdvance(valueToEnter)
                    }
                    return
                }
            } finally {
                inputField?.recycle()
            }

            val menuBtn = locateMenuButton(root, valueToEnter, selectedMenuLabel)

            if (menuBtn != null) {
                val clicked = try { performClick(menuBtn) } finally { menuBtn.recycle() }
                if (clicked) {
                    markStepAction(dialogText)
                    startPendingStepAdvance(root, dialogText)
                    return
                }
                isProcessing = false
                dismissErrorAndRestart()
                return
            }

            if (menuSignature != null || hasSeenAdvancedPopup) {
                isProcessing = false
                pendingProcessToken = SystemClock.elapsedRealtime()
                scheduleProcessStep(dialogChanged = false)
                return
            }

            isProcessing = false
            dismissErrorAndRestart()
        } finally {
            root.recycle()
        }
    }

    private fun isFinalSignatureLearningStep(stepIndex: Int): Boolean =
        signatureLearningMode && stepIndex == advancedSteps.lastIndex

    private fun getUssdRoot(): AccessibilityNodeInfo? {
        val allowSystemWindows = advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode
        val primary = rootInActiveWindow
        if (primary != null) {
            val pkg = primary.packageName?.toString() ?: ""
            val allowBlocked = pkg !in BLOCKED_PACKAGES || shouldAllowSystemUiDialogRoot(primary, pkg)
            if (
                allowBlocked &&
                (isPotentialUssdPackage(pkg) ||
                        shouldAllowAndroidDialogRoot(primary, pkg) ||
                        shouldAllowSystemUiDialogRoot(primary, pkg))
            ) {
                return primary
            }
            primary.recycle()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val visibleWindows = try { windows } catch (_: Exception) { return null }
            for (window in visibleWindows) {
                if (!allowSystemWindows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                        window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    ) continue
                }
                val root = try { window.root } catch (_: Exception) { null } ?: continue
                val pkg = root.packageName?.toString() ?: ""
                val allowBlocked = pkg !in BLOCKED_PACKAGES || shouldAllowSystemUiDialogRoot(root, pkg)
                if (!allowBlocked) {
                    root.recycle()
                    continue
                }
                if (
                    isPotentialUssdPackage(pkg) ||
                    shouldAllowAndroidDialogRoot(root, pkg) ||
                    shouldAllowSystemUiDialogRoot(root, pkg)
                ) {
                    return root
                }
                root.recycle()
            }
        }
        return null
    }

    private fun shouldAllowSystemUiDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (pkg != "com.android.systemui") return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode) return false
        if (!hasDialogLayout(root)) return false
        val dialogText = normalizeCollapsedText(extractAllText(root))
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = hasSendOrOkButton(root) || hasDismissButton(root) || hasEditableField(root)
        if (!hasAction) return false
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        return hasUssdLanguage || menuLike
    }

    private fun shouldAllowAndroidDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (!(pkg.isBlank() || pkg == "android")) return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null) return false
        if (!hasDialogLayout(root)) return false
        val dialogText = normalizeCollapsedText(extractAllText(root))
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = hasSendOrOkButton(root) || hasDismissButton(root) || hasEditableField(root)
        if (!hasAction) return false
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        return hasUssdLanguage || menuLike
    }

    private data class ParsedMenuSignature(
        val title: String,
        val options: LinkedHashMap<String, String>
    )

    private data class UssdTreeSnapshot(
        val dialogText: String,
        val normalizedDialogText: String,
        val textTokens: List<String>,
        val hasEditableField: Boolean,
        val hasSendButton: Boolean,
        val hasDismissButton: Boolean
    )

    private data class MenuOptionMatch(
        val optionKey: String,
        val optionLabel: String,
        val autoAdjustSafe: Boolean
    )

    private fun scheduleProcessStep(dialogChanged: Boolean) {
        processStepRunnable?.let { handler.removeCallbacks(it) }
        val token = pendingProcessToken
        val delayMs = when {
            hasSeenAdvancedPopup && dialogChanged -> RAPID_POST_POPUP_POLL_MS
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_VERIFY_MS
            dialogChanged && hasRecentUssdUiEvent() -> EVENT_HOT_POLL_MS
            dialogChanged -> POPUP_STABILITY_DELAY_MS
            hasRecentUssdUiEvent() -> FAST_VERIFY_POLL_MS
            else -> VERIFY_POLL_MS
        }
        val task = Runnable {
            processStepRunnable = null
            if (pendingProcessToken != token) return@Runnable
            if (!advancedActive || isProcessing) return@Runnable
            isProcessing = true
            processStep()
        }
        processStepRunnable = task
        if (delayMs <= 0L) handler.post(task) else handler.postDelayed(task, delayMs)
    }

    private fun verificationPollDelay(expected: String, noFieldCount: Int = 0): Long =
        when {
            hasSeenAdvancedPopup && hasRecentExpectedInput(expected) -> RAPID_POST_POPUP_VERIFY_MS
            hasSeenAdvancedPopup && noFieldCount > 0 -> RAPID_POST_POPUP_VERIFY_MS
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_VERIFY_MS
            hasRecentUssdUiEvent() && hasRecentExpectedInput(expected) -> EVENT_HOT_POLL_MS
            hasRecentUssdUiEvent() && noFieldCount > 0 -> EVENT_HOT_POLL_MS
            hasRecentExpectedInput(expected) -> FAST_VERIFY_POLL_MS
            noFieldCount > 0 -> FAST_VERIFY_POLL_MS
            hasRecentUssdUiEvent() -> FAST_VERIFY_POLL_MS
            else -> VERIFY_POLL_MS
        }

    private fun hasRecentUssdUiEvent(): Boolean =
        SystemClock.elapsedRealtime() - lastRelevantEventElapsed <= RECENT_UI_EVENT_GRACE_MS

    private fun sendRetryDelay(attempt: Int): Long {
        val base = when {
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> RAPID_POST_POPUP_SEND_RETRY_MS
            hasSeenAdvancedPopup -> HOT_SEND_RETRY_DELAY_MS
            hasRecentUssdUiEvent() -> HOT_SEND_RETRY_DELAY_MS
            else -> SEND_RETRY_DELAY_MS
        }
        val increment = if (hasSeenAdvancedPopup) 2L else 4L
        return minOf(base + (attempt.toLong() * increment), 36L)
    }

    private fun captureSignatureStepIfNeeded(
        stepIndex: Int,
        rawStep: String,
        menu: ParsedMenuSignature?,
        dialogText: String
    ) {
        if (!signatureLearningMode || !rawStep.all(Char::isDigit) || menu == null) return
        val optionLabel = menu.options[rawStep] ?: return
        val captured = UssdSignatureStep(
            stepIndex = stepIndex,
            expectedInput = rawStep,
            menuTitle = menu.title,
            menuText = normalizeCollapsedText(dialogText),
            selectedOptionLabel = optionLabel,
            menuOptionsSnapshot = menu.options.values
                .map { normalizeCollapsedText(it) }
                .filter { it.isNotBlank() }
        )
        val existingIndex = learnedSignatureSteps.indexOfFirst { it.stepIndex == stepIndex }
        if (existingIndex >= 0) learnedSignatureSteps[existingIndex] = captured else learnedSignatureSteps.add(captured)
    }

    private fun resolveStepInput(stepIndex: Int, rawStep: String, menu: ParsedMenuSignature?): Pair<String, String?> {
        if (rawStep == "INPUT_PHONE") {
            adjustedStepInputs[stepIndex] = rawStep
            return UssdHelper.normalizeRecipientForUssdInput(advancedPhoneNumber) to null
        }
        if (!rawStep.all(Char::isDigit) || !signatureGuardEnabled) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to menu?.options?.get(rawStep)
        }
        val learned = loadedSignatureSteps.firstOrNull { it.stepIndex == stepIndex } ?: run {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to menu?.options?.get(rawStep)
        }
        if (menu == null || menu.options.isEmpty()) {
            adjustedStepInputs[stepIndex] = rawStep
            return rawStep to null
        }

        val match = findBestMenuOptionMatch(menu, learned)
        if (match == null) {
            val message = buildChangeMessage(
                learned = learned,
                actualOption = null,
                actualLabel = null
            )
            signatureChangeDetected = true
            detectedChangeNotes += message
            failForSignatureChange(message)
            return rawStep to null
        }
        if (match.optionKey != rawStep) {
            signatureChangeDetected = true
            detectedChangeNotes += buildChangeMessage(
                learned = learned,
                actualOption = match.optionKey,
                actualLabel = match.optionLabel
            )
            if (signatureAction != "ADJUST" || !match.autoAdjustSafe) {
                failForSignatureChange(detectedChangeNotes.last())
                return rawStep to null
            }
            signatureAutoAdjusted = true
            adjustedStepInputs[stepIndex] = match.optionKey
            return match.optionKey to match.optionLabel
        }
        adjustedStepInputs[stepIndex] = rawStep
        return rawStep to match.optionLabel
    }

    private fun buildChangeMessage(
        learned: UssdSignatureStep,
        actualOption: String?,
        actualLabel: String?
    ): String {
        val menuLabel = learned.menuTitle.ifBlank { advancedOfferName.ifBlank { "the USSD menu" } }
        return if (actualOption.isNullOrBlank() || actualLabel.isNullOrBlank()) {
            "Detected a menu change in $menuLabel. The learned option '${learned.selectedOptionLabel}' is no longer available as selection ${learned.expectedInput}"
        } else {
            "Detected a menu change in $menuLabel. '${learned.selectedOptionLabel}' moved from option ${learned.expectedInput} to option $actualOption"
        }
    }

    private fun failForSignatureChange(message: String) {
        lastFinalResponse = message
        onDispatchComplete?.invoke(buildDispatchResult(message))
        tokenPurchaseCallback?.invoke(false)
        closeCurrentUssdUi()
        advancedInProgress = false
        cleanupAdvanced()
    }

    private fun finishAdvancedDispatch(finalText: String) {
        onDispatchComplete?.invoke(buildDispatchResult(finalText))
        if (!signatureLearningMode) tokenPurchaseCallback?.invoke(true)
        closeCurrentUssdUi()
        advancedInProgress = false
        cleanupAdvanced()
    }

    private fun buildDispatchResult(finalResponse: String): AdvancedDispatchResult =
        AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = signatureChangeDetected,
            autoAdjusted = signatureAutoAdjusted,
            learningCompleted = signatureLearningMode && (learnedSignatureSteps.isNotEmpty() || learningCaptures.isNotEmpty()),
            suggestedCode = if (signatureChangeDetected) buildSuggestedCode() else "",
            changeSummary = detectedChangeNotes.joinToString(". "),
            learnedSignature = learnedSignatureSteps.toList(),
            learningCaptures = learningCaptures.toList(),
            popupTranscript = popupTranscript.toList()
        )

    private fun capturePopupTranscript(dialogText: String) {
        val normalizedText = normalizeCollapsedText(dialogText)
        if (normalizedText.isBlank()) return
        if (popupTranscript.lastOrNull() == normalizedText) return
        popupTranscript += normalizedText
    }

    private fun captureLearningDialog(root: AccessibilityNodeInfo, dialogText: String) {
        val normalizedText = normalizeCollapsedText(dialogText)
        if (normalizedText.isBlank()) return

        val captureIndex = when {
            advancedSteps.isEmpty() -> -1
            currentStep >= advancedSteps.size -> advancedSteps.lastIndex
            else -> currentStep
        }
        val rawStep = advancedSteps.getOrNull(captureIndex).orEmpty()
        val menu = parseMenuSignature(root, dialogText)
        val selectedOptionLabel = when {
            rawStep == "INPUT_PHONE" -> "Enter phone number"
            rawStep.all(Char::isDigit) -> menu?.options?.get(rawStep).orEmpty()
            else -> ""
        }
        val enteredInput = when {
            rawStep == "INPUT_PHONE" -> advancedPhoneNumber
            rawStep.isNotBlank() -> rawStep
            captureIndex >= 0 -> adjustedStepInputs[captureIndex].orEmpty()
            else -> ""
        }
        val capture = UssdLearningCapture(
            stepIndex = captureIndex,
            enteredInput = enteredInput,
            selectedOptionLabel = selectedOptionLabel,
            popupText = normalizedText
        )
        val existingIndex = learningCaptures.indexOfFirst {
            it.stepIndex == capture.stepIndex &&
                normalizeMenuText(it.popupText) == normalizeMenuText(capture.popupText)
        }
        if (existingIndex >= 0) {
            val existing = learningCaptures[existingIndex]
            learningCaptures[existingIndex] = existing.copy(
                enteredInput = capture.enteredInput.ifBlank { existing.enteredInput },
                selectedOptionLabel = capture.selectedOptionLabel.ifBlank { existing.selectedOptionLabel },
                popupText = if (capture.popupText.length >= existing.popupText.length) capture.popupText else existing.popupText
            )
        } else {
            learningCaptures += capture
        }
    }

    private fun buildSuggestedCode(): String {
        val dialBase = advancedDialCode.trim().replace("%23", "").trimEnd('#')
        val steps = advancedSteps.mapIndexed { index, step ->
            when {
                step == "INPUT_PHONE" -> "pn"
                adjustedStepInputs[index].isNullOrBlank() -> step
                else -> adjustedStepInputs[index] ?: step
            }
        }
        return if (steps.isEmpty()) "$dialBase#" else "$dialBase*${steps.joinToString("*")}#"
    }

    private fun normalizeMenuText(text: String): String =
        text.lowercase()
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    private fun markStepAction(dialogText: String) {
        lastStepActionKey = normalizeMenuText(dialogText)
        lastStepActionElapsed = SystemClock.elapsedRealtime()
    }

    private fun shouldWaitForStepTransition(dialogText: String, windowChanged: Boolean): Boolean {
        val key = normalizeMenuText(dialogText)
        if (lastStepActionKey.isBlank() || key.isBlank()) return false
        if (key != lastStepActionKey) {
            // Screen changed; clear the guard and proceed normally.
            lastStepActionKey = ""
            lastStepActionElapsed = 0L
            return false
        }
        if (windowChanged) return false
        val elapsed = SystemClock.elapsedRealtime() - lastStepActionElapsed
        return elapsed in 0..STEP_TRANSITION_GUARD_MS
    }

    private fun parseMenuSignature(snapshot: UssdTreeSnapshot): ParsedMenuSignature? {
        val cacheKey = snapshot.normalizedDialogText
        if (cacheKey.isNotBlank() && cacheKey == lastMenuSignatureKey) {
            return lastMenuSignature
        }
        val parsed = buildMenuSignature(snapshot.textTokens)
        if (cacheKey.isNotBlank()) {
            lastMenuSignatureKey = cacheKey
            lastMenuSignature = parsed
        }
        return parsed
    }

    private fun parseMenuSignature(
        root: AccessibilityNodeInfo,
        dialogText: String = extractAllText(root)
    ): ParsedMenuSignature? {
        val cacheKey = normalizeCollapsedText(dialogText)
        if (cacheKey.isNotBlank() && cacheKey == lastMenuSignatureKey) {
            return lastMenuSignature
        }
        val parsed = buildMenuSignature(extractTextTokens(root))
        if (cacheKey.isNotBlank()) {
            lastMenuSignatureKey = cacheKey
            lastMenuSignature = parsed
        }
        return parsed
    }

    private fun buildMenuSignature(tokens: List<String>): ParsedMenuSignature? {
        val normalizedTokens = tokens
            .flatMap { token -> token.lineSequence().map { it.trim() }.toList() }
            .map { normalizeCollapsedText(it) }
            .filter { it.isNotBlank() }
            .distinct()

        val options = linkedMapOf<String, String>()
        val titleParts = mutableListOf<String>()
        var pendingOptionKey: String? = null

        for (token in normalizedTokens) {
            val lower = token.lowercase()
            if (lower in setOf("send", "ok", "call", "cancel", "close", "confirm", "enda", "tuma")) continue
            val match = MENU_OPTION_REGEX.find(token)
            if (match != null) {
                pendingOptionKey = null
                val key = match.groupValues[1]
                val label = match.groupValues[2].trim()
                if (label.isNotBlank()) options[key] = label
                continue
            }

            val numberOnly = MENU_OPTION_NUMBER_ONLY_REGEX.find(token)
            if (numberOnly != null) {
                pendingOptionKey = numberOnly.groupValues[1]
                continue
            }

            val deferredKey = pendingOptionKey
            if (deferredKey != null && looksLikeMenuLabel(token)) {
                options[deferredKey] = token
                pendingOptionKey = null
            } else if (options.isEmpty()) {
                titleParts += token
            }
        }

        if (options.isEmpty()) return null
        return ParsedMenuSignature(
            title = titleParts.distinct().take(2).joinToString(" / "),
            options = LinkedHashMap(options)
        )
    }

    private fun looksLikeMenuLabel(token: String): Boolean {
        val normalized = normalizeActionLabel(token)
        if (normalized.isBlank()) return false
        if (normalized in SEND_BUTTON_LABELS || normalized in DISMISS_BUTTON_LABELS) return false
        return normalized.any(Char::isLetterOrDigit)
    }

    private fun findBestMenuOptionMatch(
        menu: ParsedMenuSignature,
        learned: UssdSignatureStep
    ): MenuOptionMatch? {
        val expectedLabel = learned.selectedOptionLabel
        val normalizedExpected = normalizeMenuText(expectedLabel)
        if (normalizedExpected.isBlank()) return null
        if (!isMenuContextCompatible(menu, learned)) return null

        val exactMatches = menu.options.entries
            .asSequence()
            .filter { entry -> normalizeMenuText(entry.value) == normalizedExpected }
            .map { entry ->
                MenuOptionMatch(
                    optionKey = entry.key,
                    optionLabel = entry.value,
                    autoAdjustSafe = true
                )
            }
            .toList()
        return when (exactMatches.size) {
            1 -> exactMatches.first()
            else -> null
        }
    }

    private fun isMenuContextCompatible(menu: ParsedMenuSignature, learned: UssdSignatureStep): Boolean {
        val learnedTitle = normalizeMenuText(learned.menuTitle)
        val currentTitle = normalizeMenuText(menu.title)
        if (learnedTitle.isNotBlank() && currentTitle.isNotBlank() && learnedTitle != currentTitle) {
            return false
        }

        val learnedSnapshot = learned.menuOptionsSnapshot
            .asSequence()
            .map(::normalizeMenuText)
            .filter { it.isNotBlank() }
            .toSet()
        val currentSnapshot = menu.options.values
            .asSequence()
            .map(::normalizeMenuText)
            .filter { it.isNotBlank() }
            .toSet()
        if (learnedSnapshot.isEmpty() || currentSnapshot.isEmpty()) return true

        val overlapCount = learnedSnapshot.intersect(currentSnapshot).size
        val requiredOverlap = minOf(2, learnedSnapshot.size, currentSnapshot.size)
        return overlapCount >= requiredOverlap
    }

    private fun extractTextTokens(node: AccessibilityNodeInfo, into: MutableList<String> = mutableListOf()): List<String> {
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { into += it }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                extractTextTokens(child, into)
                child.recycle()
            }
        } catch (_: Exception) {}
        return into
    }

    private fun writeValueToField(value: String): Boolean {
        val root = getUssdRoot() ?: return false
        val windowPkg = root.packageName?.toString() ?: ""
        if (windowPkg.isNotEmpty() && windowPkg != "android" && !isPotentialUssdPackage(windowPkg)) {
            root.recycle()
            return false
        }
        val lower = normalizeCollapsedText(extractAllText(root)).lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) {
            root.recycle()
            return false
        }
        val fields = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectTextEntryCandidates(root, fields)
            if (fields.isEmpty()) return false
            fields.sortByDescending { scoreTextEntryCandidate(it) }
            return fields.any { field -> tryWriteValueToField(field, value) }
        } finally {
            fields.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun verifyThenSend(expected: String, attempt: Int, noFieldCount: Int = 0) {
        if (!advancedActive) { isProcessing = false; return }
        if (attempt >= MAX_VERIFY_ATTEMPTS) {
            isProcessing = false; handler.post { dismissErrorAndRestart() }; return
        }
        val root = getUssdRoot() ?: run {
            handler.postDelayed(
                { verifyThenSend(expected, attempt + 1, noFieldCount) },
                verificationPollDelay(expected, noFieldCount)
            )
            return
        }
        var fieldText : String? = null
        var fieldRef  : AccessibilityNodeInfo? = null
        var verified = false
        try {
            fieldRef  = findEditableField(root)
            if (fieldRef != null) {
                fieldText = readFieldText(fieldRef)
                verified = verifyExpectedInputFromRoot(root, expected, fieldRef)
            }
        } finally { fieldRef?.recycle(); root.recycle() }

        if (fieldText == null) {
            val newCount = noFieldCount + 1
            if (hasRecentVerifiedInput(expected) && newCount >= 2) {
                handler.post { clickSendButton(expected, 0, skipFieldVerification = true) }
            } else if (newCount >= NO_FIELD_PATIENCE && hasRecentVerifiedInput(expected)) {
                handler.post { clickSendButton(expected, 0) }
            }
            else handler.postDelayed(
                { verifyThenSend(expected, attempt + 1, newCount) },
                verificationPollDelay(expected, newCount)
            )
            return
        }
        if (verified || isVerifiedFieldValue(fieldText, expected)) {
            rememberVerifiedInput(expected)
            handler.post { clickSendButton(expected, 0, skipFieldVerification = true) }
        } else {
            // Avoid expensive re-writes if we very recently injected the same value.
            if (!hasRecentExpectedInput(expected)) {
                writeValueToField(expected)
            }
            handler.postDelayed(
                { verifyThenSend(expected, attempt + 1, 0) },
                verificationPollDelay(expected)
            )
        }
    }

    private fun verifyLearningFinalInputThenDismiss(expected: String, attempt: Int, noFieldCount: Int = 0) {
        if (!advancedActive) { isProcessing = false; return }
        if (attempt >= MAX_VERIFY_ATTEMPTS) {
            if (hasRecentExpectedInput(expected)) {
                handler.post { finishLearningWithoutFinalSubmission() }
            } else {
                isProcessing = false
                handler.post { dismissErrorAndRestart() }
            }
            return
        }
        val root = getUssdRoot() ?: run {
            handler.postDelayed(
                { verifyLearningFinalInputThenDismiss(expected, attempt + 1, noFieldCount) },
                verificationPollDelay(expected, noFieldCount)
            )
            return
        }
        var fieldText: String? = null
        var fieldRef: AccessibilityNodeInfo? = null
        var verified = false
        try {
            fieldRef = findEditableField(root)
            if (fieldRef != null) {
                fieldText = readFieldText(fieldRef)
                verified = verifyExpectedInputFromRoot(root, expected, fieldRef)
            }
        } finally {
            fieldRef?.recycle()
            root.recycle()
        }

        when {
            fieldText == null -> {
                val newCount = noFieldCount + 1
                if (hasRecentVerifiedInput(expected) && newCount >= 1) {
                    handler.post { finishLearningWithoutFinalSubmission() }
                } else if (newCount >= NO_FIELD_PATIENCE) {
                    isProcessing = false
                    handler.post { dismissErrorAndRestart() }
                } else {
                    handler.postDelayed(
                        { verifyLearningFinalInputThenDismiss(expected, attempt + 1, newCount) },
                        verificationPollDelay(expected, newCount)
                    )
                }
            }
            verified || isVerifiedFieldValue(fieldText, expected) -> {
                rememberVerifiedInput(expected)
                handler.post { finishLearningWithoutFinalSubmission() }
            }
            else -> {
                if (!hasRecentExpectedInput(expected)) {
                    writeValueToField(expected)
                }
                handler.postDelayed(
                    { verifyLearningFinalInputThenDismiss(expected, attempt + 1, 0) },
                    verificationPollDelay(expected)
                )
            }
        }
    }

    private fun doubleConfirmThenSend(expected: String, attempt: Int) {
        if (!advancedActive) { isProcessing = false; return }
        var fieldText: String? = null
        var fieldRef : AccessibilityNodeInfo? = null
        var verified = false
        val root = getUssdRoot()
        if (root != null) {
            try {
                fieldRef  = findEditableField(root)
                if (fieldRef != null) {
                    fieldText = readFieldText(fieldRef)
                    verified = verifyExpectedInputFromRoot(root, expected, fieldRef)
                }
            } finally { fieldRef?.recycle(); root.recycle() }
        }
        when {
            fieldText == null -> {
                if (hasRecentVerifiedInput(expected)) {
                    handler.post { clickSendButton(expected, 0, skipFieldVerification = true) }
                }
                else handler.postDelayed(
                    { verifyThenSend(expected, attempt, 1) },
                    verificationPollDelay(expected, 1)
                )
            }
            verified || isVerifiedFieldValue(fieldText, expected) -> {
                rememberVerifiedInput(expected)
                handler.post { clickSendButton(expected, 0, skipFieldVerification = true) }
            }
            else -> {
                writeValueToField(expected)
                handler.postDelayed(
                    { verifyThenSend(expected, attempt, 0) },
                    verificationPollDelay(expected)
                )
            }
        }
    }

    private fun clickSendButton(expectedValue: String, attempt: Int, skipFieldVerification: Boolean = false) {
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        if (attempt >= MAX_SEND_ATTEMPTS) {
            isProcessing = false; handler.post { dismissErrorAndRestart() }; return
        }
        if (skipFieldVerification && !hasRecentVerifiedInput(expectedValue)) {
            handler.post { verifyThenSend(expectedValue, 0) }
            return
        }
        val root = getUssdRoot() ?: run {
            handler.postDelayed(
                { clickSendButton(expectedValue, attempt + 1) },
                sendRetryDelay(attempt)
            )
            return
        }
        var fieldText: String? = null
        var fieldRef : AccessibilityNodeInfo? = null
        var sendBtn  : AccessibilityNodeInfo? = null
        try {
            if (!skipFieldVerification) {
                fieldRef = findEditableField(root)
                if (fieldRef != null) {
                    fieldText = readFieldText(fieldRef)
                }
                if (fieldText != null &&
                    !isVerifiedFieldValue(fieldText, expectedValue)
                ) {
                    handler.post { verifyThenSend(expectedValue, 0) }
                    return
                }
                if (fieldText != null && isVerifiedFieldValue(fieldText, expectedValue)) {
                    rememberVerifiedInput(expectedValue)
                }
            }
            sendBtn = findBestSendActionButton(root)
                ?: findPositiveDialogButton(root)
                ?: findBottomRightActionButton(root)

            if (sendBtn != null) {
                val clicked = performClick(sendBtn)
                sendBtn.recycle(); sendBtn = null
                if (clicked) {
                    val text = extractAllText(root)
                    markStepAction(text)
                    startPendingStepAdvance(root, text)
                    return
                }
                else {
                    if (fieldText != null && triggerInputSubmit(root, expectedValue, fieldRef)) {
                        val text = extractAllText(root)
                        markStepAction(text)
                        startPendingStepAdvance(root, text)
                        return
                    }
                    handler.postDelayed(
                        { clickSendButton(expectedValue, attempt + 1) },
                        sendRetryDelay(attempt)
                    )
                }
            } else {
                if ((fieldText != null || skipFieldVerification) && triggerInputSubmit(root, expectedValue, fieldRef)) {
                    val text = extractAllText(root)
                    markStepAction(text)
                    startPendingStepAdvance(root, text)
                    return
                }
                // Avoid re-scanning/re-writing on every retry when we already know we injected this value recently.
                if (!hasRecentExpectedInput(expectedValue) && attempt < 2) {
                    writeValueToField(expectedValue)
                }
                handler.postDelayed(
                    { clickSendButton(expectedValue, attempt + 1) },
                    sendRetryDelay(attempt) + HOT_SEND_RETRY_DELAY_MS
                )
            }
        } finally {
            fieldRef?.recycle()
            sendBtn?.recycle()
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun advanceStep() {
        currentStep++
        isProcessing   = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        startStepTimeout()
    }

    private fun finishLearningWithoutFinalSubmission() {
        val finalText = lastFinalResponse.ifBlank {
            "Signature learning captured without submitting the final step"
        }
        currentStep = advancedSteps.size
        isProcessing = false
        clearInputWriteMarker()
        closeCurrentUssdUi()
        onDispatchComplete?.invoke(buildDispatchResult(finalText))
        advancedInProgress = false
        cleanupAdvanced()
    }

    private fun dismissErrorAndRestart() {
        clearPendingStepAdvance()
        val dismissed = closeCurrentUssdUi()
        val delay = if (dismissed) DIALOG_DISMISS_SETTLE_MS else 0L
        handler.postDelayed({ restartFromBeginning() }, delay)
    }

    private fun dismissCurrentDialog(): Boolean {
        val root = getUssdRoot()
        if (root != null) {
            try {
                val btn = findActionButton(root, DISMISS_BUTTON_LABELS)
                if (btn != null) {
                    return try { performClick(btn) } finally { btn.recycle() }
                }
            } finally {
                root.recycle()
            }
        }
        return false
    }

    private fun closeCurrentUssdUi(): Boolean {
        if (dismissCurrentDialog()) return true
        val root = getUssdRoot() ?: return false
        return try {
            val pkg = root.packageName?.toString().orEmpty()
            val dialogText = normalizeCollapsedText(extractAllText(root))
            val lower = dialogText.lowercase()
            val looksLikeVisibleUssd = dialogText.isNotBlank() &&
                !NON_USSD_DIALOG_HINTS.any { lower.contains(it) } &&
                (
                    looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = pkg) ||
                        hasDialogLayout(root) ||
                        hasEditableField(root) ||
                        hasSendOrOkButton(root) ||
                        hasDismissButton(root)
                    )
            if (!looksLikeVisibleUssd) return false
            performGlobalAction(GLOBAL_ACTION_BACK)
        } finally {
            root.recycle()
        }
    }

    private fun restartFromBeginning() {
        if (retryCount >= MAX_RETRIES) {
            val failMsg = if (lastFinalResponse.isNotBlank()) lastFinalResponse else "FAILED after $MAX_RETRIES retries"
            onDispatchComplete?.invoke(buildDispatchResult(failMsg))
            tokenPurchaseCallback?.invoke(false)
            tokenPurchaseCallback = null
            cleanupAdvanced()
            return
        }
        retryCount++
        currentStep    = 0
        isProcessing   = false
        hasSeenAdvancedPopup = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        clearPendingAdvance()
        clearPendingStepAdvance()
        pendingProcessToken = 0L
        clearInputWriteMarker()
        redialAdvancedIfNeeded()
        startStepTimeout()
    }

    private fun redialAdvancedIfNeeded() {
        val dialCode = advancedDialCode.trim()
        if (dialCode.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedialElapsed < REDIAL_COOLDOWN_MS) return
        lastRedialElapsed = now
        runCatching {
            val i = UssdHelper.buildCallIntent(this, dialCode)
            startActivity(i)
        }
    }

    private fun handleCallbackDialogs(lower: String, dialogText: String) {
        tokenPurchaseCallback?.let { cb ->
            when {
                lower.contains("you have transferred") ||
                (lower.contains("transfer") && lower.contains("successful")) -> {
                    cb(true); closeCurrentUssdUi(); clearCallbacks(); return
                }
                lower.contains("insufficient") || lower.contains("failed") || lower.contains("cancelled") -> {
                    cb(false); closeCurrentUssdUi(); clearCallbacks(); return
                }
                else -> {}
            }
        }

        balanceCallback?.let { cb ->
            val hasBalance = lower.contains("balance") || lower.contains("airtime")
                    || lower.contains("ksh") || lower.contains("kes")
                    || dialogText.matches(Regex(""".*\d[\d,]*\.?\d*.*""", RegexOption.DOT_MATCHES_ALL))
            if (hasBalance) {
                airtimeBalance = dialogText
                val display = BalanceChecker.parseBalanceDisplay(dialogText)
                val intVal  = BalanceChecker.parseBalanceInt(dialogText)
                BalanceChecker.currentBalance    = intVal
                BalanceChecker.currentBalanceStr = display
                BalanceChecker.balanceCallback?.invoke(display)
                closeCurrentUssdUi()
                clearCallbacks()
            }
        }
    }

    private fun startStepTimeout() {
        cancelStepTimeout()
        val timeout = Runnable {
            val dismissed = closeCurrentUssdUi()
            val delay = if (dismissed) DIALOG_DISMISS_SETTLE_MS else 0L
            handler.postDelayed({ restartFromBeginning() }, delay)
        }
        stepTimeoutRunnable = timeout
        handler.postDelayed(timeout, STEP_TIMEOUT_MS)
    }

    private fun cancelStepTimeout() {
        stepTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stepTimeoutRunnable = null
    }

    private fun cleanupAdvanced() {
        cancelStepTimeout()
        processStepRunnable?.let { handler.removeCallbacks(it) }
        processStepRunnable = null
        handler.removeCallbacksAndMessages(null)
        currentStep         = 0
        advancedSteps       = emptyList()
        advancedPhoneNumber = ""
        advancedDialCode    = ""
        advancedOfferId     = -1
        advancedOfferName   = ""
        advancedActive      = false
        advancedInProgress  = false
        isProcessing        = false
        retryCount          = 0
        lastRedialElapsed   = 0L
        signatureGuardEnabled = false
        signatureAction     = "STOP"
        signatureLearningMode = false
        loadedSignatureSteps = emptyList()
        lastDialogText      = ""
        lastFinalResponse   = ""
        pendingProcessToken = 0L
        lastWindowPkg       = ""
        lastRelevantEventElapsed = 0L
        hasSeenAdvancedPopup = false
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        onDispatchComplete  = null
        tokenPurchaseCallback = null
        resetSignatureTracking()
    }

    private fun clearPendingAdvance() {
        pendingExpectedValue = null
        pendingPhase = PendingPhase.NONE
        pendingSinceElapsed = 0L
        pendingAttempts = 0
    }

    private fun startPendingAdvance(expectedValue: String) {
        pendingExpectedValue = expectedValue
        pendingPhase = PendingPhase.WAIT_VERIFY
        pendingSinceElapsed = SystemClock.elapsedRealtime()
        pendingAttempts = 0
        isProcessing = false
        // Safety kick in case OEM doesn't emit a useful event after ACTION_SET_TEXT.
        handler.postDelayed({ attemptPendingAdvanceWithRoot(null) }, 120L)
    }

    private fun attemptPendingAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) { clearPendingAdvance(); isProcessing = false; return }
        val root = existingRoot ?: getUssdRoot() ?: return
        try {
            attemptPendingAdvance(root)
        } finally {
            if (existingRoot == null) runCatching { root.recycle() }
        }
    }

    private fun attemptPendingAdvance(root: AccessibilityNodeInfo) {
        val expected = pendingExpectedValue ?: run { clearPendingAdvance(); return }
        val elapsed = SystemClock.elapsedRealtime() - pendingSinceElapsed
        if (elapsed > PENDING_ADVANCE_TIMEOUT_MS) {
            clearPendingAdvance()
            isProcessing = false
            dismissErrorAndRestart()
            return
        }

        when (pendingPhase) {
            PendingPhase.WAIT_VERIFY -> {
                val field = findEditableField(root)
                val verified = try {
                    if (field != null) {
                        verifyExpectedInputFromRoot(
                            root = root,
                            expectedValue = expected,
                            existingField = field
                        )
                    } else {
                        hasRecentVerifiedInput(expected)
                    }
                } finally {
                    runCatching { field?.recycle() }
                }

                if (verified) {
                    pendingPhase = PendingPhase.WAIT_SEND
                    attemptPendingAdvance(root)
                    return
                }

                // One corrective write, then wait for the next event.
                if (pendingAttempts == 0 && !hasRecentExpectedInput(expected)) {
                    pendingAttempts++
                    writeValueToField(expected)
                }
                isProcessing = false
            }

            PendingPhase.WAIT_SEND -> {
                val sent = tryImmediateVerifiedSend(root, field = null, expectedValue = expected)
                if (sent) {
                    clearPendingAdvance()
                    val text = extractAllText(root)
                    markStepAction(text)
                    startPendingStepAdvance(root, text)
                } else {
                    // Let the next event re-try, but avoid expensive work here.
                    isProcessing = false
                }
            }

            PendingPhase.NONE -> Unit
        }
    }

    private fun clearCallbacks() {
        lastDialogText      = ""
        tokenPurchaseCallback = null
        balanceCallback     = null
        onDispatchComplete  = null
    }

    private fun clearPendingStepAdvance() {
        pendingStepAdvanceFromKey = ""
        pendingStepAdvanceSinceElapsed = 0L
        pendingStepAdvanceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceTimeoutRunnable = null
    }

    private fun startPendingStepAdvance(root: AccessibilityNodeInfo, dialogText: String) {
        clearPendingStepAdvance()
        pendingStepAdvanceSinceElapsed = SystemClock.elapsedRealtime()
        val snapshot = captureTreeSnapshot(root)
        pendingStepAdvanceFromKey = buildTransitionSignatureKey(
            windowId = lastWindowId,
            windowPkg = root.packageName?.toString().orEmpty(),
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        pendingStepAdvanceTimeoutRunnable = Runnable {
            if (pendingStepAdvanceFromKey.isBlank()) return@Runnable
            clearPendingStepAdvance()
            isProcessing = false
            dismissErrorAndRestart()
        }
        handler.postDelayed(pendingStepAdvanceTimeoutRunnable!!, PENDING_STEP_ADVANCE_TIMEOUT_MS)
        isProcessing = false
    }

    private fun handlePendingStepAdvance(
        windowId: Int,
        windowPkg: String,
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        dialogText: String
    ): Boolean {
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) return false
        val elapsed = SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed
        if (elapsed > PENDING_STEP_ADVANCE_TIMEOUT_MS) {
            clearPendingStepAdvance()
            isProcessing = false
            dismissErrorAndRestart()
            return true
        }
        val currentKey = buildTransitionSignatureKey(
            windowId = windowId,
            windowPkg = windowPkg,
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        if (currentKey == fromKey) return true
        clearPendingStepAdvance()
        advanceStep()
        pendingProcessToken = SystemClock.elapsedRealtime()
        scheduleProcessStep(dialogChanged = true)
        return true
    }

    private inline fun safeFind(root: AccessibilityNodeInfo, finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?): AccessibilityNodeInfo? =
        try { finder(root) } catch (_: Exception) { null }

    private fun isPotentialUssdPackage(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == "android") return false
        if (pkg in USSD_PACKAGES) return true
        val lower = pkg.lowercase()
        return USSD_PACKAGE_HINTS.any { lower.contains(it) }
    }

    private fun hasDialogLayout(node: AccessibilityNodeInfo, snapshot: UssdTreeSnapshot? = null): Boolean {
        val className = node.className?.toString().orEmpty()
        return className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true) ||
            (node.childCount in 1..6 && (
                snapshot?.hasSendButton == true ||
                    snapshot?.hasDismissButton == true ||
                    (snapshot == null && (hasSendOrOkButton(node) || hasDismissButton(node)))
                ))
    }

    private fun hasEditableField(node: AccessibilityNodeInfo): Boolean {
        if (isTextEntryNode(node)) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasEditableField(c); c.recycle()
            if (found) return true
        }
        return false
    }

    private fun hasSendOrOkButton(node: AccessibilityNodeInfo): Boolean {
        val t = normalizeActionLabel(node.text?.toString())
        val d = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        if (t in SEND_BUTTON_LABELS || d in SEND_BUTTON_LABELS || SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasSendOrOkButton(c); c.recycle()
            if (found) return true
        }
        return false
    }

    private fun hasDismissButton(node: AccessibilityNodeInfo): Boolean {
        val t = normalizeActionLabel(node.text?.toString())
        val d = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        if (t in DISMISS_BUTTON_LABELS || d in DISMISS_BUTTON_LABELS || DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = hasDismissButton(c); c.recycle()
            if (found) return true
        }
        return false
    }

    private fun findEditableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        mutableListOf<AccessibilityNodeInfo>().let { candidates ->
            collectTextEntryCandidates(node, candidates)
            val best = candidates.maxByOrNull { scoreTextEntryCandidate(it) }
                ?.let { AccessibilityNodeInfo.obtain(it) }
            candidates.forEach { it.recycle() }
            best
        }

    private fun findEditableFieldForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String
    ): AccessibilityNodeInfo? =
        mutableListOf<AccessibilityNodeInfo>().let { candidates ->
            collectTextEntryCandidates(node, candidates)
            val best = candidates.maxByOrNull { scoreTextEntryCandidateForStep(it, step, dialogText) }
                ?.let { AccessibilityNodeInfo.obtain(it) }
            candidates.forEach { it.recycle() }
            best
        }

    private fun collectTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isTextEntryNode(node) || isLooseInputCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTextEntryCandidates(child, into)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun scoreTextEntryCandidate(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        var score = 0
        if (try { node.isEditable } catch (_: Exception) { false }) score += 500
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 320
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 140
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION)) score += 80
        if (EDITABLE_CLASS_HINTS.any { className.equals(it, ignoreCase = true) || className.contains(it, ignoreCase = true) }) score += 300
        if (className.contains("EditText", ignoreCase = true)) score += 240
        if (className.contains("Text", ignoreCase = true)) score += 90
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 180
        if (INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) || hint.contains(it) }) score += 120
        if (isLooseInputCandidate(node)) score += 110
        if (try { node.isFocused } catch (_: Exception) { false }) score += 90
        if (try { node.isFocusable } catch (_: Exception) { false }) score += 70
        if (try { node.isClickable } catch (_: Exception) { false }) score += 40
        if (bounds.right > 0 || bounds.bottom > 0) {
            score += bounds.bottom / 24
            score += bounds.right / 36
        }
        return score
    }

    private fun scoreTextEntryCandidateForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String
    ): Int {
        var score = scoreTextEntryCandidate(node)
        if (step != "INPUT_PHONE") return score

        val lowerDialog = dialogText.lowercase()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val combined = listOf(viewId, label, desc, hint).joinToString(" ")
        if (PHONE_INPUT_HINTS.any { combined.contains(it) }) score += 260
        if (combined.contains("phone") || combined.contains("mobile") || combined.contains("msisdn")) score += 180
        if (combined.contains("recipient") || combined.contains("customer") || combined.contains("subscriber")) score += 140
        if (dialogSuggestsPhoneInput(lowerDialog) && INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 90
        return score
    }

    private fun findButtonExact(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (t == normalizeActionLabel(text) || d == normalizeActionLabel(text))
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonExact(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun findButtonContaining(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val normalizedText = normalizeActionLabel(text)
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if ((t.contains(normalizedText) || d.contains(normalizedText)) && normalizedText.length > 2)
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonContaining(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun findButtonStartingWith(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        try {
            val normalizedText = normalizeActionLabel(text)
            val t = normalizeActionLabel(node.text?.toString())
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (t.startsWith(normalizedText) || d.startsWith(normalizedText))
                return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonStartingWith(child, text); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun findButtonByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        try {
            val d = normalizeActionLabel(node.contentDescription?.toString())
            if (d == normalizeActionLabel(desc)) return obtainActionTarget(node)
        } catch (_: Exception) { return null }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            val r = findButtonByDescription(child, desc); child.recycle()
            if (r != null) return r
        }
        return null
    }

    private fun extractAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            node.text?.let { sb.append(it).append(' ') }
            node.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                sb.append(extractAllText(child)); child.recycle()
            }
        } catch (_: Exception) {}
        return sb.toString()
    }

    private class TreeScanAccumulator {
        val textTokens = mutableListOf<String>()
        var hasEditableField = false
        var hasSendButton = false
        var hasDismissButton = false
    }

    private fun captureTreeSnapshot(root: AccessibilityNodeInfo): UssdTreeSnapshot {
        val accumulator = TreeScanAccumulator()
        collectTreeSnapshot(root, accumulator)
        val dialogText = normalizeCollapsedText(accumulator.textTokens.joinToString(" "))
        return UssdTreeSnapshot(
            dialogText = dialogText,
            normalizedDialogText = dialogText,
            textTokens = accumulator.textTokens.toList(),
            hasEditableField = accumulator.hasEditableField,
            hasSendButton = accumulator.hasSendButton,
            hasDismissButton = accumulator.hasDismissButton
        )
    }

    private fun collectTreeSnapshot(node: AccessibilityNodeInfo, accumulator: TreeScanAccumulator) {
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { accumulator.textTokens += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { accumulator.textTokens += it }
            if (!accumulator.hasEditableField && isTextEntryNode(node)) accumulator.hasEditableField = true
            if (!accumulator.hasSendButton && isSendActionNode(node)) accumulator.hasSendButton = true
            if (!accumulator.hasDismissButton && isDismissActionNode(node)) accumulator.hasDismissButton = true
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTreeSnapshot(child, accumulator)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun findActionButton(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        labels.forEach { label ->
            safeFind(root) { findButtonExact(it, label) }?.let { return it }
        }
        labels.forEach { label ->
            safeFind(root) { findButtonStartingWith(it, label) }?.let { return it }
        }
        labels.filter { it.length > 2 }.forEach { label ->
            safeFind(root) { findButtonContaining(it, label) }?.let { return it }
        }
        labels.forEach { label ->
            safeFind(root) { findButtonByDescription(it, label) }?.let { return it }
        }
        return null
    }

    private fun locateMenuButton(
        root: AccessibilityNodeInfo,
        valueToEnter: String,
        selectedMenuLabel: String?
    ): AccessibilityNodeInfo? =
        safeFind(root) { findButtonExact(it, valueToEnter) }
            ?: safeFind(root) { findButtonContaining(it, valueToEnter) }
            ?: safeFind(root) { findButtonStartingWith(it, valueToEnter) }
            ?: selectedMenuLabel?.let { label ->
                safeFind(root) { findButtonExact(it, label) }
                    ?: safeFind(root) { findButtonContaining(it, label) }
                    ?: safeFind(root) { findButtonStartingWith(it, label) }
            }
            ?: findActionButtonByViewIdHints(root, SEND_VIEW_ID_HINTS, DISMISS_VIEW_ID_HINTS)

    private fun findBestSendActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .maxByOrNull { scoreSendActionCandidate(it) }
            ?.takeIf { scoreSendActionCandidate(it) > 0 }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun findActionButtonByViewIdHints(
        root: AccessibilityNodeInfo,
        hints: List<String>,
        blockedHints: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filter { node ->
                val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
                viewId.isNotBlank() &&
                    hints.any { viewId.contains(it) } &&
                    blockedHints.none { viewId.contains(it) }
            }
            .maxByOrNull { scoreActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun findPositiveDialogButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filterNot { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
            }
            .maxByOrNull { scoreActionCandidate(it) }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun collectActionCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isActionCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectActionCandidates(child, into)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun isActionCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val isButtonLike = className.contains("Button", ignoreCase = true) ||
            className.contains("TextView", ignoreCase = true) ||
            className.contains("ImageView", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true) ||
            className.contains("View", ignoreCase = true)
        val actionable = try { node.isClickable || node.isFocusable } catch (_: Exception) { false }
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val hasActionHints = SEND_VIEW_ID_HINTS.any { viewId.contains(it) } ||
            DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
        val hasUsefulLabel = label.isNotBlank() || desc.isNotBlank()
        return !editable && enabled && visible && actionable && (isButtonLike || hasActionHints || hasUsefulLabel)
    }

    private fun scoreActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        val shortText = (label.ifBlank { desc }).length

        var score = 0
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 500
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 300
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 220
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 260
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 180
        if (label.isNotBlank() || desc.isNotBlank()) score += 80
        if (childCount == 0) score += 70
        if (childCount > 2) score -= childCount * 40
        if (shortText in 1..18) score += 60
        if (shortText > 28) score -= 180
        if (bounds.right > 0 || bounds.bottom > 0) {
            score += bounds.right / 10
            score += bounds.bottom / 20
        }
        return score
    }

    private fun scoreSendActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        var score = scoreActionCandidate(node)
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score += 420
        if (SEND_BUTTON_LABELS.any { label.startsWith(it) || desc.startsWith(it) }) score += 240
        if (SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) }) score += 140
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 260
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 420
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 280
        return score
    }

    private fun findBottomRightActionButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filterNot { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
            }
            .maxByOrNull { scoreActionCandidate(it) + 120 }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun triggerInputSubmit(root: AccessibilityNodeInfo, expectedValue: String): Boolean =
        triggerInputSubmit(root, expectedValue, null)

    private fun triggerInputSubmit(
        root: AccessibilityNodeInfo,
        expectedValue: String,
        existingField: AccessibilityNodeInfo?
    ): Boolean {
        val field = existingField ?: findEditableField(root) ?: return false
        return try {
            val currentValue = readFieldText(field)?.trim().orEmpty()
            if (currentValue.isNotEmpty() &&
                !isVerifiedFieldValue(currentValue, expectedValue)
            ) return false
            if (currentValue.isNotEmpty() && isVerifiedFieldValue(currentValue, expectedValue)) {
                rememberVerifiedInput(expectedValue)
            }
            performImeAction(field)
        } finally {
            if (existingField == null) field.recycle()
        }
    }

    private fun tryImmediateVerifiedSend(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String
    ): Boolean {
        val verified = verifyExpectedInputFromRoot(
            root = root,
            expectedValue = expectedValue,
            existingField = field
        ) || hasRecentVerifiedInput(expectedValue)
        if (!verified) return false
        val fieldText = field?.let(::readFieldText)
        val sendBtn = findBestSendActionButton(root)
            ?: findPositiveDialogButton(root)
            ?: findBottomRightActionButton(root)
        try {
            if (sendBtn != null && performClick(sendBtn)) {
                return true
            }
        } finally {
            sendBtn?.recycle()
        }
        val canSubmitFromField = fieldText != null || hasRecentVerifiedInput(expectedValue)
        return canSubmitFromField && triggerInputSubmit(root, expectedValue, field)
    }

    private fun performImeAction(node: AccessibilityNodeInfo): Boolean {
        val targets = obtainInputTargets(node)
        try {
            targets.forEach { target ->
                focusInputTarget(target)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val imeEnter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    if (supportsAction(target, imeEnter) && runCatching {
                            target.performAction(imeEnter)
                        }.getOrDefault(false)
                    ) {
                        return true
                    }
                }
                val actionMatch = try {
                    target.actionList?.firstOrNull { action ->
                        val label = normalizeActionLabel(action.label?.toString())
                        label in SEND_BUTTON_LABELS || label in listOf("go", "done", "enter", "search", "next")
                    }?.id
                } catch (_: Exception) {
                    null
                }
                if (actionMatch != null && runCatching { target.performAction(actionMatch) }.getOrDefault(false)) {
                    return true
                }
            }
            return false
        } finally {
            targets.forEach { it.recycle() }
        }
    }

    private fun normalizeActionLabel(value: String?): String =
        value.orEmpty()
            .trim()
            .lowercase()
            .replace(MULTI_SPACE_REGEX, " ")

    private fun isSendActionNode(node: AccessibilityNodeInfo): Boolean {
        val text = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return text in SEND_BUTTON_LABELS ||
            desc in SEND_BUTTON_LABELS ||
            SEND_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun isDismissActionNode(node: AccessibilityNodeInfo): Boolean {
        val text = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return text in DISMISS_BUTTON_LABELS ||
            desc in DISMISS_BUTTON_LABELS ||
            DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }
    }

    private fun shouldTreatStepAsTextInput(step: String, valueToEnter: String, selectedMenuLabel: String?): Boolean {
        if (step == "INPUT_PHONE") return true
        if (valueToEnter.isBlank()) return false
        if (!valueToEnter.all(Char::isDigit)) return true
        if (valueToEnter.length >= 4) return true
        return selectedMenuLabel == null && valueToEnter.length > 1
    }

    private fun dialogSuggestsTextInput(allTextLower: String): Boolean =
        allTextLower.contains("enter") ||
            allTextLower.contains("input") ||
            allTextLower.contains("reply") ||
            allTextLower.contains("amount") ||
            allTextLower.contains("pin") ||
            allTextLower.contains("phone") ||
            allTextLower.contains("number") ||
            allTextLower.contains("code")

    private fun dialogSuggestsPhoneInput(allTextLower: String): Boolean =
        PHONE_INPUT_HINTS.any { allTextLower.contains(it) } ||
            (allTextLower.contains("254") && (allTextLower.contains("phone") || allTextLower.contains("mobile"))) ||
            (allTextLower.contains("07") && (allTextLower.contains("phone") || allTextLower.contains("number")))

    private fun isTextEntryNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        val focusable = try { node.isFocusable || node.isFocused } catch (_: Exception) { false }
        val clickable = try { node.isClickable } catch (_: Exception) { false }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            (className.contains("Text", ignoreCase = true) &&
                (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) || supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)))
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val hasInputHints = INPUT_FIELD_HINTS.any {
            label.contains(it) || desc.contains(it) || hint.contains(it)
        } || INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }
        val hasSetTextAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        val hasPasteAction = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val sendOrDismissLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        return enabled && visible && !looksLikeButton && !sendOrDismissLabel && (
            editable ||
                looksLikeInputClass ||
                hasSetTextAction ||
                hasPasteAction ||
                (hasInputHints && (focusable || clickable))
            )
    }

    private fun isLooseInputCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val focusable = try { node.isFocusable || node.isFocused } catch (_: Exception) { false }
        val clickable = try { node.isClickable || node.isLongClickable } catch (_: Exception) { false }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val hasWritableAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_CUT)
        val hasInputHints = INPUT_FIELD_HINTS.any {
            label.contains(it) || desc.contains(it) || hint.contains(it)
        } || INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }
        val hasShortText = label.isBlank() || label.length <= 24
        return enabled &&
            visible &&
            !editable &&
            !looksLikeButton &&
            hasShortText &&
            (hasWritableAction || (hasInputHints && (focusable || clickable)))
    }

    private fun supportsAction(node: AccessibilityNodeInfo, actionId: Int): Boolean =
        try {
            node.actionList?.any { it.id == actionId } == true
        } catch (_: Exception) {
            false
        }

    private fun normalizeInputValue(value: String?): String =
        value.orEmpty()
            .replace(MULTI_SPACE_REGEX, "")
            .trim()

    private fun normalizePhoneComparable(value: String?): String {
        val digits = value.orEmpty().replace(Regex("\\D+"), "")
        if (digits.length < 9) return ""
        return UssdHelper.normalizeRecipientForUssdInput(digits).replace(Regex("\\D+"), "")
    }

    private fun normalizeCollapsedText(value: String?): String =
        value.orEmpty()
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()

    private fun matchesExpectedInput(actualValue: String?, expectedValue: String): Boolean {
        val actual = normalizeInputValue(actualValue)
        val expected = normalizeInputValue(expectedValue)
        if (actual.isBlank() || expected.isBlank()) return false
        if (actual == expected || actual.endsWith(expected) || expected.endsWith(actual)) return true

        val actualPhone = normalizePhoneComparable(actualValue)
        val expectedPhone = normalizePhoneComparable(expectedValue)
        if (actualPhone.isNotBlank() && expectedPhone.isNotBlank()) {
            return actualPhone == expectedPhone ||
                actualPhone.endsWith(expectedPhone) ||
                expectedPhone.endsWith(actualPhone)
        }
        return false
    }

    private fun isLikelyPromptText(value: String): Boolean {
        val normalized = normalizeActionLabel(value)
        if (normalized.isBlank()) return false
        if (value.contains('\n')) return true
        if (Regex("""\b\d+\s*[\)\].:\-]\s+\S+""").containsMatchIn(value)) return true
        if (normalized.length > 18 && USSD_HINTS.any { normalized.contains(it) }) return true
        return false
    }

    private fun rememberInputWrite(value: String) {
        lastInputWriteValue = normalizeInputValue(value)
        lastInputWriteElapsed = SystemClock.elapsedRealtime()
    }

    private fun clearInputWriteMarker() {
        lastInputWriteValue = ""
        lastInputWriteElapsed = 0L
        lastVerifiedInputValue = ""
        lastVerifiedInputElapsed = 0L
    }

    private fun hasRecentExpectedInput(expectedValue: String): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank()) return false
        if (lastInputWriteValue != expected) return false
        return SystemClock.elapsedRealtime() - lastInputWriteElapsed <= RECENT_INPUT_GRACE_MS
    }

    private fun rememberVerifiedInput(value: String) {
        val normalized = normalizeInputValue(value)
        if (normalized.isBlank()) return
        lastVerifiedInputValue = normalized
        lastVerifiedInputElapsed = SystemClock.elapsedRealtime()
    }

    private fun hasRecentVerifiedInput(expectedValue: String): Boolean {
        val expected = normalizeInputValue(expectedValue)
        if (expected.isBlank()) return false
        if (lastVerifiedInputValue != expected) return false
        return SystemClock.elapsedRealtime() - lastVerifiedInputElapsed <= RECENT_VERIFIED_INPUT_GRACE_MS
    }

    private fun shouldTrustRecentWrite(fieldText: String?, expectedValue: String): Boolean {
        val actual = fieldText?.trim().orEmpty()
        if (actual.isBlank()) return hasRecentExpectedInput(expectedValue)
        return hasRecentExpectedInput(expectedValue) && isLikelyPromptText(actual)
    }

    private fun isVerifiedFieldValue(fieldText: String?, expectedValue: String): Boolean {
        val actual = fieldText?.trim().orEmpty()
        return when {
            matchesExpectedInput(actual, expectedValue) -> true
            actual.isNotBlank() && shouldTrustRecentWrite(actual, expectedValue) -> true
            else -> false
        }
    }

    private fun verifyExpectedInputFromRoot(
        root: AccessibilityNodeInfo,
        expectedValue: String,
        existingField: AccessibilityNodeInfo? = null
    ): Boolean {
        if (existingField != null && isVerifiedFieldValue(readFieldText(existingField), expectedValue)) {
            rememberVerifiedInput(expectedValue)
            return true
        }
        var rescannedField: AccessibilityNodeInfo? = null
        return try {
            rescannedField = findEditableField(root)
            val verified = rescannedField != null &&
                isVerifiedFieldValue(readFieldText(rescannedField), expectedValue)
            if (verified) rememberVerifiedInput(expectedValue)
            verified
        } finally {
            if (rescannedField !== existingField) {
                runCatching { rescannedField?.recycle() }
            }
        }
    }

    private fun readFieldText(node: AccessibilityNodeInfo): String? {
        val directText = try { node.text?.toString() } catch (_: Exception) { null }
        if (!directText.isNullOrBlank()) return directText
        val descText = try { node.contentDescription?.toString() } catch (_: Exception) { null }
        if (!descText.isNullOrBlank()) return descText
        val nestedText = extractTextTokens(node)
            .filterNot { token -> isLikelyPromptText(token) }
            .minByOrNull { token -> token.length }
            ?.takeIf { token ->
                val normalized = normalizeActionLabel(token)
                normalized.isNotBlank() && INPUT_FIELD_HINTS.none { normalized.contains(it) }
            }
        if (!nestedText.isNullOrBlank()) return nestedText
        return null
    }

    private fun tryWriteValueToField(field: AccessibilityNodeInfo, value: String): Boolean {
        val targets = obtainInputTargets(field)
        try {
            targets.forEach { target ->
                if (writeValueUsingStrategies(target, value)) {
                    rememberInputWrite(value)
                    return true
                }
            }
            return false
        } finally {
            targets.forEach { it.recycle() }
        }
    }

    private fun writeValueUsingStrategies(node: AccessibilityNodeInfo, value: String): Boolean {
        focusInputTarget(node)
        if (setTextOnNode(node, value)) return true
        if (pasteTextIntoNode(node, value)) return true
        if (performTapGesture(node)) {
            if (setTextOnNode(node, value)) return true
            if (pasteTextIntoNode(node, value)) return true
        }
        return false
    }

    private fun obtainInputTargets(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        collectPreferredInputTargets(node, targets, 0)
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < INPUT_TARGET_DEPTH) {
            targets += current
            current = try { current.parent?.let { AccessibilityNodeInfo.obtain(it) } } catch (_: Exception) { null }
            depth++
        }
        return targets
    }

    private fun collectPreferredInputTargets(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 2) return
        try {
            val supportsDirectInput = isTextEntryNode(node) ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
                supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
            if (supportsDirectInput) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun focusInputTarget(node: AccessibilityNodeInfo) {
        val alreadyFocused = try { node.isFocused } catch (_: Exception) { false }
        if (alreadyFocused) return
        val focusReady = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }.getOrDefault(false) ||
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false) ||
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) ||
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SELECT) }.getOrDefault(false)
        if (!focusReady) {
            runCatching { performTapGesture(node) }
        }
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, value: String): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })
        }
        val replaced = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }.getOrDefault(false)
        if (replaced) moveCursorToEnd(node, value)
        return replaced
    }

    private fun pasteTextIntoNode(node: AccessibilityNodeInfo, value: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val originalClip = runCatching { clipboard.primaryClip }.getOrNull()
        return try {
            clearNodeText(node)
            clipboard.setPrimaryClip(ClipData.newPlainText("ussd_input", value))
            selectAllNodeText(node)
            val pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
            if (pasted) moveCursorToEnd(node, value)
            pasted
        } finally {
            runCatching {
                if (originalClip != null) clipboard.setPrimaryClip(originalClip)
                else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }

    private fun clearNodeText(node: AccessibilityNodeInfo) {
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                })
            }
            return
        }
        selectAllNodeText(node)
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CUT) }
    }

    private fun selectAllNodeText(node: AccessibilityNodeInfo) {
        val length = readFieldText(node)?.length ?: 0
        if (length < 0) return
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
            )
        }
    }

    private fun moveCursorToEnd(node: AccessibilityNodeInfo, value: String) {
        val length = value.length
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                }
            )
        }
    }

    private fun obtainActionTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val clickable = try {
                current.isClickable || current.isFocusable
            } catch (_: Exception) {
                false
            }
            if (clickable) return current
            val parent = try { current.parent } catch (_: Exception) { null }
            current.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < 6) {
            val activeNode = current
            runCatching { activeNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }
            runCatching { activeNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            val clicked = try {
                activeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    activeNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            } catch (_: Exception) {
                false
            }
            if (clicked) {
                activeNode.recycle()
                return true
            }
            if (performTapGesture(activeNode)) {
                activeNode.recycle()
                return true
            }
            val parent = try { activeNode.parent } catch (_: Exception) { null }
            activeNode.recycle()
            current = parent
            depth++
        }
        current?.recycle()
        return false
    }

    private fun performTapGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val bounds = Rect()
        if (!runCatching { node.getBoundsInScreen(bounds) }.isSuccess) return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        if (x <= 0f || y <= 0f) return false

        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_GESTURE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        return dispatched
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "USSD Automation", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            piFlags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bingwa Mobile")
            .setContentText("USSD automation active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
