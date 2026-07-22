package com.bingwa.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
class UssdNavigationService : AccessibilityService() {

    companion object {
        const val TAG = "UssdNavigation"
        @Volatile
        private var activeInstance: UssdNavigationService? = null
        @Volatile
        private var pendingAdvancedArm: Boolean = false

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
        var retryWindowStartedAt    : Long         = 0L
        var lastRedialElapsed       : Long         = 0L
        var signatureGuardEnabled   : Boolean      = false
        var signatureAction         : String       = "STOP"
        var signatureLearningMode   : Boolean      = false
        var loadedSignatureSteps    : List<UssdSignatureStep> = emptyList()

        var onDispatchComplete      : ((result: AdvancedDispatchResult) -> Unit)? = null
        @Volatile
        private var foregroundUiActive: Boolean = false
        @Volatile
        private var foregroundUiUntilElapsed: Long = 0L
        @Volatile
        private var keepAppUiVisibleEnabled: Boolean = true
        @Volatile
        private var uiReturnSuppressed: Boolean = false

        private const val MAX_RETRY_WINDOW_MS    = 60_000L
        private const val SHOW_RUNNING_OVERLAY   = false
        private const val STEP_DELAY_MS          = 20L
        private const val EVENT_HOT_POLL_MS      = 2L
        private const val ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS = 12L
        private const val DUPLICATE_EVENT_WINDOW_MS = 24L
        private const val FAST_VERIFY_POLL_MS    = 2L
        private const val HOT_SEND_RETRY_DELAY_MS = 3L
        private const val SEND_RETRY_DELAY_MS    = 4L
        private const val POST_WRITE_VERIFY_POLL_MS = 1L
        private const val POST_WRITE_SEND_RETRY_MS = 2L
        private const val STEP_TIMEOUT_MS           = 4_500L
        private const val STARTUP_STEP_TIMEOUT_MS   = 7_000L
        private const val FINAL_RESPONSE_TIMEOUT_MS = 6_500L
        private const val PENDING_STEP_TIMEOUT_MS   = 6_000L
        private const val PENDING_ADVANCE_TIMEOUT_MS = 3_000L
        private const val ROOT_REACQUIRE_TIMEOUT_MS  = 5_000L
        private const val PENDING_STEP_ADVANCE_TIMEOUT_MS = 3_500L
        private const val NETWORK_DELAY_STEP_TIMEOUT_MS = 16_000L
        private const val NETWORK_DELAY_FINAL_RESPONSE_TIMEOUT_MS = 18_000L
        private const val NETWORK_DELAY_PENDING_STEP_TIMEOUT_MS = 16_000L
        private const val NETWORK_DELAY_PENDING_ADVANCE_TIMEOUT_MS = 15_000L
        private const val NETWORK_DELAY_ROOT_REACQUIRE_TIMEOUT_MS = 15_000L
        private const val NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS = 15_000L
        private const val NETWORK_DELAY_ACTION_GRACE_MS = 18_000L
        private const val PENDING_STEP_ADVANCE_KICK_MS = 4L
        private const val VERIFY_POLL_MS         = 4L
        private const val RAPID_POST_POPUP_POLL_MS = 2L
        private const val RAPID_POST_POPUP_VERIFY_MS = 1L
        private const val RAPID_POST_POPUP_SEND_RETRY_MS = 2L
        private const val MAX_VERIFY_ATTEMPTS    = 10
        private const val MAX_SEND_ATTEMPTS      = 5
        private const val FORCEFUL_WRITE_PASSES  = 6
        private const val WRITE_VERIFICATION_PASSES = 5
        private const val WRITE_VERIFICATION_SETTLE_MS = 18L
        private const val DIRECT_WRITE_VERIFY_PASSES = 3
        private const val SET_TEXT_BURST_ATTEMPTS = 3
        private const val PASTE_BURST_ATTEMPTS = 3
        private const val NO_FIELD_PATIENCE      = 4
        private const val INPUT_TARGET_DEPTH     = 8
        private const val INPUT_DESCENT_DEPTH    = 4
        private const val INPUT_NEARBY_SCOPE_DEPTH = 2
        private const val RECENT_INPUT_GRACE_MS  = 4_000L
        private const val RECENT_VERIFIED_INPUT_GRACE_MS = 6_500L
        private const val RECENT_UI_EVENT_GRACE_MS = 1_200L
        private const val RECENT_USSD_CONTEXT_WINDOW_MS = 220L
        private const val GESTURE_SETTLE_MS      = 3L
        private const val POST_GESTURE_WAIT_MS   = 2L
        private const val POPUP_STABILITY_DELAY_MS = 2L
        private const val TAP_GESTURE_DURATION_MS = 10L
        private const val REDIAL_COOLDOWN_MS     = 200L
        private const val PENDING_ADVANCE_KICK_MS = 4L
        private const val ROOT_REACQUIRE_RETRY_DELAY_MS = 6L
        private const val DIALOG_DISMISS_SETTLE_MS = 20L
        private const val UI_KEEP_VISIBLE_INTERVAL_MS = 500L
        private const val STARTUP_UI_KEEP_VISIBLE_MS = 8_000L
        private const val STEP_TRANSITION_GUARD_MS = 240L
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
        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher"
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
        private val AMOUNT_INPUT_HINTS = listOf(
            "amount", "ksh", "kes", "price", "cost", "value", "total", "airtime amount", "bundle amount"
        )
        private val PIN_INPUT_HINTS = listOf(
            "pin", "m-pin", "mpin", "password", "passcode", "secret", "security code"
        )
        private val ACCOUNT_INPUT_HINTS = listOf(
            "account", "account number", "id", "identity", "meter", "meter number", "reference", "ref"
        )
        private val CODE_INPUT_HINTS = listOf(
            "code", "voucher", "token", "otp", "confirmation code", "promo", "activation code"
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
        private val TRANSIENT_RESPONSE_HINTS = listOf(
            "ussd running", "running", "processing", "please wait", "wait", "loading",
            "requesting", "sending", "fetching", "working", "in progress"
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

        fun refreshRunningOverlay() {
            activeInstance?.let { instance ->
                instance.handler.post { instance.updateRunningOverlay() }
            }
        }

        fun beginAdvancedSessionMonitoring() {
            activeInstance?.let { instance ->
                pendingAdvancedArm = false
                instance.handler.post { instance.handleAdvancedSessionArmed() }
            } ?: run {
                pendingAdvancedArm = true
            }
        }

        fun armForegroundUi(timeoutMs: Long = 120_000L) {
            foregroundUiActive = true
            foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + timeoutMs
            refreshRunningOverlay()
        }

        fun disarmForegroundUi() {
            if (!foregroundUiActive) return
            foregroundUiActive = false
            foregroundUiUntilElapsed = 0L
            refreshRunningOverlay()
        }

        fun configureUiReturn(keepVisible: Boolean) {
            keepAppUiVisibleEnabled = keepVisible
            uiReturnSuppressed = false
            refreshRunningOverlay()
        }

        fun suppressUiReturn() {
            uiReturnSuppressed = true
            refreshRunningOverlay()
        }

        fun onAppUiForegrounded() {
            if (uiReturnSuppressed) {
                uiReturnSuppressed = false
                refreshRunningOverlay()
            }
        }

        fun isBusyForBalanceCheck(): Boolean =
            advancedActive ||
                advancedInProgress ||
                signatureLearningMode ||
                tokenPurchaseCallback != null ||
                isForegroundUiActive()

        private fun refreshForegroundUi(timeoutMs: Long = 35_000L) {
            if (!foregroundUiActive) return
            foregroundUiUntilElapsed = SystemClock.elapsedRealtime() + timeoutMs
        }

        private fun isForegroundUiActive(): Boolean =
            foregroundUiActive && SystemClock.elapsedRealtime() < foregroundUiUntilElapsed
    }

    private val handler            = Handler(Looper.getMainLooper())
    private lateinit var bgHandler: Handler
    private lateinit var bgThread: HandlerThread
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
    private var hasSeenForegroundPopup = false
    private var lastMenuSignatureKey = ""
    private var lastMenuSignature: ParsedMenuSignature? = null
    private var loadedSignatureLookupSource: List<UssdSignatureStep> = emptyList()
    private var loadedSignatureLookup: Map<Int, LearnedSignatureContext> = emptyMap()
    private var lastScreenSignatureKey = ""
    private var pendingExpectedValue: String? = null
    private var pendingPhase: PendingPhase = PendingPhase.NONE
    private var pendingAdvanceFromKey: String = ""
    private var pendingSinceElapsed: Long = 0L
    private var pendingAttempts: Int = 0
    private var lastStepActionKey: String = ""
    private var lastStepActionElapsed: Long = 0L
    private var lastUiReturnElapsed: Long = 0L
    private var pendingStepAdvanceFromKey: String = ""
    private var pendingStepAdvanceSinceElapsed: Long = 0L
    private var pendingStepAdvanceTimeoutRunnable: Runnable? = null
    private var pendingStepAdvanceKickRunnable: Runnable? = null
    private var pendingAdvanceKickRunnable: Runnable? = null
    private var uiKeepVisibleRunnable: Runnable? = null
    private var waitingForRootSinceElapsed: Long = 0L
    private var lastEventFingerprint = ""
    private var lastEventElapsed = 0L
    private var recentUssdRoot: AccessibilityNodeInfo? = null
    private var recentUssdSnapshot: UssdTreeSnapshot? = null
    private var recentUssdWindowId = -1
    private var recentUssdWindowPkg = ""
    private var recentUssdDialogText = ""
    private var recentUssdStrictDialog = false
    private var recentUssdCapturedElapsed = 0L
    private var windowManager: WindowManager? = null
    private var runningOverlayView: View? = null
    private var runningOverlayStatusText: TextView? = null
    private var runningOverlayDetailText: TextView? = null
    private val useRelaxedAccessibilityTimings: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRamDevice = activityManager?.isLowRamDevice == true
        isLowRamDevice || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }
    private val useAggressiveVerifiedPopupFastPath: Boolean
        get() = !useRelaxedAccessibilityTimings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    private enum class PendingPhase { NONE, WAIT_VERIFY, WAIT_SEND }

    private data class InputWriteResult(
        val wroteValue: Boolean,
        val likelyVerified: Boolean
    )

    private data class RecentUssdContext(
        val root: AccessibilityNodeInfo,
        val snapshot: UssdTreeSnapshot?,
        val windowId: Int,
        val windowPkg: String,
        val dialogText: String,
        val strictDialog: Boolean
    )

    private fun timingForDevice(modernMs: Long, relaxedMs: Long): Long =
        if (useRelaxedAccessibilityTimings) relaxedMs else modernMs

    private val accessibilityNotificationTimeoutMs: Long
        get() = timingForDevice(ACCESSIBILITY_NOTIFICATION_TIMEOUT_MS, 24L)

    private val duplicateEventWindowMs: Long
        get() = timingForDevice(DUPLICATE_EVENT_WINDOW_MS, 48L)

    private val eventHotPollMs: Long
        get() = timingForDevice(EVENT_HOT_POLL_MS, 16L)

    private val fastVerifyPollMs: Long
        get() = timingForDevice(FAST_VERIFY_POLL_MS, 20L)

    private val verifyPollMs: Long
        get() = timingForDevice(VERIFY_POLL_MS, 36L)

    private val postWriteVerifyPollMs: Long
        get() = timingForDevice(POST_WRITE_VERIFY_POLL_MS, 18L)

    private val hotSendRetryDelayMs: Long
        get() = timingForDevice(HOT_SEND_RETRY_DELAY_MS, 20L)

    private val sendRetryDelayMs: Long
        get() = timingForDevice(SEND_RETRY_DELAY_MS, 28L)

    private val postWriteSendRetryMs: Long
        get() = timingForDevice(POST_WRITE_SEND_RETRY_MS, 18L)

    private val rapidPostPopupPollMs: Long
        get() = timingForDevice(RAPID_POST_POPUP_POLL_MS, 16L)

    private val rapidPostPopupVerifyMs: Long
        get() = timingForDevice(RAPID_POST_POPUP_VERIFY_MS, 16L)

    private val rapidPostPopupSendRetryMs: Long
        get() = timingForDevice(RAPID_POST_POPUP_SEND_RETRY_MS, 20L)

    private val pendingAdvanceKickMs: Long
        get() = timingForDevice(PENDING_ADVANCE_KICK_MS, 24L)

    private val rootReacquireRetryDelayMs: Long
        get() = timingForDevice(ROOT_REACQUIRE_RETRY_DELAY_MS, 48L)

    private val sendRetryIncrementMs: Long
        get() = timingForDevice(6L, 12L)

    private val fastSendRetryIncrementMs: Long
        get() = timingForDevice(4L, 8L)

    private val maxSendRetryDelayMs: Long
        get() = timingForDevice(48L, 84L)

    private val writeVerificationSettleMs: Long
        get() = timingForDevice(WRITE_VERIFICATION_SETTLE_MS, 28L)

    private val errorKeywords = listOf(
        "connection problem", "invalid mmi", "mmi code", "network error", "invalid", "failed",
        "cancelled", "try again", "unavailable", "problem", "request timeout",
        "busy", "sim error", "not available", "service unavailable", "temporary error",
        "session expired", "not registered", "maintenance", "maintainance"
    )

    private fun handleAdvancedSessionArmed() {
        if (!advancedActive || advancedSteps.isEmpty()) return
        if (retryWindowStartedAt <= 0L) retryWindowStartedAt = SystemClock.elapsedRealtime()
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        isProcessing = false
        lastDialogText = ""
        lastFinalResponse = ""
        lastWindowPkg = ""
        lastWindowId = -1
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastRelevantEventElapsed = SystemClock.elapsedRealtime()
        pendingProcessToken = lastRelevantEventElapsed
        lastUiReturnElapsed = 0L
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        if (shouldKeepAppUiVisible()) {
            requestAppUiBehindPopup(force = true)
            startKeepingAppUiVisible()
        }
        updateRunningOverlay()
        startStepTimeout()
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        bgThread = HandlerThread("UssdNavigationBg").apply { start() }
        bgHandler = Handler(bgThread.looper)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        createNotificationChannel()
        startForegroundCompat(
            notificationId = NOTIFICATION_ID,
            notification = buildNotification(),
            foregroundServiceType = ForegroundServiceTypes.dataSync
        )
        updateRunningOverlay()
        if (pendingAdvancedArm) {
            pendingAdvancedArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
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
            // Small coalescing window trims duplicate bursts without adding perceptible latency.
            notificationTimeout = accessibilityNotificationTimeoutMs
        }
        if (pendingAdvancedArm) {
            pendingAdvancedArm = false
            handler.post { handleAdvancedSessionArmed() }
        }
    }

    override fun onInterrupt() { cleanupAdvanced(); clearCallbacks() }

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundCompat()
        runCatching { bgThread.quitSafely() }
        if (activeInstance === this) activeInstance = null
        cleanupAdvanced()
        clearCallbacks()
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
    }

    private fun updateRunningOverlay() {
        if (!shouldShowRunningOverlay()) {
            hideRunningOverlay()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return
        val wm = windowManager ?: return
        val overlay = runningOverlayView ?: run {
            val view = buildRunningOverlayView()
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(18)
            }
            val added = runCatching {
                wm.addView(view, params)
                true
            }.getOrElse {
                Log.w(TAG, "Unable to show running overlay", it)
                false
            }
            if (!added) {
                clearRunningOverlayReferences()
                return
            }
            view
        }
        runningOverlayStatusText?.text = buildRunningOverlayStatusText()
        runningOverlayDetailText?.text = buildRunningOverlayDetailText()
        overlay.visibility = View.VISIBLE
    }

    private fun hideRunningOverlay() {
        val wm = windowManager ?: return
        val overlay = runningOverlayView ?: return
        runCatching { wm.removeView(overlay) }
            .onFailure { Log.w(TAG, "Unable to remove running overlay", it) }
        clearRunningOverlayReferences()
    }

    private fun clearRunningOverlayReferences() {
        runningOverlayView = null
        runningOverlayStatusText = null
        runningOverlayDetailText = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !isForegroundUiActive()) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) return
        if (shouldSkipDuplicateEvent(event)) return
        val pkg = event.packageName?.toString() ?: ""
        if (advancedActive &&
            pkg in LAUNCHER_PACKAGES &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        ) {
            suppressUiReturn()
            stopKeepingAppUiVisible()
            updateRunningOverlay()
            return
        }
        if (foregroundUiActive &&
            !advancedActive &&
            pkg in LAUNCHER_PACKAGES &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
        ) {
            disarmForegroundUi()
            stopKeepingAppUiVisible()
            hasSeenForegroundPopup = false
            updateRunningOverlay()
            return
        }
        val allowUnknownPkg = pkg.isBlank() || pkg == "android"
        val otherAppInFront = (advancedActive || isForegroundUiActive()) &&
            !allowUnknownPkg &&
            pkg != "com.bingwa.mobile" &&
            pkg != "com.android.systemui" &&
            !isPotentialUssdPackage(pkg)
        if (otherAppInFront) {
            if (advancedActive) suppressUiReturn()
            if (isForegroundUiActive() && !advancedActive) {
                disarmForegroundUi()
                hasSeenForegroundPopup = false
            }
            stopKeepingAppUiVisible()
            updateRunningOverlay()
            return
        }
        val allowSystemUi = pkg == "com.android.systemui" &&
                (advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode || isForegroundUiActive())
        if (pkg in BLOCKED_PACKAGES && !allowSystemUi) return
        val windowId = event.windowId
        val windowChanged = windowId != lastWindowId
        lastWindowId = windowId

        val root = obtainRootFromEvent(event) ?: getUssdRoot() ?: return
        try {
            val windowPkg = root.packageName?.toString() ?: ""
            val allowBlockedWindow = windowPkg == "com.android.systemui" && shouldAllowSystemUiDialogRoot(root, windowPkg)
            if (windowPkg in BLOCKED_PACKAGES && !allowBlockedWindow) return
            lastWindowPkg = windowPkg

            val requireStrictPopupScope = shouldRequireStrictPopupScope()
            val eventDialogText = extractDialogTextFromEvent(event)
            val snapshot = if (
                eventDialogText.isBlank() ||
                advancedActive ||
                isForegroundUiActive() ||
                balanceCallback != null ||
                tokenPurchaseCallback != null
            ) {
                capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
            } else {
                null
            }
            if (requireStrictPopupScope && snapshot == null && eventDialogText.isBlank()) return
            val dialogText = snapshot?.dialogText
                ?: normalizeCollapsedText(eventDialogText)
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
            rememberRecentUssdContext(
                root = root,
                snapshot = snapshot,
                windowId = windowId,
                windowPkg = windowPkg,
                dialogText = dialogText,
                strictDialog = requireStrictPopupScope
            )

            if (advancedActive && advancedSteps.isNotEmpty()) {
                if (!hasSeenAdvancedPopup) {
                    hasSeenAdvancedPopup = true
                    updateRunningOverlay()
                    requestAppUiBehindPopup(force = true)
                    startKeepingAppUiVisible()
                } else if (windowChanged) {
                    requestAppUiBehindPopup()
                    startKeepingAppUiVisible()
                }
                cancelStepTimeout()
                lastFinalResponse = dialogText
                capturePopupTranscript(snapshot, dialogText)
                if (signatureLearningMode) {
                    val learningSnapshot = snapshot ?: return
                    val learningLower = learningSnapshot.dialogText.lowercase()
                    if (!NON_USSD_DIALOG_HINTS.any { learningLower.contains(it) } &&
                        looksLikeUssdDialog(
                            root = root,
                            snapshot = learningSnapshot,
                            allTextLower = learningLower,
                            windowPackageName = windowPkg
                        )
                    ) {
                        captureLearningDialog(learningSnapshot)
                    }
                }

                // Prevent "next-step" injections on the same dialog right after we click Send/OK.
                if (shouldWaitForStepTransition(
                        dialogText = dialogText,
                        windowChanged = windowChanged,
                        root = root,
                        snapshot = snapshot
                    )
                ) return

                if (errorKeywords.any { lower.contains(it) }) {
                    if (signatureLearningMode && currentStep >= advancedSteps.size) {
                        finishAdvancedDispatch(dialogText)
                    } else {
                        dismissErrorAndRestart()
                    }
                    return
                }
                if (currentStep >= advancedSteps.size && isTransientResponseText(lower)) return
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

            if (isForegroundUiActive()) {
                refreshForegroundUi()
                if (!hasSeenForegroundPopup) {
                    hasSeenForegroundPopup = true
                    updateRunningOverlay()
                    requestAppUiBehindPopup(force = true)
                    startKeepingAppUiVisible()
                } else if (windowChanged) {
                    requestAppUiBehindPopup()
                    startKeepingAppUiVisible()
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

    private fun shouldSkipDuplicateEvent(event: AccessibilityEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        val fingerprint = buildEventFingerprint(event)
        val isDuplicate = fingerprint.isNotBlank() &&
            fingerprint == lastEventFingerprint &&
            now - lastEventElapsed <= duplicateEventWindowMs
        lastEventFingerprint = fingerprint
        lastEventElapsed = now
        return isDuplicate
    }

    private fun buildEventFingerprint(event: AccessibilityEvent): String {
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val text = extractDialogTextFromEvent(event)
        val contentType = runCatching { event.contentChangeTypes }.getOrDefault(0)
        return "${event.eventType}|${event.windowId}|$contentType|$pkg|$cls|$text"
    }

    private fun looksLikeUssdDialogFast(allTextLower: String, windowPackageName: String): Boolean {
        val hasUssdLanguage = USSD_HINTS.any { allTextLower.contains(it) } ||
            errorKeywords.any { allTextLower.contains(it) }
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(allTextLower)
        if (windowPackageName == "android" || windowPackageName.isBlank()) {
            if (!advancedActive && !isForegroundUiActive()) return false
            return hasUssdLanguage || menuLike
        }
        val likelyUssdPackage = isPotentialUssdPackage(windowPackageName)
        if (!likelyUssdPackage && !advancedActive && !isForegroundUiActive()) return false
        if (advancedActive || isForegroundUiActive()) {
            if (hasSeenAdvancedPopup || hasSeenForegroundPopup) return true
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
                || ((advancedActive || isForegroundUiActive()) && likelyUssdPackage && (hasEditField || hasSendButton || hasDismissButton || hasMenuOptions))
    }

    private fun shouldRequireStrictPopupScope(): Boolean {
        if (signatureLearningMode) return true
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || !pendingExpectedValue.isNullOrBlank()) return true
        val step = advancedSteps.getOrNull(currentStep).orEmpty()
        return step == "INPUT_PHONE" || (step.isNotBlank() && !step.all(Char::isDigit))
    }

    private fun shouldUseEventTextFallback(): Boolean =
        !advancedActive &&
            !signatureLearningMode &&
            !isForegroundUiActive() &&
            balanceCallback == null &&
            tokenPurchaseCallback == null

    private fun capturePreferredPopupSnapshot(
        root: AccessibilityNodeInfo,
        requireStrictDialog: Boolean
    ): UssdTreeSnapshot? {
        val strictSnapshot = captureTreeSnapshotStrictDialog(root)
        if (strictSnapshot != null) return strictSnapshot
        val relaxedSnapshot = captureTreeSnapshot(root)
        if (requireStrictDialog && !shouldAllowRelaxedDialogFallback(root, relaxedSnapshot)) return null
        return relaxedSnapshot
    }

    private fun obtainInteractionRoot(
        root: AccessibilityNodeInfo,
        requireStrictDialog: Boolean
    ): AccessibilityNodeInfo? {
        findDialogCaptureRoot(root)?.let { return it }
        if (requireStrictDialog && !shouldAllowRelaxedDialogFallback(root)) return null
        return AccessibilityNodeInfo.obtain(root)
    }

    private fun shouldAllowRelaxedDialogFallback(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot? = null
    ): Boolean {
        if (!advancedActive &&
            balanceCallback == null &&
            tokenPurchaseCallback == null &&
            !signatureLearningMode &&
            !isForegroundUiActive()
        ) {
            return false
        }
        val pkg = root.packageName?.toString().orEmpty()
        val allowedPkg = pkg.isBlank() ||
            pkg == "android" ||
            pkg == "com.android.systemui" ||
            isPotentialUssdPackage(pkg)
        if (!allowedPkg) return false

        val effectiveSnapshot = snapshot ?: captureTreeSnapshot(root)
        val dialogText = effectiveSnapshot.dialogText
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false

        return looksLikeUssdDialog(root, effectiveSnapshot, lower, pkg) ||
            looksLikeUssdDialogFast(allTextLower = lower, windowPackageName = pkg) ||
            hasDialogLayout(root, effectiveSnapshot) ||
            effectiveSnapshot.hasEditableField ||
            effectiveSnapshot.hasSendButton ||
            effectiveSnapshot.hasDismissButton
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
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}|${snapshot.inputStateSignature}"
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
            "${snapshot.hasEditableField}|${snapshot.hasSendButton}|${snapshot.hasDismissButton}|${snapshot.inputStateSignature}"
        } else {
            ""
        }
        return "$windowId|$windowPkg|$cls|$flags|$normalized"
    }

    private fun processStep() {
        if (!advancedActive) { isProcessing = false; return }
        if (pendingStepAdvanceFromKey.isNotBlank()) { isProcessing = false; return }
        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val recentContext = obtainRecentUssdContext(requireStrictDialog = requireStrictPopupScope)
        val root = recentContext?.root ?: getUssdRoot() ?: run {
            if (shouldWaitForRootRecovery()) {
                isProcessing = false
                waitForRootRecovery()
            } else {
                clearRootRecoveryState()
                isProcessing = false
                handler.postDelayed({ restartFromBeginning() }, 700)
            }
            return
        }
        try {
            clearRootRecoveryState()
            val windowPkg = recentContext?.windowPkg ?: (root.packageName?.toString() ?: "")
            val effectiveSnapshot = recentContext?.snapshot ?: capturePreferredPopupSnapshot(
                root = root,
                requireStrictDialog = requireStrictPopupScope
            )
            if (effectiveSnapshot == null) {
                isProcessing = false
                pendingProcessToken = SystemClock.elapsedRealtime()
                scheduleProcessStep(dialogChanged = false)
                return
            }
            val interactionRoot = obtainInteractionRoot(
                root = root,
                requireStrictDialog = requireStrictPopupScope
            ) ?: run {
                isProcessing = false
                pendingProcessToken = SystemClock.elapsedRealtime()
                scheduleProcessStep(dialogChanged = false)
                return
            }
            try {
                val freshDialogText = effectiveSnapshot.dialogText
                val dialogText = freshDialogText.ifBlank { lastFinalResponse }
                val lower = dialogText.lowercase()
                if (shouldWaitForStepTransition(
                        dialogText = dialogText,
                        windowChanged = false,
                        root = interactionRoot,
                        snapshot = effectiveSnapshot
                    )
                ) {
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
                    if (shouldWaitForFinalResponse(effectiveSnapshot, dialogText)) {
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false, overrideDelayMs = rapidPostPopupPollMs)
                        return
                    }
                    val finalText = lastFinalResponse
                    Log.d(TAG, "All steps complete, finalText='$finalText'")
                    finishAdvancedDispatch(finalText)
                    return
                }

                val step = advancedSteps[currentStep]
                val menuSignature = parseMenuSignature(effectiveSnapshot)
                if (step != "INPUT_PHONE") {
                    captureSignatureStepIfNeeded(currentStep, step, menuSignature, effectiveSnapshot, dialogText)
                }
                val resolved = resolveStepInput(currentStep, step, menuSignature)
                if (!advancedActive) {
                    isProcessing = false
                    return
                }
                val valueToEnter = resolved.first
                val selectedMenuLabel = resolved.second

                val inputField = findEditableFieldForStep(interactionRoot, step, dialogText, valueToEnter)
                try {
                    val dialogAllowsPhoneInput = step == "INPUT_PHONE" && dialogSuggestsPhoneInput(lower)
                    if (step == "INPUT_PHONE" && inputField == null && !dialogAllowsPhoneInput) {
                        // Wait for the correct prompt/dialog instead of blindly injecting the phone number.
                        isProcessing = false
                        pendingProcessToken = SystemClock.elapsedRealtime()
                        scheduleProcessStep(dialogChanged = false)
                        return
                    }
                    val likelyTypedMenuReply = shouldTreatNumericReplyAsTextInput(
                        step = step,
                        valueToEnter = valueToEnter,
                        snapshot = effectiveSnapshot,
                        dialogTextLower = lower,
                        menuSignature = menuSignature
                    )
                    val shouldPreferTextInput = inputField != null ||
                        shouldTreatStepAsTextInput(
                            step = step,
                            valueToEnter = valueToEnter,
                            selectedMenuLabel = selectedMenuLabel
                        ) ||
                        likelyTypedMenuReply ||
                        dialogSuggestsTextInput(lower) ||
                        dialogAllowsPhoneInput
                    if (!shouldPreferTextInput &&
                        step.all(Char::isDigit) &&
                        menuSignature != null &&
                        menuSignature.options.isNotEmpty()
                    ) {
                        // Only enforce menu-option matching when this popup behaves like a menu.
                        // Typed reply prompts can still contain numbered text from the previous step.
                        if (!menuSignature.options.containsKey(valueToEnter)) {
                            isProcessing = false
                            pendingProcessToken = SystemClock.elapsedRealtime()
                            scheduleProcessStep(dialogChanged = false)
                            return
                        }
                    }
                    if (inputField == null &&
                        shouldPreferTextInput &&
                        !dialogSuggestsTextInput(lower) &&
                        !dialogAllowsPhoneInput &&
                        !likelyTypedMenuReply
                    ) {
                        if (!effectiveSnapshot.hasEditableField) {
                            val opportunisticWrite = writeValueToField(interactionRoot, valueToEnter)
                            if (!opportunisticWrite) {
                                isProcessing = false
                                pendingProcessToken = SystemClock.elapsedRealtime()
                                scheduleProcessStep(dialogChanged = false)
                                return
                            }
                        }
                    }
                    if (inputField != null || shouldPreferTextInput) {
                        val wroteValue = when {
                            inputField != null ->
                                tryWriteValueToField(inputField, valueToEnter, interactionRoot) ||
                                    writeValueToField(interactionRoot, valueToEnter)
                            else -> writeValueToField(interactionRoot, valueToEnter)
                        }
                        val inlineVerified = verifyExpectedInputFromRoot(
                            root = interactionRoot,
                            expectedValue = valueToEnter,
                            existingField = inputField
                        )
                        val trustedFreshWrite = shouldTrustFreshInputWrite(
                            wroteValue = wroteValue,
                            expectedValue = valueToEnter,
                            existingField = inputField,
                            snapshot = effectiveSnapshot,
                            dialogTextLower = lower
                        )
                        val recentVerifiedWrite = inlineVerified ||
                            hasRecentVerifiedInput(valueToEnter) ||
                            trustedFreshWrite
                        if (!isFinalSignatureLearningStep(currentStep) &&
                            wroteValue &&
                            recentVerifiedWrite &&
                            tryImmediateVerifiedSend(
                                root = interactionRoot,
                                field = inputField,
                                expectedValue = valueToEnter
                            )
                        ) {
                            markStepAction(dialogText, root = interactionRoot)
                            startPendingStepAdvance(interactionRoot, dialogText)
                            return
                        }
                        if (!isFinalSignatureLearningStep(currentStep) &&
                            wroteValue &&
                            shouldAttemptAggressiveImmediateSubmit(
                                snapshot = effectiveSnapshot,
                                dialogTextLower = lower,
                                step = step,
                                expectedValue = valueToEnter,
                                field = inputField
                            ) &&
                            tryAggressiveImmediateSubmitAfterWrite(
                                root = interactionRoot,
                                field = inputField,
                                expectedValue = valueToEnter
                            )
                        ) {
                            markStepAction(dialogText, root = interactionRoot)
                            startPendingStepAdvance(interactionRoot, dialogText)
                            return
                        }
                        // Fast path: stop polling loops. Set a pending phase and let the next accessibility
                        // event drive verification/send immediately (with a short safety kick).
                        if (isFinalSignatureLearningStep(currentStep)) {
                            // Keep legacy behavior for learning flows (we don't want to risk changing capture timing).
                            val delay = when {
                                wroteValue && hasSeenAdvancedPopup -> rapidPostPopupVerifyMs
                                wroteValue -> fastVerifyPollMs
                                else -> verifyPollMs
                            }
                            if (delay <= 0L) handler.post { verifyLearningFinalInputThenDismiss(valueToEnter, 0, 0) }
                            else handler.postDelayed({ verifyLearningFinalInputThenDismiss(valueToEnter, 0, 0) }, delay)
                        } else {
                            startPendingAdvance(
                                expectedValue = valueToEnter,
                                root = interactionRoot,
                                dialogText = dialogText,
                                snapshot = effectiveSnapshot
                            )
                        }
                        return
                    }
                } finally {
                    inputField?.recycle()
                }

                val menuBtn = locateMenuButton(interactionRoot, valueToEnter, selectedMenuLabel)

                if (menuBtn != null) {
                    val clicked = try { performClick(menuBtn) } finally { menuBtn.recycle() }
                    if (clicked) {
                        markStepAction(dialogText, root = interactionRoot, snapshot = effectiveSnapshot)
                        startPendingStepAdvance(interactionRoot, dialogText)
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
                interactionRoot.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun isFinalSignatureLearningStep(stepIndex: Int): Boolean =
        signatureLearningMode && stepIndex == advancedSteps.lastIndex

    private fun getUssdRoot(): AccessibilityNodeInfo? {
        val allowSystemWindows = advancedActive || balanceCallback != null || tokenPurchaseCallback != null || signatureLearningMode || isForegroundUiActive()
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

    private fun obtainRootFromEvent(event: AccessibilityEvent): AccessibilityNodeInfo? {
        val source = try { event.source } catch (_: Exception) { null } ?: return null
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
        var result: AccessibilityNodeInfo? = null
        var depth = 0
        try {
            while (current != null && depth < 24) {
                val parent = try { current.parent } catch (_: Exception) { null }
                if (parent == null) {
                    result = AccessibilityNodeInfo.obtain(current)
                    break
                }
                val next = try { AccessibilityNodeInfo.obtain(parent) } catch (_: Exception) { null }
                current.recycle()
                current = next
                depth++
            }
        } finally {
            current?.recycle()
            source.recycle()
        }

        val candidate = result ?: return null
        val pkg = candidate.packageName?.toString() ?: ""
        val allowBlocked = pkg !in BLOCKED_PACKAGES || shouldAllowSystemUiDialogRoot(candidate, pkg)
        val allowed = allowBlocked &&
            (isPotentialUssdPackage(pkg) ||
                shouldAllowAndroidDialogRoot(candidate, pkg) ||
                shouldAllowSystemUiDialogRoot(candidate, pkg))
        if (!allowed) {
            candidate.recycle()
            return null
        }
        return candidate
    }

    private fun shouldAllowSystemUiDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (pkg != "com.android.systemui") return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !signatureLearningMode && !isForegroundUiActive()) return false
        if (!hasDialogLayout(root)) return false
        val summary = scanNodeSummary(root)
        val dialogText = normalizeCollapsedText(summary.textTokens.joinToString(" "))
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = summary.hasSendButton || summary.hasDismissButton || summary.hasEditableField
        if (!hasAction) return false
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val startupRelaxed = advancedActive &&
            !hasSeenUssdPopup() &&
            (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= STARTUP_UI_KEEP_VISIBLE_MS
        return hasUssdLanguage || menuLike || startupRelaxed
    }

    private fun shouldAllowAndroidDialogRoot(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (!(pkg.isBlank() || pkg == "android")) return false
        if (!advancedActive && balanceCallback == null && tokenPurchaseCallback == null && !isForegroundUiActive()) return false
        if (!hasDialogLayout(root)) return false
        val summary = scanNodeSummary(root)
        val dialogText = normalizeCollapsedText(summary.textTokens.joinToString(" "))
        if (dialogText.isBlank()) return false
        val lower = dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) return false
        val hasAction = summary.hasSendButton || summary.hasDismissButton || summary.hasEditableField
        if (!hasAction) return false
        val menuLike = Regex("""\b\d+\s*[\)\].:\-]""").containsMatchIn(lower)
        val hasUssdLanguage = USSD_HINTS.any { lower.contains(it) } || errorKeywords.any { lower.contains(it) }
        val startupRelaxed = advancedActive &&
            !hasSeenUssdPopup() &&
            (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= STARTUP_UI_KEEP_VISIBLE_MS
        return hasUssdLanguage || menuLike || startupRelaxed
    }

    private data class MenuOptionDescriptor(
        val key: String,
        val label: String,
        val normalizedLabel: String,
        val tokens: Set<String>
    )

    private data class ParsedMenuSignature(
        val title: String,
        val options: LinkedHashMap<String, String>,
        val normalizedTitle: String,
        val titleTokens: Set<String>,
        val optionDescriptors: List<MenuOptionDescriptor>,
        val normalizedOptionLabels: Set<String>
    )

    private data class LearnedSignatureContext(
        val step: UssdSignatureStep,
        val normalizedSelectedLabel: String,
        val selectedLabelTokens: Set<String>,
        val normalizedMenuTitle: String,
        val menuTitleTokens: Set<String>,
        val normalizedOptionSnapshot: Set<String>
    )

    private data class UssdTreeSnapshot(
        val dialogText: String,
        val normalizedDialogText: String,
        val textTokens: List<String>,
        val hasEditableField: Boolean,
        val hasSendButton: Boolean,
        val hasDismissButton: Boolean,
        val inputStateSignature: String
    )

    private data class DialogCaptureCandidate(
        val node: AccessibilityNodeInfo,
        val score: Int
    )

    private data class StructuredMenuBlock(
        val titleLines: List<String>,
        val options: LinkedHashMap<String, String>
    )

    private data class MenuOptionMatch(
        val optionKey: String,
        val optionLabel: String,
        val autoAdjustSafe: Boolean
    )

    private data class ScoredMenuOptionMatch(
        val descriptor: MenuOptionDescriptor,
        val sharedTokenCount: Int,
        val strongSharedTokenCount: Int,
        val expectedTokenCount: Int,
        val candidateTokenCount: Int,
        val score: Double
    )

    private fun scheduleProcessStep(dialogChanged: Boolean, overrideDelayMs: Long? = null) {
        processStepRunnable?.let { handler.removeCallbacks(it) }
        val token = pendingProcessToken
        val delayMs = overrideDelayMs ?: when {
            dialogChanged && hasFreshRecentUssdContext() -> 0L
            hasSeenAdvancedPopup && dialogChanged -> rapidPostPopupPollMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupVerifyMs
            dialogChanged && hasRecentUssdUiEvent() -> eventHotPollMs
            dialogChanged -> POPUP_STABILITY_DELAY_MS
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> verifyPollMs
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
            hasRecentExpectedInput(expected) && hasRecentUssdUiEvent() -> postWriteVerifyPollMs
            hasRecentExpectedInput(expected) -> postWriteVerifyPollMs
            hasSeenAdvancedPopup && noFieldCount > 0 -> rapidPostPopupVerifyMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupVerifyMs
            hasRecentUssdUiEvent() && noFieldCount > 0 -> eventHotPollMs
            noFieldCount > 0 -> fastVerifyPollMs
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> verifyPollMs
        }

    private fun hasRecentUssdUiEvent(): Boolean =
        SystemClock.elapsedRealtime() - lastRelevantEventElapsed <= RECENT_UI_EVENT_GRACE_MS

    private fun sendRetryDelay(attempt: Int, expectedValue: String? = null): Long {
        val hasRecentWrite = expectedValue?.let { hasRecentExpectedInput(it) } == true
        val hasRecentVerification = expectedValue?.let { hasRecentVerifiedInput(it) } == true
        if (useAggressiveVerifiedPopupFastPath &&
            attempt <= 1 &&
            (hasRecentVerification || (hasRecentWrite && hasRecentUssdUiEvent()))
        ) {
            return 0L
        }
        val base = when {
            hasRecentVerification || hasRecentWrite -> postWriteSendRetryMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupSendRetryMs
            hasSeenAdvancedPopup -> hotSendRetryDelayMs
            hasRecentUssdUiEvent() -> hotSendRetryDelayMs
            else -> sendRetryDelayMs
        }
        val increment = when {
            hasRecentVerification || hasRecentWrite -> fastSendRetryIncrementMs
            hasSeenAdvancedPopup -> fastSendRetryIncrementMs
            else -> sendRetryIncrementMs
        }
        return minOf(base + (attempt.toLong() * increment), maxSendRetryDelayMs)
    }

    private fun shouldForcePendingFieldRewrite(
        expectedValue: String,
        fieldPresent: Boolean
    ): Boolean {
        if (fieldPresent) return true
        if (!hasRecentExpectedInput(expectedValue)) return true
        return shouldUseExtendedNetworkDelayWindow(expectedValue) && !hasRecentVerifiedInput(expectedValue)
    }

    private fun captureSignatureStepIfNeeded(
        stepIndex: Int,
        rawStep: String,
        menu: ParsedMenuSignature?,
        snapshot: UssdTreeSnapshot,
        dialogText: String
    ) {
        if (!signatureLearningMode || !rawStep.all(Char::isDigit) || menu == null) return
        val optionLabel = menu.options[rawStep] ?: return
        val captured = UssdSignatureStep(
            stepIndex = stepIndex,
            expectedInput = rawStep,
            menuTitle = menu.title,
            menuText = formatRecordedDialogText(snapshot.textTokens, dialogText),
            selectedOptionLabel = optionLabel,
            menuOptionsSnapshot = menu.options.values
                .map { normalizeCollapsedText(it) }
                .filter { it.isNotBlank() }
        )
        val existingIndex = learnedSignatureSteps.indexOfFirst { it.stepIndex == stepIndex }
        if (existingIndex >= 0) learnedSignatureSteps[existingIndex] = captured else learnedSignatureSteps.add(captured)
    }

    private fun recordedInputForStep(stepIndex: Int, rawStep: String): String =
        when {
            rawStep == "INPUT_PHONE" -> advancedPhoneNumber
            stepIndex >= 0 -> adjustedStepInputs[stepIndex].takeUnless { it.isNullOrBlank() } ?: rawStep
            else -> rawStep
        }

    private fun selectedOptionLabelForInput(
        enteredInput: String,
        rawStep: String,
        menu: ParsedMenuSignature?
    ): String =
        when {
            rawStep == "INPUT_PHONE" -> "Enter phone number"
            enteredInput.all(Char::isDigit) -> menu?.options?.get(enteredInput)
                ?: menu?.options?.get(rawStep)
                .orEmpty()
            else -> ""
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
        val learned = getLoadedSignatureContext(stepIndex) ?: run {
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
                learned = learned.step,
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
                learned = learned.step,
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
        updateRunningOverlay()
        cleanupAdvanced()
    }

    private fun finishAdvancedDispatch(finalText: String) {
        onDispatchComplete?.invoke(buildDispatchResult(finalText))
        if (!signatureLearningMode) tokenPurchaseCallback?.invoke(true)
        closeCurrentUssdUi()
        advancedInProgress = false
        updateRunningOverlay()
        cleanupAdvanced()
    }

    private fun buildDispatchResult(finalResponse: String): AdvancedDispatchResult {
        val sigList: List<UssdSignatureStep>
        val capList: List<UssdLearningCapture>
        val transcript: List<String>
        sigList = learnedSignatureSteps.toList()
        capList = learningCaptures.toList()
        transcript = popupTranscript.toList()
        return AdvancedDispatchResult(
            finalResponse = finalResponse,
            changeDetected = signatureChangeDetected,
            autoAdjusted = signatureAutoAdjusted,
            learningCompleted = signatureLearningMode && (sigList.isNotEmpty() || capList.isNotEmpty()),
            suggestedCode = if (signatureChangeDetected) buildSuggestedCode() else "",
            changeSummary = detectedChangeNotes.joinToString(". "),
            learnedSignature = sigList,
            learningCaptures = capList,
            popupTranscript = transcript
        )
    }

    private fun shouldRecordPopupTranscript(): Boolean =
        signatureLearningMode || signatureGuardEnabled

    private fun capturePopupTranscript(snapshot: UssdTreeSnapshot?, dialogText: String) {
        if (!shouldRecordPopupTranscript()) return
        val recordedText = snapshot?.let {
            val menu = parseMenuSignature(it)
            if (signatureLearningMode) {
                formatLearningRecordedDialogText(snapshot = it, menu = menu)
            } else {
                formatRecordedDialogText(it.textTokens, it.dialogText)
            }
        } ?: formatRecordedDialogText(emptyList(), dialogText)
        if (recordedText.isBlank()) return
        if (popupTranscript.lastOrNull() == recordedText) return
        popupTranscript += recordedText
    }

    private fun captureLearningDialog(snapshot: UssdTreeSnapshot) {
        val menu = parseMenuSignature(snapshot) ?: return
        val recordedText = formatLearningRecordedDialogText(snapshot, menu)
        if (recordedText.isBlank()) return

        val captureIndex = when {
            advancedSteps.isEmpty() -> -1
            currentStep >= advancedSteps.size -> advancedSteps.lastIndex
            else -> currentStep
        }
        val rawStep = advancedSteps.getOrNull(captureIndex).orEmpty()
        val enteredInput = recordedInputForStep(captureIndex, rawStep)
        val selectedOptionLabel = selectedOptionLabelForInput(enteredInput, rawStep, menu)
        val capture = UssdLearningCapture(
            stepIndex = captureIndex,
            enteredInput = enteredInput,
            selectedOptionLabel = selectedOptionLabel,
            popupText = recordedText
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

    private fun buildLearnedSignatureContext(step: UssdSignatureStep): LearnedSignatureContext {
        val normalizedSelectedLabel = normalizeMenuText(step.selectedOptionLabel)
        val normalizedMenuTitle = normalizeMenuText(step.menuTitle)
        return LearnedSignatureContext(
            step = step,
            normalizedSelectedLabel = normalizedSelectedLabel,
            selectedLabelTokens = tokenizeMenuLabel(normalizedSelectedLabel),
            normalizedMenuTitle = normalizedMenuTitle,
            menuTitleTokens = tokenizeMenuLabel(normalizedMenuTitle),
            normalizedOptionSnapshot = step.menuOptionsSnapshot
                .asSequence()
                .map(::normalizeMenuText)
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    private fun getLoadedSignatureContext(stepIndex: Int): LearnedSignatureContext? {
        if (loadedSignatureLookupSource !== loadedSignatureSteps) {
            loadedSignatureLookupSource = loadedSignatureSteps
            loadedSignatureLookup = loadedSignatureSteps
                .asSequence()
                .map(::buildLearnedSignatureContext)
                .associateBy { it.step.stepIndex }
        }
        return loadedSignatureLookup[stepIndex]
    }

    private fun markStepAction(
        dialogText: String,
        root: AccessibilityNodeInfo? = null,
        snapshot: UssdTreeSnapshot? = null
    ) {
        lastStepActionKey = buildDialogStateKey(
            dialogText = dialogText,
            inputStateSignature = snapshot?.inputStateSignature ?: root?.let(::captureInputStateSignature).orEmpty()
        )
        lastStepActionElapsed = SystemClock.elapsedRealtime()
    }

    private fun shouldWaitForStepTransition(
        dialogText: String,
        windowChanged: Boolean,
        root: AccessibilityNodeInfo? = null,
        snapshot: UssdTreeSnapshot? = null
    ): Boolean {
        val key = buildDialogStateKey(
            dialogText = dialogText,
            inputStateSignature = snapshot?.inputStateSignature ?: root?.let(::captureInputStateSignature).orEmpty()
        )
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

    private fun resolveWindowId(root: AccessibilityNodeInfo): Int =
        runCatching { root.windowId }.getOrDefault(lastWindowId)

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
        val normalizedTokens = normalizeDialogLines(tokens)
        val menuBlock = extractStructuredMenuBlock(normalizedTokens) ?: return null
        val options = menuBlock.options
        val title = menuBlock.titleLines
            .distinct()
            .takeLast(2)
            .joinToString(" / ")
        val normalizedTitle = normalizeMenuText(title)
        val optionDescriptors = options.entries.map { (key, label) ->
            val normalizedLabel = normalizeMenuText(label)
            MenuOptionDescriptor(
                key = key,
                label = label,
                normalizedLabel = normalizedLabel,
                tokens = tokenizeMenuLabel(normalizedLabel)
            )
        }
        return ParsedMenuSignature(
            title = title,
            options = LinkedHashMap(options),
            normalizedTitle = normalizedTitle,
            titleTokens = tokenizeMenuLabel(normalizedTitle),
            optionDescriptors = optionDescriptors,
            normalizedOptionLabels = optionDescriptors
                .asSequence()
                .map { it.normalizedLabel }
                .filter { it.isNotBlank() }
                .toSet()
        )
    }

    private fun normalizeDialogLines(tokens: List<String>): List<String> {
        val collapsed = mutableListOf<String>()
        tokens.forEach { token ->
            token.lineSequence().forEach { line ->
                val normalizedLine = normalizeCollapsedText(line)
                if (normalizedLine.isNotBlank() && collapsed.lastOrNull() != normalizedLine) {
                    collapsed += normalizedLine
                }
            }
        }
        return collapsed
    }

    private fun extractStructuredMenuBlock(lines: List<String>): StructuredMenuBlock? {
        if (lines.isEmpty()) return null
        var bestBlock: StructuredMenuBlock? = null
        for (startIndex in lines.indices) {
            val token = lines[startIndex]
            val key = parseStructuredMenuKey(token) ?: continue
            if (key != "0" && key != "1") continue
            val candidate = collectStructuredMenuBlock(lines, startIndex) ?: continue
            val currentBest = bestBlock
            if (
                currentBest == null ||
                candidate.options.size > currentBest.options.size ||
                (
                    candidate.options.size == currentBest.options.size &&
                        candidate.titleLines.size > currentBest.titleLines.size
                    )
            ) {
                bestBlock = candidate
            }
        }
        return bestBlock
    }

    private fun collectStructuredMenuBlock(
        lines: List<String>,
        startIndex: Int
    ): StructuredMenuBlock? {
        val options = linkedMapOf<String, String>()
        var pendingOptionKey: String? = null

        for (index in startIndex until lines.size) {
            val token = lines[index]
            val normalizedAction = normalizeActionLabel(token)
            if (normalizedAction in SEND_BUTTON_LABELS || normalizedAction in DISMISS_BUTTON_LABELS) {
                if (options.isNotEmpty()) break
                continue
            }

            val numberedOption = MENU_OPTION_REGEX.matchEntire(token)
            if (numberedOption != null) {
                val key = numberedOption.groupValues[1]
                if (!isNextStructuredMenuKey(options, key)) {
                    if (options.isNotEmpty()) break
                    return null
                }
                val label = normalizeCollapsedText(numberedOption.groupValues[2])
                if (label.isBlank()) break
                options[key] = label
                pendingOptionKey = null
                continue
            }

            val numberOnly = MENU_OPTION_NUMBER_ONLY_REGEX.matchEntire(token)
            if (numberOnly != null) {
                val key = numberOnly.groupValues[1]
                if (!isNextStructuredMenuKey(options, key)) {
                    if (options.isNotEmpty()) break
                    return null
                }
                pendingOptionKey = key
                continue
            }

            val deferredKey = pendingOptionKey
            if (deferredKey != null) {
                if (!looksLikeMenuLabel(token)) break
                options[deferredKey] = token
                pendingOptionKey = null
                continue
            }

            if (options.isNotEmpty()) break
        }

        if (pendingOptionKey != null) return null
        if (!looksLikeStructuredUssdMenu(LinkedHashMap(options))) return null

        val titleLines = lines
            .take(startIndex)
            .takeLast(2)
            .map(::normalizeCollapsedText)
            .filter { line ->
                line.isNotBlank() &&
                    normalizeActionLabel(line) !in SEND_BUTTON_LABELS &&
                    normalizeActionLabel(line) !in DISMISS_BUTTON_LABELS
            }

        return StructuredMenuBlock(
            titleLines = titleLines,
            options = LinkedHashMap(options)
        )
    }

    private fun parseStructuredMenuKey(token: String): String? =
        MENU_OPTION_REGEX.matchEntire(token)?.groupValues?.get(1)
            ?: MENU_OPTION_NUMBER_ONLY_REGEX.matchEntire(token)?.groupValues?.get(1)

    private fun isNextStructuredMenuKey(
        options: LinkedHashMap<String, String>,
        key: String
    ): Boolean {
        val numericKey = key.toIntOrNull() ?: return false
        if (options.isEmpty()) return numericKey == 0 || numericKey == 1
        val lastKey = options.keys.lastOrNull()?.toIntOrNull() ?: return false
        return numericKey == lastKey + 1
    }

    private fun formatRecordedDialogText(tokens: List<String>, fallbackText: String = ""): String {
        val normalizedLines = normalizeDialogLines(tokens)
        extractStructuredMenuBlock(normalizedLines)?.let { menuBlock ->
            return buildList {
                menuBlock.titleLines.forEach(::add)
                menuBlock.options.forEach { (key, label) -> add("$key. ${normalizeCollapsedText(label)}") }
            }
                .map(::normalizeCollapsedText)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("\n")
        }
        if (normalizedLines.isEmpty()) return normalizeCollapsedText(fallbackText)

        val recordedLines = mutableListOf<String>()
        var pendingOptionKey: String? = null

        for (token in normalizedLines) {
            val numberedOption = MENU_OPTION_REGEX.find(token)
            if (numberedOption != null) {
                pendingOptionKey = null
                val key = numberedOption.groupValues[1]
                val label = normalizeCollapsedText(numberedOption.groupValues[2])
                recordedLines += if (label.isBlank()) key else "$key. $label"
                continue
            }

            val numberOnly = MENU_OPTION_NUMBER_ONLY_REGEX.find(token)
            if (numberOnly != null) {
                pendingOptionKey = numberOnly.groupValues[1]
                continue
            }

            val deferredKey = pendingOptionKey
            if (deferredKey != null && looksLikeMenuLabel(token)) {
                recordedLines += "$deferredKey. $token"
                pendingOptionKey = null
                continue
            }

            if (deferredKey != null) {
                recordedLines += deferredKey
                pendingOptionKey = null
            }

            recordedLines += token
        }

        pendingOptionKey?.let(recordedLines::add)

        return recordedLines
            .map(::normalizeCollapsedText)
            .filter { it.isNotBlank() }
            .let { lines ->
                if (lines.size < 2) lines else buildList {
                    lines.forEach { line ->
                        if (lastOrNull() != line) add(line)
                    }
                }
            }
            .joinToString("\n")
    }

    private fun formatLearningRecordedDialogText(
        snapshot: UssdTreeSnapshot,
        menu: ParsedMenuSignature?
    ): String {
        if (menu == null) return ""
        extractStructuredMenuBlock(normalizeDialogLines(snapshot.textTokens))?.let { menuBlock ->
            return buildList {
                menuBlock.titleLines.forEach(::add)
                menuBlock.options.forEach { (key, label) -> add("$key. ${normalizeCollapsedText(label)}") }
            }.joinToString("\n")
        }
        val lines = buildList {
            menu.title
                .takeIf { it.isNotBlank() }
                ?.let(::normalizeCollapsedText)
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            menu.options.forEach { (key, label) ->
                val normalizedLabel = normalizeCollapsedText(label)
                if (normalizedLabel.isBlank()) add(key) else add("$key. $normalizedLabel")
            }
        }
        if (lines.isNotEmpty()) {
            return lines.joinToString("\n")
        }
        return formatRecordedDialogText(snapshot.textTokens, snapshot.dialogText)
    }

    private fun looksLikeMenuLabel(token: String): Boolean {
        val normalized = normalizeActionLabel(token)
        if (normalized.isBlank()) return false
        if (normalized in SEND_BUTTON_LABELS || normalized in DISMISS_BUTTON_LABELS) return false
        return normalized.any(Char::isLetterOrDigit)
    }

    private fun findBestMenuOptionMatch(
        menu: ParsedMenuSignature,
        learned: LearnedSignatureContext
    ): MenuOptionMatch? {
        val normalizedExpected = learned.normalizedSelectedLabel
        if (normalizedExpected.isBlank()) return null
        if (!isMenuContextCompatible(menu, learned)) return null

        val exactMatches = menu.optionDescriptors
            .asSequence()
            .filter { descriptor -> descriptor.normalizedLabel == normalizedExpected }
            .map { descriptor ->
                MenuOptionMatch(
                    optionKey = descriptor.key,
                    optionLabel = descriptor.label,
                    autoAdjustSafe = true
                )
            }
            .toList()
        return when (exactMatches.size) {
            1 -> exactMatches.first()
            else -> {
                exactMatches.firstOrNull { it.optionKey == learned.step.expectedInput }?.let { return it }
                var bestMatch: ScoredMenuOptionMatch? = null
                var runnerUp: ScoredMenuOptionMatch? = null
                menu.optionDescriptors.forEach { descriptor ->
                    val scored = scoreMenuOptionMatch(
                        expectedTokens = learned.selectedLabelTokens,
                        descriptor = descriptor
                    ) ?: return@forEach
                    if (bestMatch == null || scored.score > bestMatch!!.score) {
                        runnerUp = bestMatch
                        bestMatch = scored
                    } else if (runnerUp == null || scored.score > runnerUp!!.score) {
                        runnerUp = scored
                    }
                }
                val resolvedBestMatch = bestMatch ?: return null
                val uniqueEnough = runnerUp == null || (resolvedBestMatch.score - runnerUp.score) >= 0.18
                if (!uniqueEnough) return null

                val expectedTokens = learned.selectedLabelTokens
                val candidateTokens = resolvedBestMatch.descriptor.tokens
                val hasStrongAnchor = resolvedBestMatch.strongSharedTokenCount >= minOf(2, resolvedBestMatch.expectedTokenCount.coerceAtLeast(1))
                val hasNumericAnchor = expectedTokens
                    .intersect(candidateTokens)
                    .any { token -> token.any(Char::isDigit) }
                val safeToAdjust = resolvedBestMatch.score >= 0.72 &&
                    resolvedBestMatch.sharedTokenCount >= 1 &&
                    (hasStrongAnchor || hasNumericAnchor)
                val strongEnoughToReport = resolvedBestMatch.score >= 0.48 &&
                    resolvedBestMatch.sharedTokenCount >= 1 &&
                    resolvedBestMatch.candidateTokenCount > 0
                if (!strongEnoughToReport) return null

                MenuOptionMatch(
                    optionKey = resolvedBestMatch.descriptor.key,
                    optionLabel = resolvedBestMatch.descriptor.label,
                    autoAdjustSafe = safeToAdjust
                )
            }
        }
    }

    private fun scoreMenuOptionMatch(
        expectedTokens: Set<String>,
        descriptor: MenuOptionDescriptor
    ): ScoredMenuOptionMatch? {
        val candidateTokens = descriptor.tokens
        if (expectedTokens.isEmpty() || candidateTokens.isEmpty()) return null

        val sharedTokens = expectedTokens.intersect(candidateTokens)
        if (sharedTokens.isEmpty()) return null

        val sharedCount = sharedTokens.size
        val precision = sharedCount.toDouble() / candidateTokens.size.toDouble()
        val recall = sharedCount.toDouble() / expectedTokens.size.toDouble()
        val f1Score = if (precision + recall == 0.0) 0.0 else (2 * precision * recall) / (precision + recall)
        val strongSharedCount = sharedTokens.count { token ->
            token.any(Char::isDigit) || token.length >= 4
        }
        return ScoredMenuOptionMatch(
            descriptor = descriptor,
            sharedTokenCount = sharedCount,
            strongSharedTokenCount = strongSharedCount,
            expectedTokenCount = expectedTokens.size,
            candidateTokenCount = candidateTokens.size,
            score = f1Score
        )
    }

    private fun tokenizeMenuLabel(value: String): Set<String> =
        value.split(' ')
            .asSequence()
            .map { token -> token.trim() }
            .filter { token -> token.length >= 2 || token.any(Char::isDigit) }
            .toSet()

    private fun titlesLookCompatible(
        learnedTitle: String,
        learnedTokens: Set<String>,
        currentTitle: String,
        currentTokens: Set<String>
    ): Boolean {
        if (learnedTitle.isBlank() || currentTitle.isBlank()) return true
        if (learnedTitle == currentTitle) return true
        if (learnedTitle.contains(currentTitle) || currentTitle.contains(learnedTitle)) return true
        if (learnedTokens.isEmpty() || currentTokens.isEmpty()) return true
        val sharedCount = learnedTokens.intersect(currentTokens).size
        val requiredShared = minOf(2, learnedTokens.size, currentTokens.size).coerceAtLeast(1)
        return sharedCount >= requiredShared
    }

    private fun isMenuContextCompatible(menu: ParsedMenuSignature, learned: LearnedSignatureContext): Boolean {
        if (!titlesLookCompatible(
                learnedTitle = learned.normalizedMenuTitle,
                learnedTokens = learned.menuTitleTokens,
                currentTitle = menu.normalizedTitle,
                currentTokens = menu.titleTokens
            )
        ) {
            return false
        }

        val learnedSnapshot = learned.normalizedOptionSnapshot
        val currentSnapshot = menu.normalizedOptionLabels
        if (learnedSnapshot.isEmpty() || currentSnapshot.isEmpty()) return true

        val overlapCount = learnedSnapshot.intersect(currentSnapshot).size
        val requiredOverlap = when (minOf(learnedSnapshot.size, currentSnapshot.size)) {
            0 -> 0
            1, 2, 3 -> 1
            else -> 2
        }
        return overlapCount >= requiredOverlap
    }

    private fun isTransientResponseText(lower: String): Boolean =
        TRANSIENT_RESPONSE_HINTS.any { hint -> lower.contains(hint) }

    private fun hasMeaningfulResponseText(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        if (dialogText.isBlank()) return false
        val lines = normalizeDialogLines(snapshot.textTokens.ifEmpty { listOf(dialogText) })
        if (lines.isEmpty()) return false
        return lines.any { line ->
            val normalized = normalizeActionLabel(line)
            normalized.isNotBlank() &&
                normalized !in SEND_BUTTON_LABELS &&
                normalized !in DISMISS_BUTTON_LABELS &&
                normalized.length >= 3
        }
    }

    private fun shouldWaitForFinalResponse(snapshot: UssdTreeSnapshot, dialogText: String): Boolean {
        val normalized = normalizeMenuText(dialogText)
        val stateKey = buildDialogStateKey(dialogText, snapshot.inputStateSignature)
        if (!hasMeaningfulResponseText(snapshot, dialogText)) return true
        if (isTransientResponseText(normalized)) return true
        return lastStepActionKey.isNotBlank() && stateKey == lastStepActionKey
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
        return try {
            writeValueToField(root, value)
        } finally {
            root.recycle()
        }
    }

    private fun writeValueToField(root: AccessibilityNodeInfo, value: String): Boolean {
        val windowPkg = root.packageName?.toString() ?: ""
        if (windowPkg.isNotEmpty() && windowPkg != "android" && !isPotentialUssdPackage(windowPkg)) {
            return false
        }
        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val snapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
            ?: return false
        val lower = snapshot.dialogText.lowercase()
        if (NON_USSD_DIALOG_HINTS.any { lower.contains(it) }) {
            return false
        }
        val fields = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectTextEntryCandidates(root, fields)
            if (fields.isNotEmpty()) {
                fields.sortByDescending { scoreTextEntryCandidate(it) }
                if (fields.any { field -> tryWriteValueToField(field, value, root) }) {
                    return true
                }
            }
        } finally {
            fields.forEach { it.recycle() }
        }

        if (tryWriteValueToField(root, value, root)) {
            return true
        }

        val aggressiveFields = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectAggressiveTextEntryCandidates(root, aggressiveFields)
            aggressiveFields.sortByDescending {
                scoreTextEntryCandidate(it) + scoreAggressiveTextEntryCandidate(it)
            }
            return aggressiveFields.any { field -> tryWriteValueToField(field, value, root) }
        } finally {
            aggressiveFields.forEach { it.recycle() }
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
                { clickSendButton(expectedValue, attempt + 1, skipFieldVerification) },
                sendRetryDelay(attempt, expectedValue)
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
            sendBtn = findBestSendActionButton(root, fieldRef)
                ?: findPositiveDialogButton(root, fieldRef)
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
                        { clickSendButton(expectedValue, attempt + 1, skipFieldVerification) },
                        sendRetryDelay(attempt, expectedValue)
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
                    { clickSendButton(expectedValue, attempt + 1, skipFieldVerification) },
                    sendRetryDelay(attempt, expectedValue) + hotSendRetryDelayMs
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
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        requestAppUiBehindPopup()
        updateRunningOverlay()
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
        updateRunningOverlay()
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
        val now = SystemClock.elapsedRealtime()
        if (retryWindowStartedAt <= 0L) retryWindowStartedAt = now
        if (now - retryWindowStartedAt >= MAX_RETRY_WINDOW_MS) {
            val failMsg = if (lastFinalResponse.isNotBlank()) lastFinalResponse else "FAILED after 1 minute of retries"
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
        hasSeenForegroundPopup = false
        lastDialogText = ""
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastUiReturnElapsed = 0L
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        lastWindowId = -1
        lastWindowPkg = ""
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        pendingProcessToken = 0L
        clearInputWriteMarker()
        clearRecentUssdContext()
        requestAppUiBehindPopup(force = true)
        updateRunningOverlay()
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
            if (shouldKeepAppUiVisible()) UssdHelper.relaunchAppUi(this)
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
                BalanceChecker.persistLastKnownBalance(applicationContext, display)
                BalanceChecker.balanceCallback?.invoke(display)
                closeCurrentUssdUi()
                clearCallbacks()
            }
        }
    }

    private fun startStepTimeout() {
        cancelStepTimeout()
        val timeout = Runnable {
            if (shouldExtendStepTimeoutWindow()) {
                startStepTimeout()
                return@Runnable
            }
            val dismissed = closeCurrentUssdUi()
            val delay = if (dismissed) DIALOG_DISMISS_SETTLE_MS else 0L
            handler.postDelayed({ restartFromBeginning() }, delay)
        }
        stepTimeoutRunnable = timeout
        handler.postDelayed(timeout, currentStepTimeoutMs())
    }

    private fun cancelStepTimeout() {
        stepTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stepTimeoutRunnable = null
    }

    private fun currentStepTimeoutMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return when {
            pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank() ->
                if (shouldUseExtendedNetworkDelayWindow()) NETWORK_DELAY_PENDING_STEP_TIMEOUT_MS
                else PENDING_STEP_TIMEOUT_MS
            currentStep >= advancedSteps.size ->
                if (isWaitingOnTransientNetworkResponse()) NETWORK_DELAY_FINAL_RESPONSE_TIMEOUT_MS
                else FINAL_RESPONSE_TIMEOUT_MS
            !hasSeenAdvancedPopup -> STARTUP_STEP_TIMEOUT_MS
            hasRecentUssdUiEvent() -> FINAL_RESPONSE_TIMEOUT_MS
            shouldUseExtendedNetworkDelayWindow() -> NETWORK_DELAY_STEP_TIMEOUT_MS
            retryWindowStartedAt > 0L && (now - retryWindowStartedAt) <= STARTUP_UI_KEEP_VISIBLE_MS -> STARTUP_STEP_TIMEOUT_MS
            else -> STEP_TIMEOUT_MS
        }
    }

    private fun hasRecentStepAction(waitWindowMs: Long = NETWORK_DELAY_ACTION_GRACE_MS): Boolean {
        if (lastStepActionKey.isBlank() || lastStepActionElapsed <= 0L) return false
        return SystemClock.elapsedRealtime() - lastStepActionElapsed <= waitWindowMs
    }

    private fun isWaitingOnTransientNetworkResponse(): Boolean {
        val normalizedFinal = normalizeMenuText(lastFinalResponse)
        return normalizedFinal.isNotBlank() && isTransientResponseText(normalizedFinal)
    }

    private fun shouldUseExtendedNetworkDelayWindow(expectedValue: String? = pendingExpectedValue): Boolean {
        if (isWaitingOnTransientNetworkResponse()) return true
        if (hasRecentStepAction()) return true
        if (expectedValue != null &&
            (hasRecentExpectedInput(expectedValue) || hasRecentVerifiedInput(expectedValue))
        ) {
            return true
        }
        return false
    }

    private fun shouldExtendStepTimeoutWindow(): Boolean {
        if (!advancedActive) return false
        if (pendingPhase != PendingPhase.NONE || pendingStepAdvanceFromKey.isNotBlank()) return true
        if (!hasSeenAdvancedPopup) {
            return retryWindowStartedAt <= 0L ||
                (SystemClock.elapsedRealtime() - retryWindowStartedAt) <= STARTUP_UI_KEEP_VISIBLE_MS
        }
        if (currentStep >= advancedSteps.size) {
            val normalizedFinal = normalizeMenuText(lastFinalResponse)
            return normalizedFinal.isBlank() ||
                isTransientResponseText(normalizedFinal) ||
                hasRecentUssdUiEvent()
        }
        return hasRecentUssdUiEvent() || shouldUseExtendedNetworkDelayWindow()
    }

    private fun cleanupAdvanced() {
        stopKeepingAppUiVisible()
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
        retryWindowStartedAt = 0L
        advancedActive      = false
        advancedInProgress  = false
        hideRunningOverlay()
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
        lastWindowId        = -1
        lastRelevantEventElapsed = 0L
        lastEventFingerprint = ""
        lastEventElapsed = 0L
        hasSeenAdvancedPopup = false
        hasSeenForegroundPopup = false
        lastMenuSignatureKey = ""
        lastMenuSignature = null
        loadedSignatureLookupSource = emptyList()
        loadedSignatureLookup = emptyMap()
        lastScreenSignatureKey = ""
        lastStepActionKey = ""
        lastStepActionElapsed = 0L
        lastUiReturnElapsed = 0L
        clearRootRecoveryState()
        clearPendingAdvance()
        clearPendingStepAdvance()
        clearInputWriteMarker()
        clearRecentUssdContext()
        onDispatchComplete  = null
        tokenPurchaseCallback = null
        resetSignatureTracking()
        configureUiReturn(true)
    }

    private fun clearPendingAdvanceKick() {
        pendingAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingAdvanceKickRunnable = null
    }

    private fun pendingAdvanceKickDelay(expectedValue: String, phase: PendingPhase): Long =
        when {
            useAggressiveVerifiedPopupFastPath &&
                phase == PendingPhase.WAIT_SEND &&
                (hasRecentVerifiedInput(expectedValue) ||
                    (hasRecentExpectedInput(expectedValue) && hasRecentUssdUiEvent())) -> 0L
            useAggressiveVerifiedPopupFastPath &&
                phase == PendingPhase.WAIT_VERIFY &&
                hasRecentExpectedInput(expectedValue) &&
                hasRecentUssdUiEvent() -> 0L
            phase == PendingPhase.WAIT_SEND && hasRecentVerifiedInput(expectedValue) -> 0L
            hasSeenAdvancedPopup && hasRecentVerifiedInput(expectedValue) -> 0L
            hasRecentExpectedInput(expectedValue) && hasRecentUssdUiEvent() -> postWriteVerifyPollMs
            hasRecentExpectedInput(expectedValue) -> postWriteVerifyPollMs
            hasSeenAdvancedPopup && hasRecentUssdUiEvent() -> rapidPostPopupVerifyMs
            hasRecentVerifiedInput(expectedValue) -> hotSendRetryDelayMs
            hasRecentUssdUiEvent() -> fastVerifyPollMs
            else -> pendingAdvanceKickMs
        }

    private fun schedulePendingAdvanceKick(delayMs: Long = rootReacquireRetryDelayMs) {
        if (!advancedActive || pendingPhase == PendingPhase.NONE) return
        clearPendingAdvanceKick()
        val task = Runnable {
            pendingAdvanceKickRunnable = null
            attemptPendingAdvanceWithRoot(null)
        }
        pendingAdvanceKickRunnable = task
        if (delayMs <= 0L) handler.post(task) else handler.postDelayed(task, delayMs)
    }

    private fun clearPendingAdvance() {
        clearPendingAdvanceKick()
        pendingExpectedValue = null
        pendingPhase = PendingPhase.NONE
        pendingAdvanceFromKey = ""
        pendingSinceElapsed = 0L
        pendingAttempts = 0
    }

    private fun startPendingAdvance(
        expectedValue: String,
        root: AccessibilityNodeInfo,
        dialogText: String,
        snapshot: UssdTreeSnapshot? = null
    ) {
        pendingExpectedValue = expectedValue
        pendingPhase = if (hasRecentVerifiedInput(expectedValue)) PendingPhase.WAIT_SEND else PendingPhase.WAIT_VERIFY
        pendingAdvanceFromKey = buildTransitionSignatureKey(
            windowId = resolveWindowId(root),
            windowPkg = root.packageName?.toString().orEmpty(),
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        pendingSinceElapsed = SystemClock.elapsedRealtime()
        pendingAttempts = 0
        isProcessing = false
        // Safety kick in case OEM doesn't emit a useful event after ACTION_SET_TEXT.
        schedulePendingAdvanceKick(delayMs = pendingAdvanceKickDelay(expectedValue, pendingPhase))
    }

    private fun attemptPendingAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) { clearPendingAdvance(); isProcessing = false; return }
        clearPendingAdvanceKick()
        val pendingTimeoutMs = if (shouldUseExtendedNetworkDelayWindow(pendingExpectedValue)) {
            NETWORK_DELAY_PENDING_ADVANCE_TIMEOUT_MS
        } else {
            PENDING_ADVANCE_TIMEOUT_MS
        }
        val root = existingRoot ?: getUssdRoot() ?: run {
            if (pendingSinceElapsed > 0L &&
                SystemClock.elapsedRealtime() - pendingSinceElapsed > pendingTimeoutMs
            ) {
                clearPendingAdvance()
                isProcessing = false
                dismissErrorAndRestart()
            } else {
                val expected = pendingExpectedValue
                val delayMs = expected?.let { pendingAdvanceKickDelay(it, pendingPhase) } ?: rootReacquireRetryDelayMs
                schedulePendingAdvanceKick(delayMs = delayMs)
            }
            return
        }
        try {
            attemptPendingAdvance(root)
        } finally {
            if (existingRoot == null) runCatching { root.recycle() }
        }
    }

    private fun attemptPendingAdvance(root: AccessibilityNodeInfo) {
        val expected = pendingExpectedValue ?: run { clearPendingAdvance(); return }
        val pendingTimeoutMs = if (shouldUseExtendedNetworkDelayWindow(expected)) {
            NETWORK_DELAY_PENDING_ADVANCE_TIMEOUT_MS
        } else {
            PENDING_ADVANCE_TIMEOUT_MS
        }
        val elapsed = SystemClock.elapsedRealtime() - pendingSinceElapsed
        if (elapsed > pendingTimeoutMs) {
            clearPendingAdvance()
            isProcessing = false
            dismissErrorAndRestart()
            return
        }

        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val interactionRoot = obtainInteractionRoot(root, requireStrictDialog = requireStrictPopupScope) ?: run {
            schedulePendingAdvanceKick(delayMs = pendingAdvanceKickDelay(expected, pendingPhase))
            isProcessing = false
            return
        }
        try {
            if (shouldAdvanceFromChangedPendingPopup(interactionRoot, expected)) {
                clearPendingAdvance()
                advanceStep()
                pendingProcessToken = SystemClock.elapsedRealtime()
                scheduleProcessStep(dialogChanged = true)
                return
            }
            when (pendingPhase) {
                PendingPhase.WAIT_VERIFY -> {
                    val field = findEditableField(interactionRoot)
                    try {
                        val verified = if (field != null) {
                            verifyExpectedInputFromRoot(
                                root = interactionRoot,
                                expectedValue = expected,
                                existingField = field
                            )
                        } else {
                            hasRecentVerifiedInput(expected)
                        }

                        if (verified) {
                            pendingPhase = PendingPhase.WAIT_SEND
                            attemptPendingAdvance(root)
                            return
                        }

                        // Re-write as soon as a delayed popup finally exposes the real field.
                        if (pendingAttempts < 2 && shouldForcePendingFieldRewrite(expected, field != null)) {
                            pendingAttempts++
                            val wroteValue = try {
                                if (field != null) {
                                    tryWriteValueToField(field, expected, interactionRoot) ||
                                        writeValueToField(interactionRoot, expected)
                                } else {
                                    writeValueToField(interactionRoot, expected)
                                }
                            } catch (_: Exception) {
                                false
                            }
                            if (wroteValue && verifyExpectedInputFromRoot(interactionRoot, expected, field)) {
                                pendingPhase = PendingPhase.WAIT_SEND
                                attemptPendingAdvance(root)
                                return
                            }
                        }
                        schedulePendingAdvanceKick(delayMs = pendingAdvanceKickDelay(expected, pendingPhase))
                        isProcessing = false
                    } finally {
                        runCatching { field?.recycle() }
                    }
                }

                PendingPhase.WAIT_SEND -> {
                    val sent = tryImmediateVerifiedSend(interactionRoot, field = null, expectedValue = expected)
                    if (sent) {
                        clearPendingAdvance()
                        val text = capturePreferredPopupSnapshot(
                            root = interactionRoot,
                            requireStrictDialog = requireStrictPopupScope
                        )?.dialogText.orEmpty()
                        markStepAction(text)
                        startPendingStepAdvance(interactionRoot, text)
                    } else {
                        // Let the next event re-try, but avoid expensive work here.
                        schedulePendingAdvanceKick(delayMs = pendingAdvanceKickDelay(expected, pendingPhase))
                        isProcessing = false
                    }
                }

                PendingPhase.NONE -> Unit
            }
        } finally {
            interactionRoot.recycle()
        }
    }

    private fun shouldAdvanceFromChangedPendingPopup(
        root: AccessibilityNodeInfo,
        expectedValue: String
    ): Boolean {
        val fromKey = pendingAdvanceFromKey
        if (fromKey.isBlank()) return false
        val requireStrictPopupScope = shouldRequireStrictPopupScope()
        val snapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
        val dialogText = snapshot?.dialogText ?: normalizeCollapsedText(extractAllText(root))
        if (dialogText.isBlank()) return false
        val currentKey = buildTransitionSignatureKey(
            windowId = resolveWindowId(root),
            windowPkg = root.packageName?.toString().orEmpty(),
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        if (currentKey == fromKey) return false
        if (verifyExpectedInputFromRoot(root, expectedValue)) {
            pendingAdvanceFromKey = currentKey
            return false
        }
        return true
    }

    private fun clearCallbacks() {
        lastDialogText      = ""
        tokenPurchaseCallback = null
        balanceCallback     = null
        onDispatchComplete  = null
    }

    private fun shouldKeepAppUiVisible(): Boolean =
        keepAppUiVisibleEnabled &&
            !uiReturnSuppressed &&
            ((advancedActive && advancedInProgress) || isForegroundUiActive())

    private fun hasSeenUssdPopup(): Boolean =
        hasSeenAdvancedPopup || hasSeenForegroundPopup

    private fun shouldShowRunningOverlay(): Boolean =
        SHOW_RUNNING_OVERLAY && ((advancedInProgress || (advancedActive && advancedSteps.isNotEmpty())) || isForegroundUiActive())

    private fun requestAppUiBehindPopup(force: Boolean = false) {
        if (!shouldKeepAppUiVisible()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastUiReturnElapsed < 900L) return
        lastUiReturnElapsed = now
        UssdHelper.relaunchAppUi(this, delayMs = if (force) 60L else 120L)
    }

    private fun startKeepingAppUiVisible() {
        uiKeepVisibleRunnable?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val foregroundExpired = foregroundUiActive && !isForegroundUiActive()
                if (foregroundExpired) {
                    disarmForegroundUi()
                    hasSeenForegroundPopup = false
                    updateRunningOverlay()
                }
                val startupWindowActive = advancedActive &&
                    !hasSeenUssdPopup() &&
                    (SystemClock.elapsedRealtime() - lastRelevantEventElapsed) <= STARTUP_UI_KEEP_VISIBLE_MS
                val canKeepVisible = shouldKeepAppUiVisible() &&
                    (startupWindowActive || (hasSeenUssdPopup() && hasRecentUssdUiEvent()))
                if (!canKeepVisible) {
                    uiKeepVisibleRunnable = null
                    return
                }
                requestAppUiBehindPopup(force = true)
                handler.postDelayed(this, UI_KEEP_VISIBLE_INTERVAL_MS)
            }
        }
        uiKeepVisibleRunnable = task
        handler.postDelayed(task, UI_KEEP_VISIBLE_INTERVAL_MS)
    }

    private fun stopKeepingAppUiVisible() {
        uiKeepVisibleRunnable?.let { handler.removeCallbacks(it) }
        uiKeepVisibleRunnable = null
    }

    private fun buildRunningOverlayView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val paddingH = dp(14)
            val paddingV = dp(10)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E61A1A1A"))
                setStroke(dp(1), Color.parseColor("#3329B6F6"))
            }
            elevation = dp(8).toFloat()
        }

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val detail = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD7E3F4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(0f, 1.05f)
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            alpha = 0.9f
        }

        container.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            detail,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        )
        container.addView(
            progress,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dp(6)
            }
        )

        runningOverlayView = container
        runningOverlayStatusText = status
        runningOverlayDetailText = detail
        return container
    }

    private fun buildRunningOverlayStatusText(): String = when {
        retryCount > 0 -> "Bingwa Mobile USSD retrying"
        !hasSeenAdvancedPopup -> "Bingwa Mobile opening USSD"
        currentStep >= advancedSteps.size -> "Bingwa Mobile finishing USSD"
        else -> "Bingwa Mobile USSD running"
    }

    private fun buildRunningOverlayDetailText(): String {
        val detailParts = mutableListOf<String>()
        advancedOfferName.takeIf { it.isNotBlank() }?.let { detailParts += it }
        when {
            advancedSteps.isEmpty() -> detailParts += "Waiting for the network menu"
            currentStep >= advancedSteps.size -> detailParts += "Finalizing network response"
            advancedSteps.getOrNull(currentStep) == "INPUT_PHONE" -> {
                val progress = "Step ${currentStep + 1} of ${advancedSteps.size}"
                detailParts += "$progress, entering phone number"
            }
            else -> detailParts += "Step ${currentStep + 1} of ${advancedSteps.size}"
        }
        if (retryCount > 0) {
            detailParts += "Retry $retryCount within 1-minute window"
        }
        return detailParts.joinToString("  |  ")
            .ifBlank { "USSD session is still active while Bingwa stays visible here" }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun clearPendingStepAdvance() {
        pendingStepAdvanceFromKey = ""
        pendingStepAdvanceSinceElapsed = 0L
        pendingStepAdvanceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceTimeoutRunnable = null
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceKickRunnable = null
    }

    private fun shouldWaitForRootRecovery(): Boolean {
        if (!advancedActive) return false
        return hasSeenAdvancedPopup ||
            pendingPhase != PendingPhase.NONE ||
            pendingStepAdvanceFromKey.isNotBlank() ||
            hasRecentUssdUiEvent()
    }

    private fun waitForRootRecovery() {
        val now = SystemClock.elapsedRealtime()
        if (waitingForRootSinceElapsed == 0L) {
            waitingForRootSinceElapsed = now
        }
        val elapsed = now - waitingForRootSinceElapsed
        val rootRecoveryTimeoutMs = if (shouldUseExtendedNetworkDelayWindow()) {
            NETWORK_DELAY_ROOT_REACQUIRE_TIMEOUT_MS
        } else {
            ROOT_REACQUIRE_TIMEOUT_MS
        }
        if (elapsed > rootRecoveryTimeoutMs) {
            clearRootRecoveryState()
            handler.post { dismissErrorAndRestart() }
            return
        }
        pendingProcessToken = now
        scheduleProcessStep(dialogChanged = false, overrideDelayMs = rootReacquireRetryDelayMs)
    }

    private fun clearRootRecoveryState() {
        waitingForRootSinceElapsed = 0L
    }

    private fun rememberRecentUssdContext(
        root: AccessibilityNodeInfo,
        snapshot: UssdTreeSnapshot?,
        windowId: Int,
        windowPkg: String,
        dialogText: String,
        strictDialog: Boolean
    ) {
        recentUssdRoot?.let { existing ->
            if (existing !== root) {
                runCatching { existing.recycle() }
            }
        }
        recentUssdRoot = AccessibilityNodeInfo.obtain(root)
        recentUssdSnapshot = snapshot
        recentUssdWindowId = windowId
        recentUssdWindowPkg = windowPkg
        recentUssdDialogText = dialogText
        recentUssdStrictDialog = strictDialog
        recentUssdCapturedElapsed = SystemClock.elapsedRealtime()
    }

    private fun hasFreshRecentUssdContext(requireStrictDialog: Boolean = false): Boolean {
        if (recentUssdCapturedElapsed <= 0L) return false
        if (SystemClock.elapsedRealtime() - recentUssdCapturedElapsed > RECENT_USSD_CONTEXT_WINDOW_MS) return false
        if (requireStrictDialog && !recentUssdStrictDialog) return false
        return recentUssdRoot != null
    }

    private fun obtainRecentUssdContext(requireStrictDialog: Boolean = false): RecentUssdContext? {
        if (!hasFreshRecentUssdContext(requireStrictDialog = requireStrictDialog)) return null
        val root = recentUssdRoot ?: return null
        return RecentUssdContext(
            root = AccessibilityNodeInfo.obtain(root),
            snapshot = recentUssdSnapshot,
            windowId = recentUssdWindowId,
            windowPkg = recentUssdWindowPkg,
            dialogText = recentUssdDialogText,
            strictDialog = recentUssdStrictDialog
        )
    }

    private fun clearRecentUssdContext() {
        recentUssdRoot?.let { runCatching { it.recycle() } }
        recentUssdRoot = null
        recentUssdSnapshot = null
        recentUssdWindowId = -1
        recentUssdWindowPkg = ""
        recentUssdDialogText = ""
        recentUssdStrictDialog = false
        recentUssdCapturedElapsed = 0L
    }

    private fun startPendingStepAdvance(root: AccessibilityNodeInfo, dialogText: String) {
        clearPendingStepAdvance()
        pendingStepAdvanceSinceElapsed = SystemClock.elapsedRealtime()
        val snapshot = captureTreeSnapshot(root)
        pendingStepAdvanceFromKey = buildTransitionSignatureKey(
            windowId = resolveWindowId(root),
            windowPkg = root.packageName?.toString().orEmpty(),
            root = root,
            snapshot = snapshot,
            dialogText = dialogText
        )
        val timeoutTask = Runnable {
            if (pendingStepAdvanceFromKey.isBlank()) return@Runnable
            clearPendingStepAdvance()
            isProcessing = false
            dismissErrorAndRestart()
        }
        pendingStepAdvanceTimeoutRunnable = timeoutTask
        val timeoutMs = if (shouldUseExtendedNetworkDelayWindow()) {
            NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS
        } else {
            PENDING_STEP_ADVANCE_TIMEOUT_MS
        }
        handler.postDelayed(timeoutTask, timeoutMs)
        schedulePendingStepAdvanceKick()
        isProcessing = false
    }

    private fun schedulePendingStepAdvanceKick(delayMs: Long = PENDING_STEP_ADVANCE_KICK_MS) {
        if (!advancedActive || pendingStepAdvanceFromKey.isBlank()) return
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            pendingStepAdvanceKickRunnable = null
            attemptPendingStepAdvanceWithRoot(null)
        }
        pendingStepAdvanceKickRunnable = task
        if (delayMs <= 0L) handler.post(task) else handler.postDelayed(task, delayMs)
    }

    private fun attemptPendingStepAdvanceWithRoot(existingRoot: AccessibilityNodeInfo?) {
        if (!advancedActive) {
            clearPendingStepAdvance()
            isProcessing = false
            return
        }
        val fromKey = pendingStepAdvanceFromKey
        if (fromKey.isBlank()) {
            isProcessing = false
            return
        }
        pendingStepAdvanceKickRunnable?.let { handler.removeCallbacks(it) }
        pendingStepAdvanceKickRunnable = null

        val elapsed = SystemClock.elapsedRealtime() - pendingStepAdvanceSinceElapsed
        val timeoutMs = if (shouldUseExtendedNetworkDelayWindow()) {
            NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS
        } else {
            PENDING_STEP_ADVANCE_TIMEOUT_MS
        }
        if (elapsed > timeoutMs) {
            clearPendingStepAdvance()
            isProcessing = false
            dismissErrorAndRestart()
            return
        }

        val root = existingRoot ?: getUssdRoot() ?: run {
            schedulePendingStepAdvanceKick()
            isProcessing = false
            return
        }
        try {
            val requireStrictPopupScope = shouldRequireStrictPopupScope()
            val snapshot = capturePreferredPopupSnapshot(root, requireStrictDialog = requireStrictPopupScope)
            val dialogText = snapshot?.dialogText
                ?: normalizeCollapsedText(extractAllText(root))
            if (dialogText.isBlank()) {
                schedulePendingStepAdvanceKick()
                isProcessing = false
                return
            }
            val currentKey = buildTransitionSignatureKey(
                windowId = resolveWindowId(root),
                windowPkg = root.packageName?.toString().orEmpty(),
                root = root,
                snapshot = snapshot,
                dialogText = dialogText
            )
            if (currentKey == fromKey) {
                schedulePendingStepAdvanceKick()
                isProcessing = false
                return
            }
            clearPendingStepAdvance()
            advanceStep()
            pendingProcessToken = SystemClock.elapsedRealtime()
            scheduleProcessStep(dialogChanged = true)
        } finally {
            if (existingRoot == null) runCatching { root.recycle() }
        }
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
        val timeoutMs = if (shouldUseExtendedNetworkDelayWindow()) {
            NETWORK_DELAY_STEP_ADVANCE_TIMEOUT_MS
        } else {
            PENDING_STEP_ADVANCE_TIMEOUT_MS
        }
        if (elapsed > timeoutMs) {
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
        if (currentKey == fromKey) {
            schedulePendingStepAdvanceKick()
            return true
        }
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
        findBestEditableField(node) { scoreTextEntryCandidate(it) }

    private fun findEditableFieldForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String,
        expectedValue: String
    ): AccessibilityNodeInfo? =
        findBestEditableField(node) { scoreTextEntryCandidateForStep(it, step, dialogText, expectedValue) }

    private fun findBestEditableField(
        node: AccessibilityNodeInfo,
        scorer: (AccessibilityNodeInfo) -> Int
    ): AccessibilityNodeInfo? {
        val directTargets = obtainInputTargets(node)
        try {
            directTargets.maxByOrNull(scorer)?.let { best ->
                return AccessibilityNodeInfo.obtain(best)
            }
        } finally {
            directTargets.forEach { it.recycle() }
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectTextEntryCandidates(node, candidates)
            candidates.maxByOrNull(scorer)?.let { best ->
                return AccessibilityNodeInfo.obtain(best)
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        val aggressiveCandidates = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectAggressiveTextEntryCandidates(node, aggressiveCandidates)
            return aggressiveCandidates.maxByOrNull {
                scorer(it) + scoreAggressiveTextEntryCandidate(it)
            }?.let { AccessibilityNodeInfo.obtain(it) }
        } finally {
            aggressiveCandidates.forEach { it.recycle() }
        }
    }

    private fun collectTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isTextEntryNode(node) || isLooseInputCandidate(node) || isHiddenInputProxyCandidate(node)) {
                into += AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTextEntryCandidates(child, into)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun collectAggressiveTextEntryCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isAggressiveTextEntryCandidate(node)) {
                into += AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAggressiveTextEntryCandidates(child, into)
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
        if (EDITABLE_CLASS_HINTS.any { className.equals(it, ignoreCase = true) || className.contains(it, ignoreCase = true) }) score += 300
        if (className.contains("EditText", ignoreCase = true)) score += 240
        if (className.contains("Text", ignoreCase = true)) score += 90
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 180
        if (INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) || hint.contains(it) }) score += 120
        if (isLooseInputCandidate(node)) score += 110
        if (isHiddenInputProxyCandidate(node)) score += 150
        if (try { node.isFocused } catch (_: Exception) { false }) score += 90
        if (try { node.isFocusable } catch (_: Exception) { false }) score += 70
        if (try { node.isClickable } catch (_: Exception) { false }) score += 40
        val currentValue = readFieldText(node)?.trim().orEmpty()
        when {
            currentValue.isBlank() -> score += 120
            isLikelyPromptText(currentValue) -> score -= 180
            currentValue.length <= 24 -> score += 30
            else -> score -= 45
        }
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score -= 280
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 280
        if (bounds.right > 0 || bounds.bottom > 0) {
            score += bounds.bottom / 24
            score += bounds.right / 36
        }
        return score
    }

    private fun scoreTextEntryCandidateForStep(
        node: AccessibilityNodeInfo,
        step: String,
        dialogText: String,
        expectedValue: String
    ): Int {
        var score = scoreTextEntryCandidate(node)
        score += scorePromptAlignedFieldCandidate(node, dialogText, expectedValue)
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

    private fun nodeFieldMetadata(node: AccessibilityNodeInfo): String {
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        return listOf(viewId, label, desc, hint).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun scoreHintAlignment(
        metadata: String,
        dialogLower: String,
        dialogHints: List<String>,
        fieldHints: List<String>,
        bonus: Int
    ): Int {
        if (!dialogHints.any { dialogLower.contains(it) }) return 0
        return if (fieldHints.any { metadata.contains(it) }) bonus else 0
    }

    private fun scorePromptAlignedFieldCandidate(
        node: AccessibilityNodeInfo,
        dialogText: String,
        expectedValue: String
    ): Int {
        val metadata = nodeFieldMetadata(node)
        if (metadata.isBlank()) return 0
        val lowerDialog = dialogText.lowercase()
        var score = 0
        score += scoreHintAlignment(metadata, lowerDialog, PHONE_INPUT_HINTS, PHONE_INPUT_HINTS, 240)
        score += scoreHintAlignment(metadata, lowerDialog, AMOUNT_INPUT_HINTS, AMOUNT_INPUT_HINTS, 220)
        score += scoreHintAlignment(metadata, lowerDialog, PIN_INPUT_HINTS, PIN_INPUT_HINTS, 240)
        score += scoreHintAlignment(metadata, lowerDialog, ACCOUNT_INPUT_HINTS, ACCOUNT_INPUT_HINTS, 220)
        score += scoreHintAlignment(metadata, lowerDialog, CODE_INPUT_HINTS, CODE_INPUT_HINTS, 220)

        val normalizedExpected = normalizeInputValue(expectedValue)
        val expectedPhone = normalizePhoneComparable(expectedValue)
        when {
            expectedPhone.isNotBlank() && PHONE_INPUT_HINTS.any { metadata.contains(it) } -> score += 220
            normalizedExpected.all(Char::isDigit) && normalizedExpected.length in 4..8 &&
                (PIN_INPUT_HINTS.any { metadata.contains(it) } || CODE_INPUT_HINTS.any { metadata.contains(it) }) -> score += 180
            normalizedExpected.all(Char::isDigit) && normalizedExpected.length >= 2 &&
                AMOUNT_INPUT_HINTS.any { metadata.contains(it) } -> score += 150
        }

        if (PHONE_INPUT_HINTS.any { lowerDialog.contains(it) } &&
            (AMOUNT_INPUT_HINTS.any { metadata.contains(it) } || PIN_INPUT_HINTS.any { metadata.contains(it) })
        ) score -= 120
        if (AMOUNT_INPUT_HINTS.any { lowerDialog.contains(it) } &&
            PHONE_INPUT_HINTS.any { metadata.contains(it) }
        ) score -= 110
        if (PIN_INPUT_HINTS.any { lowerDialog.contains(it) } &&
            AMOUNT_INPUT_HINTS.any { metadata.contains(it) }
        ) score -= 100
        return score
    }

    private fun scoreAggressiveTextEntryCandidate(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val writable = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        var score = 0
        if (writable) score += 260
        if (!visible) score += 180
        if (className.contains("Edit", ignoreCase = true) || className.contains("TextInput", ignoreCase = true)) score += 200
        if (className.contains("AutoComplete", ignoreCase = true) || className.contains("Search", ignoreCase = true)) score += 140
        if (INPUT_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 200
        if (INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) || hint.contains(it) }) score += 160
        if (try { node.isFocused } catch (_: Exception) { false }) score += 70
        if (try { node.isFocusable || node.isClickable || node.isLongClickable } catch (_: Exception) { false }) score += 60
        if (label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS) score -= 360
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 360
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

    private fun extractAllText(node: AccessibilityNodeInfo): String =
        StringBuilder().also { appendAllText(node, it) }.toString()

    private fun appendAllText(node: AccessibilityNodeInfo, into: StringBuilder) {
        try {
            node.text?.let { into.append(it).append(' ') }
            node.contentDescription?.let { into.append(it).append(' ') }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    appendAllText(child, into)
                } finally {
                    child.recycle()
                }
            }
        } catch (_: Exception) {}
    }

    private class TreeScanAccumulator {
        val textTokens = mutableListOf<String>()
        var hasEditableField = false
        var hasSendButton = false
        var hasDismissButton = false
        var bestInputSignature = ""
        var bestInputScore = Int.MIN_VALUE
    }

    private fun scanNodeSummary(node: AccessibilityNodeInfo): TreeScanAccumulator =
        TreeScanAccumulator().also { collectTreeSnapshot(node, it) }

    private fun captureTreeSnapshot(root: AccessibilityNodeInfo): UssdTreeSnapshot {
        val captureRoot = findDialogCaptureRoot(root) ?: AccessibilityNodeInfo.obtain(root)
        val accumulator = scanNodeSummary(captureRoot)
        return try {
            val dialogText = normalizeCollapsedText(accumulator.textTokens.joinToString(" "))
            UssdTreeSnapshot(
                dialogText = dialogText,
                normalizedDialogText = dialogText,
                textTokens = accumulator.textTokens.toList(),
                hasEditableField = accumulator.hasEditableField,
                hasSendButton = accumulator.hasSendButton,
                hasDismissButton = accumulator.hasDismissButton,
                inputStateSignature = accumulator.bestInputSignature
            )
        } finally {
            captureRoot.recycle()
        }
    }

    /**
     * Signature learning must only record text that belongs to the visible USSD dialog itself.
     * Some devices expose the full underlying window tree; in that case we require a dialog-sized
     * capture root (found by [findDialogCaptureRoot]) and skip learning captures if we can't find it.
     */
    private fun captureTreeSnapshotStrictDialog(root: AccessibilityNodeInfo): UssdTreeSnapshot? {
        val captureRoot = findDialogCaptureRoot(root) ?: return null
        val accumulator = scanNodeSummary(captureRoot)
        return try {
            val dialogText = normalizeCollapsedText(accumulator.textTokens.joinToString(" "))
            if (dialogText.isBlank()) return null
            UssdTreeSnapshot(
                dialogText = dialogText,
                normalizedDialogText = dialogText,
                textTokens = accumulator.textTokens.toList(),
                hasEditableField = accumulator.hasEditableField,
                hasSendButton = accumulator.hasSendButton,
                hasDismissButton = accumulator.hasDismissButton,
                inputStateSignature = accumulator.bestInputSignature
            )
        } finally {
            captureRoot.recycle()
        }
    }

    private fun findDialogCaptureRoot(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootBounds = Rect()
        runCatching { root.getBoundsInScreen(rootBounds) }
        val candidates = mutableListOf<DialogCaptureCandidate>()
        collectDialogCaptureCandidates(root, rootBounds, candidates, depth = 0)
        return try {
            candidates
                .maxByOrNull { it.score }
                ?.let { AccessibilityNodeInfo.obtain(it.node) }
        } finally {
            candidates.forEach { candidate ->
                runCatching { candidate.node.recycle() }
            }
        }
    }

    private fun collectDialogCaptureCandidates(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        into: MutableList<DialogCaptureCandidate>,
        depth: Int
    ) {
        val score = scoreDialogCaptureCandidate(node, rootBounds, depth)
        if (score > 0) {
            into += DialogCaptureCandidate(
                node = AccessibilityNodeInfo.obtain(node),
                score = score
            )
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                collectDialogCaptureCandidates(child, rootBounds, into, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun scoreDialogCaptureCandidate(
        node: AccessibilityNodeInfo,
        rootBounds: Rect,
        depth: Int
    ): Int {
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        if (childCount == 0) return 0

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val nodeArea = bounds.width().toLong() * bounds.height().toLong()
        val rootArea = rootBounds.width().toLong() * rootBounds.height().toLong()
        val areaRatio = if (nodeArea > 0L && rootArea > 0L) nodeArea.toFloat() / rootArea.toFloat() else 1f
        if (nodeArea <= 0L || areaRatio < 0.03f) return 0

        val summary = scanNodeSummary(node)
        val textTokens = summary.textTokens
            .map(::normalizeCollapsedText)
            .filter { it.isNotBlank() }
            .distinct()
        if (textTokens.isEmpty()) return 0

        val combinedText = textTokens.joinToString(" ").lowercase()
        if (NON_USSD_DIALOG_HINTS.any { combinedText.contains(it) }) return 0

        val hasAction = summary.hasSendButton || summary.hasDismissButton || summary.hasEditableField
        val structuredMenu = extractStructuredMenuBlock(textTokens)
        val hasMenuOptions = structuredMenu != null
        val structuredMenuOptionCount = structuredMenu?.options?.size ?: 0
        val className = node.className?.toString().orEmpty()
        val hasDialogClass = className.contains("Dialog", ignoreCase = true) ||
            className.contains("AlertDialog", ignoreCase = true) ||
            className.contains("BottomSheet", ignoreCase = true)
        val compactContainer = childCount in 1..8
        val moderatelyCompactContainer = childCount in 1..14
        val hasUssdLanguage = USSD_HINTS.any { combinedText.contains(it) } || errorKeywords.any { combinedText.contains(it) }
        if (!hasAction && !hasMenuOptions) return 0
        if (!(hasUssdLanguage || hasMenuOptions || signatureLearningMode || advancedActive || isForegroundUiActive())) return 0

        val horizontalInset = maxOf(0, bounds.left - rootBounds.left) + maxOf(0, rootBounds.right - bounds.right)
        val verticalInset = maxOf(0, bounds.top - rootBounds.top) + maxOf(0, rootBounds.bottom - bounds.bottom)
        val hasInset = horizontalInset > 0 || verticalInset > 0
        val centerDx = kotlin.math.abs(bounds.centerX() - rootBounds.centerX())
        val centerDy = kotlin.math.abs(bounds.centerY() - rootBounds.centerY())
        val centered = centerDx <= (rootBounds.width() * 0.18f) && centerDy <= (rootBounds.height() * 0.18f)
        val fullScreenDialogLike = areaRatio > 0.96f &&
            (hasDialogClass || (centered && childCount <= 10 && (hasAction || hasMenuOptions)))
        if (areaRatio > 0.995f && !fullScreenDialogLike) return 0

        var score = 0
        if (summary.hasEditableField) score += 380
        if (summary.hasSendButton) score += 260
        if (summary.hasDismissButton) score += 120
        if (hasMenuOptions) score += 260 + (structuredMenuOptionCount * 45)
        if (hasUssdLanguage) score += 220
        if (hasDialogClass) score += 180
        if (compactContainer) score += 140
        else if (moderatelyCompactContainer) score += 70
        else score -= minOf(childCount, 32) * 18
        if (areaRatio in 0.08f..0.86f) score += 180
        else if (fullScreenDialogLike) score += 110
        else if (areaRatio > 0.96f) score -= 220
        if (hasInset) score += 80
        if (centered) score += 70
        score += maxOf(0, 42 - (depth * 4))
        score -= maxOf(0, textTokens.size - 12) * 10
        if (hasMenuOptions) {
            val blockLineCount = structuredMenuOptionCount + (structuredMenu?.titleLines?.size ?: 0)
            score -= maxOf(0, textTokens.size - blockLineCount - 4) * 18
        }
        return score.takeIf { it > 0 } ?: 0
    }

    private fun collectTreeSnapshot(node: AccessibilityNodeInfo, accumulator: TreeScanAccumulator) {
        try {
            node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { accumulator.textTokens += it }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { accumulator.textTokens += it }
            val isEditableField = isTextEntryNode(node)
            if (!accumulator.hasEditableField && isEditableField) accumulator.hasEditableField = true
            if (!accumulator.hasSendButton && isSendActionNode(node)) accumulator.hasSendButton = true
            if (!accumulator.hasDismissButton && isDismissActionNode(node)) accumulator.hasDismissButton = true
            if (isEditableField || isLooseInputCandidate(node)) {
                val candidateScore = scoreTextEntryCandidate(node)
                if (candidateScore >= accumulator.bestInputScore) {
                    accumulator.bestInputScore = candidateScore
                    accumulator.bestInputSignature = buildInputNodeSignature(node)
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectTreeSnapshot(child, accumulator)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun buildDialogStateKey(dialogText: String, inputStateSignature: String): String {
        val normalizedDialog = normalizeMenuText(dialogText)
        if (normalizedDialog.isBlank()) return ""
        val normalizedInput = normalizeCollapsedText(inputStateSignature)
        return if (normalizedInput.isBlank()) normalizedDialog else "$normalizedDialog|$normalizedInput"
    }

    private fun captureInputStateSignature(root: AccessibilityNodeInfo): String {
        val field = findEditableField(root) ?: return ""
        return try {
            buildInputNodeSignature(field)
        } finally {
            field.recycle()
        }
    }

    private fun buildInputNodeSignature(node: AccessibilityNodeInfo): String {
        val className = node.className?.toString().orEmpty().substringAfterLast('.')
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val fieldText = normalizeCollapsedText(readFieldText(node))
        val valueSignature = when {
            fieldText.isBlank() -> "_"
            isLikelyPromptText(fieldText) -> "prompt:${normalizeActionLabel(fieldText).take(24)}"
            else -> "value:${normalizeInputValue(fieldText).takeLast(20)}"
        }
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val role = when {
            try { node.isEditable } catch (_: Exception) { false } -> "editable"
            supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) -> "settext"
            else -> "candidate"
        }
        return listOf(
            className,
            viewId.takeLast(24),
            hint.take(24),
            valueSignature,
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
            role
        ).joinToString("|")
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

    private fun findBestSendActionButton(
        root: AccessibilityNodeInfo,
        anchorField: AccessibilityNodeInfo? = null
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        val best = candidates
            .maxByOrNull { scoreSendActionCandidate(it) + scoreActionRelativeToField(it, anchorField) }
            ?.takeIf { scoreSendActionCandidate(it) + scoreActionRelativeToField(it, anchorField) > 0 }
            ?.let { AccessibilityNodeInfo.obtain(it) }
        candidates.forEach { it.recycle() }
        if (best != null) return best
        return findAggressiveSendActionButton(root, anchorField)
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

    private fun findPositiveDialogButton(
        root: AccessibilityNodeInfo,
        anchorField: AccessibilityNodeInfo? = null
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectActionCandidates(root, candidates)
        return candidates
            .asSequence()
            .filterNot { node ->
                val label = normalizeActionLabel(node.text?.toString())
                val desc = normalizeActionLabel(node.contentDescription?.toString())
                label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
            }
            .maxByOrNull { scoreActionCandidate(it) + scoreActionRelativeToField(it, anchorField) }
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

    private fun collectAggressiveActionCandidates(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        try {
            if (isAggressiveActionCandidate(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectAggressiveActionCandidates(child, into)
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

    private fun findAggressiveSendActionButton(
        root: AccessibilityNodeInfo,
        anchorField: AccessibilityNodeInfo? = null
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectAggressiveActionCandidates(root, candidates)
        return candidates
            .maxByOrNull {
                scoreSendActionCandidate(it) + scoreAggressiveActionCandidate(it) + scoreActionRelativeToField(it, anchorField)
            }
            ?.takeIf {
                scoreSendActionCandidate(it) + scoreAggressiveActionCandidate(it) + scoreActionRelativeToField(it, anchorField) > 0
            }
            ?.let { AccessibilityNodeInfo.obtain(it) }
            .also { candidates.forEach { it.recycle() } }
    }

    private fun scoreActionRelativeToField(
        actionNode: AccessibilityNodeInfo,
        anchorField: AccessibilityNodeInfo?
    ): Int {
        if (anchorField == null) return 0
        val actionBounds = Rect()
        val fieldBounds = Rect()
        if (!runCatching { actionNode.getBoundsInScreen(actionBounds) }.isSuccess) return 0
        if (!runCatching { anchorField.getBoundsInScreen(fieldBounds) }.isSuccess) return 0
        if (actionBounds.width() <= 0 || actionBounds.height() <= 0 || fieldBounds.width() <= 0 || fieldBounds.height() <= 0) {
            return 0
        }
        var score = 0
        val actionCenterX = actionBounds.centerX()
        val actionCenterY = actionBounds.centerY()
        val fieldCenterX = fieldBounds.centerX()
        val fieldCenterY = fieldBounds.centerY()
        if (actionCenterX >= fieldCenterX) score += 120 else score -= 60
        if (actionCenterY >= fieldCenterY - 24) score += 110 else score -= 50
        if (actionBounds.left >= fieldBounds.left) score += 40
        if (actionBounds.top >= fieldBounds.top - 24) score += 30
        val verticalGap = actionBounds.top - fieldBounds.bottom
        if (verticalGap in -24..220) score += 70
        val horizontalGap = actionBounds.left - fieldBounds.right
        if (horizontalGap in -48..260) score += 70
        return score
    }

    private fun scoreAggressiveActionCandidate(node: AccessibilityNodeInfo): Int {
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        var score = 0
        if (!visible) score += 120
        if (SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) }) score += 240
        if (SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) score += 220
        if (label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS) score -= 320
        if (DISMISS_VIEW_ID_HINTS.any { viewId.contains(it) }) score -= 260
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
        val field = existingField ?: findEditableFieldMatchingExpectedInput(root, expectedValue) ?: return false
        return try {
            val currentValue = readFieldText(field)?.trim().orEmpty()
            val verified = isVerifiedFieldValue(currentValue, expectedValue)
            if (currentValue.isNotEmpty() && !verified) return false
            if (currentValue.isBlank() && !hasRecentVerifiedInput(expectedValue)) return false
            if (verified) {
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
        ) ||
            hasRecentVerifiedInput(expectedValue)
        if (!verified) return false
        val fieldText = field?.let(::readFieldText)
        val sendBtn = findBestSendActionButton(root, field)
            ?: findPositiveDialogButton(root, field)
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

    private fun shouldTreatNumericReplyAsTextInput(
        step: String,
        valueToEnter: String,
        snapshot: UssdTreeSnapshot,
        dialogTextLower: String,
        menuSignature: ParsedMenuSignature?
    ): Boolean {
        if (step == "INPUT_PHONE") return true
        if (!valueToEnter.all(Char::isDigit) || valueToEnter.isBlank()) return false
        if (!snapshot.hasSendButton) return false
        if (snapshot.hasEditableField) return true
        if (menuSignature?.options?.isNotEmpty() == true) return true
        return dialogSuggestsTypedReplyPrompt(dialogTextLower)
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

    private fun dialogSuggestsTypedReplyPrompt(allTextLower: String): Boolean =
        dialogSuggestsTextInput(allTextLower) ||
            allTextLower.contains("select") ||
            allTextLower.contains("choose") ||
            allTextLower.contains("option") ||
            allTextLower.contains("press") ||
            allTextLower.contains("respond") ||
            allTextLower.contains("response") ||
            allTextLower.contains("continue")

    private fun dialogSuggestsPhoneInput(allTextLower: String): Boolean =
        PHONE_INPUT_HINTS.any { allTextLower.contains(it) } ||
            (allTextLower.contains("254") && (allTextLower.contains("phone") || allTextLower.contains("mobile"))) ||
            (allTextLower.contains("07") && (allTextLower.contains("phone") || allTextLower.contains("number")))

    private fun hasInputViewHint(viewId: String, hint: String): Boolean =
        INPUT_VIEW_ID_HINTS.any { viewId.contains(it) } ||
            INPUT_FIELD_HINTS.any { hint.contains(it) }

    private fun hasInputLabelHint(label: String, desc: String): Boolean =
        INPUT_FIELD_HINTS.any { label.contains(it) || desc.contains(it) }

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
        val hasViewHint = hasInputViewHint(viewId, hint)
        val hasLabelHint = hasInputLabelHint(label, desc)
        val hasSetTextAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        val hasPasteAction = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val sendOrDismissLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        return enabled && visible && !looksLikeButton && !sendOrDismissLabel && (
            editable ||
                looksLikeInputClass ||
                hasSetTextAction ||
                (hasPasteAction && (looksLikeInputClass || hasViewHint)) ||
                (hasViewHint && (focusable || clickable)) ||
                (hasLabelHint && (editable || hasSetTextAction))
            )
    }

    private fun looksLikeStructuredUssdMenu(options: LinkedHashMap<String, String>): Boolean {
        if (options.size < 2) return false
        val numericKeys = options.keys.mapNotNull { it.toIntOrNull() }
        if (numericKeys.size != options.size) return false
        val firstKey = numericKeys.firstOrNull() ?: return false
        if (firstKey != 0 && firstKey != 1) return false

        val sequentialPrefixCount = numericKeys
            .zipWithNext()
            .takeWhile { (left, right) -> right == left + 1 }
            .size + 1
        if (sequentialPrefixCount < 2) return false

        val meaningfulLabels = options.values.count { label ->
            val normalized = normalizeActionLabel(label)
            normalized.isNotBlank() &&
                normalized !in SEND_BUTTON_LABELS &&
                normalized !in DISMISS_BUTTON_LABELS &&
                normalized.any(Char::isLetterOrDigit)
        }
        return meaningfulLabels >= 2
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
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true)
        val hasWritableAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val hasViewHint = hasInputViewHint(viewId, hint)
        val sendOrDismissLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        val hasShortText = label.isBlank() || label.length <= 24
        return enabled &&
            visible &&
            !editable &&
            !looksLikeButton &&
            !sendOrDismissLabel &&
            hasShortText &&
            ((hasWritableAction && (looksLikeInputClass || hasViewHint || focusable)) ||
                (hasViewHint && (focusable || clickable)))
    }

    private fun isHiddenInputProxyCandidate(node: AccessibilityNodeInfo): Boolean {
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
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val focusable = try { node.isFocusable || node.isFocused } catch (_: Exception) { false }
        val clickable = try { node.isClickable || node.isLongClickable } catch (_: Exception) { false }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val hasSetTextAction = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)
        val hasPasteAction = supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val hasWritableAction = hasSetTextAction || hasPasteAction
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true) ||
            className.contains("AutoComplete", ignoreCase = true) ||
            className.contains("Search", ignoreCase = true)
        val hasViewHint = hasInputViewHint(viewId, hint)
        val hasLabelHint = hasInputLabelHint(label, desc)
        val hasShortOrBlankText = label.isBlank() || label.length <= 18
        val isActionLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        if (!enabled || editable || looksLikeButton || isActionLabel) return false
        if (!visible && !hasWritableAction) return false
        if (hasWritableAction && (looksLikeInputClass || hasViewHint || hasLabelHint || focusable || clickable)) {
            return true
        }
        return hasShortOrBlankText &&
            (focusable || clickable) &&
            (looksLikeInputClass || hasViewHint || hasLabelHint)
    }

    private fun isAggressiveTextEntryCandidate(node: AccessibilityNodeInfo): Boolean {
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
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val writable = supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT) ||
            supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)
        val focusable = try {
            node.isFocusable || node.isFocused || node.isClickable || node.isLongClickable
        } catch (_: Exception) {
            false
        }
        val looksLikeButton = className.contains("Button", ignoreCase = true) ||
            className.contains("ImageButton", ignoreCase = true)
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true) ||
            className.contains("AutoComplete", ignoreCase = true) ||
            className.contains("Search", ignoreCase = true)
        val hasHints = hasInputViewHint(viewId, hint) || hasInputLabelHint(label, desc)
        val isActionLabel = label in SEND_BUTTON_LABELS || desc in SEND_BUTTON_LABELS ||
            label in DISMISS_BUTTON_LABELS || desc in DISMISS_BUTTON_LABELS
        return enabled &&
            !looksLikeButton &&
            !isActionLabel && (
                editable ||
                    isHiddenInputProxyCandidate(node) ||
                    isLooseInputCandidate(node) ||
                    (writable && (looksLikeInputClass || hasHints || focusable)) ||
                    (focusable && hasHints)
                )
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

    private fun shouldTrustFreshInputWrite(
        wroteValue: Boolean,
        expectedValue: String,
        existingField: AccessibilityNodeInfo?,
        snapshot: UssdTreeSnapshot,
        dialogTextLower: String
    ): Boolean {
        if (!wroteValue || !hasRecentVerifiedInput(expectedValue)) return false
        if (existingField != null) return true
        return snapshot.hasSendButton && (
            snapshot.hasEditableField ||
                snapshot.inputStateSignature.isNotBlank() ||
                dialogSuggestsTypedReplyPrompt(dialogTextLower)
            )
    }

    private fun shouldAttemptAggressiveImmediateSubmit(
        snapshot: UssdTreeSnapshot,
        dialogTextLower: String,
        step: String,
        expectedValue: String,
        field: AccessibilityNodeInfo?
    ): Boolean {
        if (!hasRecentVerifiedInput(expectedValue)) return false
        if (field == null && !snapshot.hasEditableField && snapshot.inputStateSignature.isBlank()) return false
        return snapshot.hasSendButton ||
            step == "INPUT_PHONE" ||
            field == null ||
            dialogSuggestsTypedReplyPrompt(dialogTextLower)
    }

    private fun tryAggressiveImmediateSubmitAfterWrite(
        root: AccessibilityNodeInfo,
        field: AccessibilityNodeInfo?,
        expectedValue: String
    ): Boolean {
        if (!hasRecentVerifiedInput(expectedValue)) return false
        val sendBtn = findBestSendActionButton(root, field)
            ?: findPositiveDialogButton(root, field)
            ?: findBottomRightActionButton(root)
        try {
            if (sendBtn != null && performClick(sendBtn)) {
                return true
            }
        } finally {
            sendBtn?.recycle()
        }
        return triggerInputSubmit(root, expectedValue, field)
    }

    private fun shouldTrustRecentWrite(fieldText: String?, expectedValue: String): Boolean {
        val actual = fieldText?.trim().orEmpty()
        if (actual.isBlank()) return hasRecentVerifiedInput(expectedValue)
        return hasRecentVerifiedInput(expectedValue) && isLikelyPromptText(actual)
    }

    private fun isVerifiedFieldValue(fieldText: String?, expectedValue: String): Boolean {
        val actual = fieldText?.trim().orEmpty()
        return when {
            matchesExpectedInput(actual, expectedValue) -> true
            shouldTrustRecentWrite(actual, expectedValue) -> true
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
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        return try {
            collectTextEntryCandidates(root, candidates)
            val verified = candidates.any { candidate ->
                isVerifiedFieldValue(readFieldText(candidate), expectedValue)
            }
            val aggressiveVerified = if (verified) {
                false
            } else {
                val aggressiveCandidates = mutableListOf<AccessibilityNodeInfo>()
                try {
                    collectAggressiveTextEntryCandidates(root, aggressiveCandidates)
                    aggressiveCandidates.any { candidate ->
                        isVerifiedFieldValue(readFieldText(candidate), expectedValue)
                    }
                } finally {
                    aggressiveCandidates.forEach { candidate ->
                        if (candidate !== existingField) {
                            runCatching { candidate.recycle() }
                        }
                    }
                }
            }
            if (verified || aggressiveVerified) rememberVerifiedInput(expectedValue)
            verified || aggressiveVerified
        } finally {
            candidates.forEach { candidate ->
                if (candidate !== existingField) {
                    runCatching { candidate.recycle() }
                }
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

    private fun tryWriteValueToField(
        field: AccessibilityNodeInfo,
        value: String,
        verificationRoot: AccessibilityNodeInfo? = null
    ): Boolean {
        var wroteValue = false
        for (pass in 0 until FORCEFUL_WRITE_PASSES) {
            val targets = obtainInputTargets(field)
            try {
                targets.forEach { target ->
                    val result = writeValueUsingStrategies(target, value)
                    if (result.wroteValue) {
                        wroteValue = true
                        rememberInputWrite(value)
                        val verified = result.likelyVerified ||
                            verifyWrittenValueWithRetries(verificationRoot, target, value)
                        if (verified) {
                            rememberVerifiedInput(value)
                            return true
                        }
                    }
                }
                if (wroteValue && verifyWrittenValueWithRetries(verificationRoot, field, value)) {
                    rememberVerifiedInput(value)
                    return true
                }
            } finally {
                targets.forEach { it.recycle() }
            }
            primeInputTarget(field, aggressive = true)
            refreshInputTarget(field)
            if (verificationRoot != null) {
                runCatching { verificationRoot.refresh() }
            }
        }
        return wroteValue && verifyWrittenValueWithRetries(verificationRoot, field, value)
    }

    private fun verifyWrittenValue(
        verificationRoot: AccessibilityNodeInfo?,
        field: AccessibilityNodeInfo,
        expectedValue: String
    ): Boolean {
        if (isLikelyDirectWriteVerified(field, expectedValue)) return true
        val root = verificationRoot ?: return false
        runCatching { root.refresh() }
        return verifyExpectedInputFromRoot(
            root = root,
            expectedValue = expectedValue,
            existingField = field
        )
    }

    private fun verifyWrittenValueWithRetries(
        verificationRoot: AccessibilityNodeInfo?,
        field: AccessibilityNodeInfo,
        expectedValue: String
    ): Boolean {
        repeat(WRITE_VERIFICATION_PASSES) { attempt ->
            if (attempt > 0) {
                runCatching { field.refresh() }
                verificationRoot?.let { root ->
                    runCatching { root.refresh() }
                }
                if (attempt >= 2) {
                    SystemClock.sleep(writeVerificationSettleMs)
                }
            }
            if (verifyWrittenValue(verificationRoot, field, expectedValue)) {
                return true
            }
        }
        return false
    }

    private fun writeValueUsingStrategies(node: AccessibilityNodeInfo, value: String): InputWriteResult {
        primeInputTarget(node)
        attemptSetTextBurst(node, value)?.let { return it }
        primeInputTarget(node, aggressive = true)
        attemptSetTextBurst(node, value, aggressive = true)?.let { return it }
        if (supportsSilentSetText(node) && primeInputTarget(node, aggressive = true)) {
            refreshInputTarget(node)
            attemptSetTextBurst(node, value, aggressive = true)?.let { return it }
        }
        primeInputTarget(node, aggressive = true)
        attemptPasteBurst(node, value)?.let { return it }
        if (primeInputTarget(node, aggressive = true)) {
            refreshInputTarget(node)
            attemptPasteBurst(node, value, aggressive = true)?.let { return it }
        }
        return InputWriteResult(wroteValue = false, likelyVerified = false)
    }

    private fun attemptSetTextBurst(
        node: AccessibilityNodeInfo,
        value: String,
        aggressive: Boolean = false
    ): InputWriteResult? {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return null
        repeat(SET_TEXT_BURST_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                if (aggressive) {
                    primeInputTarget(node, aggressive = true)
                } else {
                    refreshInputTarget(node)
                }
            }
            if (setTextOnNode(node, value)) {
                val likelyVerified = verifyDirectWriteBurst(node, value)
                if (likelyVerified || attempt == SET_TEXT_BURST_ATTEMPTS - 1) {
                    return InputWriteResult(
                        wroteValue = true,
                        likelyVerified = likelyVerified
                    )
                }
            }
        }
        return null
    }

    private fun attemptPasteBurst(
        node: AccessibilityNodeInfo,
        value: String,
        aggressive: Boolean = false
    ): InputWriteResult? {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) return null
        repeat(PASTE_BURST_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                if (aggressive) {
                    primeInputTarget(node, aggressive = true)
                } else {
                    refreshInputTarget(node)
                }
            }
            if (pasteValueOnNode(node, value)) {
                val likelyVerified = verifyDirectWriteBurst(node, value)
                if (likelyVerified || attempt == PASTE_BURST_ATTEMPTS - 1) {
                    return InputWriteResult(
                        wroteValue = true,
                        likelyVerified = likelyVerified
                    )
                }
            }
        }
        return null
    }

    private fun verifyDirectWriteBurst(node: AccessibilityNodeInfo, expectedValue: String): Boolean {
        repeat(DIRECT_WRITE_VERIFY_PASSES) { attempt ->
            if (attempt > 0) {
                refreshInputTarget(node)
            }
            if (isLikelyDirectWriteVerified(node, expectedValue)) {
                return true
            }
        }
        return false
    }

    private fun obtainInputTargets(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val targets = mutableListOf<AccessibilityNodeInfo>()
        collectPreferredInputTargets(node, targets, 0)
        collectNearbyInputTargets(node, targets, 0)
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        var depth = 0
        while (current != null && depth < INPUT_TARGET_DEPTH) {
            if (supportsDirectInput(current)) {
                targets += current
            } else {
                current.recycle()
            }
            current = try { current.parent?.let { AccessibilityNodeInfo.obtain(it) } } catch (_: Exception) { null }
            depth++
        }
        return rankAndDedupeInputTargets(targets)
    }

    private fun rankAndDedupeInputTargets(targets: MutableList<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        if (targets.size <= 1) return targets
        val ranked = targets.sortedByDescending { scoreDirectInputTarget(it) }
        val seen = HashSet<String>(ranked.size)
        val result = mutableListOf<AccessibilityNodeInfo>()
        ranked.forEach { target ->
            val key = buildInputTargetKey(target)
            if (seen.add(key)) {
                result += target
            } else {
                target.recycle()
            }
        }
        return result
    }

    private fun scoreDirectInputTarget(node: AccessibilityNodeInfo): Int {
        var score = scoreTextEntryCandidate(node) + scoreAggressiveTextEntryCandidate(node)
        if (try { node.isEditable } catch (_: Exception) { false }) score += 320
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) score += 260
        if (supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) score += 140
        if (try { node.isFocused } catch (_: Exception) { false }) score += 90
        if (try { node.isFocusable } catch (_: Exception) { false }) score += 60
        return score
    }

    private fun buildInputTargetKey(node: AccessibilityNodeInfo): String {
        val signature = buildInputNodeSignature(node)
        if (signature.isNotBlank()) return signature
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return listOf(
            className,
            viewId,
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
        ).joinToString("|")
    }

    private fun collectNearbyInputTargets(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        ancestorDepth: Int
    ) {
        if (ancestorDepth >= INPUT_NEARBY_SCOPE_DEPTH) return
        val parent = try { node.parent } catch (_: Exception) { null } ?: return
        try {
            for (i in 0 until parent.childCount) {
                val sibling = try { parent.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(sibling, into, 0)
                sibling.recycle()
            }
            collectNearbyInputTargets(parent, into, ancestorDepth + 1)
        } finally {
            parent.recycle()
        }
    }

    private fun collectPreferredInputTargets(
        node: AccessibilityNodeInfo,
        into: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > INPUT_DESCENT_DEPTH) return
        try {
            if (supportsDirectInput(node)) into += AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                collectPreferredInputTargets(child, into, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun supportsDirectInput(node: AccessibilityNodeInfo): Boolean =
        isTextEntryNode(node) ||
            supportsSilentSetText(node) ||
            isHiddenInputProxyCandidate(node)

    private fun focusInputTarget(node: AccessibilityNodeInfo) {
        val alreadyFocused = try { node.isFocused } catch (_: Exception) { false }
        if (alreadyFocused) return
        if (refocusInputTarget(node)) return
        activateInputTarget(node)
    }

    private fun refreshInputTarget(node: AccessibilityNodeInfo) {
        runCatching { node.refresh() }
    }

    private fun primeInputTarget(node: AccessibilityNodeInfo, aggressive: Boolean = false): Boolean {
        var changed = false
        if (refocusInputTarget(node)) {
            changed = true
        }
        if (activateInputTarget(node)) {
            changed = true
        }
        if (aggressive &&
            supportsAction(node, AccessibilityNodeInfo.ACTION_LONG_CLICK) &&
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }.getOrDefault(false)
        ) {
            changed = true
        }
        refreshInputTarget(node)
        return changed
    }

    private fun performSetTextAction(node: AccessibilityNodeInfo, value: String): Boolean =
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }.getOrDefault(false)

    private fun clearTextOnNode(node: AccessibilityNodeInfo) {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return
        runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })
        }
    }

    private fun reinforceTextWrite(node: AccessibilityNodeInfo, value: String): Boolean {
        var wroteValue = false
        repeat(2) { attempt ->
            if (attempt > 0) {
                primeInputTarget(node, aggressive = true)
                refreshInputTarget(node)
                clearTextOnNode(node)
            }
            if (performSetTextAction(node, value)) {
                wroteValue = true
                collapseInputSelection(node, value)
                if (isLikelyDirectWriteVerified(node, value)) {
                    return true
                }
            }
        }
        return wroteValue
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, value: String): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) return false
        if (performSetTextAction(node, value)) {
            collapseInputSelection(node, value)
            if (isLikelyDirectWriteVerified(node, value)) {
                return true
            }
        }
        clearTextOnNode(node)
        return reinforceTextWrite(node, value)
    }

    private fun pasteValueOnNode(node: AccessibilityNodeInfo, value: String): Boolean {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_PASTE)) return false
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        val previousClip = runCatching { clipboard.primaryClip }.getOrNull()
        val hadClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)
        return try {
            clearTextOnNode(node)
            clipboard.setPrimaryClip(ClipData.newPlainText("ussd_reply", value))
            val pasted = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }.getOrDefault(false)
            if (pasted) {
                collapseInputSelection(node, value)
                if (isLikelyDirectWriteVerified(node, value)) {
                    return true
                }
                if (supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)) {
                    return reinforceTextWrite(node, value)
                }
            }
            pasted
        } finally {
            runCatching {
                if (hadClip && previousClip != null) {
                    clipboard.setPrimaryClip(previousClip)
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        }
    }

    private fun isLikelyDirectWriteVerified(node: AccessibilityNodeInfo, expectedValue: String): Boolean {
        runCatching { node.refresh() }
        val readBack = readFieldText(node)
        return matchesExpectedInput(readBack, expectedValue)
    }

    private fun refocusInputTarget(node: AccessibilityNodeInfo): Boolean =
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }.getOrDefault(false) ||
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                runCatching {
                    node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                }.getOrDefault(false))

    private fun collapseInputSelection(node: AccessibilityNodeInfo, value: String) {
        if (!supportsAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION)) return
        val cursorPosition = value.length.coerceAtLeast(0)
        runCatching {
            node.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursorPosition)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursorPosition)
                }
            )
        }
    }

    private fun activateInputTarget(node: AccessibilityNodeInfo): Boolean {
        if (!isSafeInputActivationCandidate(node)) return false
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false) ||
            performTapGesture(node)
    }

    private fun supportsSilentSetText(node: AccessibilityNodeInfo): Boolean =
        supportsAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT)

    private fun isSafeInputActivationCandidate(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            normalizeActionLabel(try { node.hintText?.toString() } catch (_: Exception) { null })
        } else {
            ""
        }
        val editable = try { node.isEditable } catch (_: Exception) { false }
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        val visible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { node.isVisibleToUser } catch (_: Exception) { true }
        } else {
            true
        }
        val looksLikeInputClass = EDITABLE_CLASS_HINTS.any { className.contains(it, ignoreCase = true) } ||
            className.contains("TextInput", ignoreCase = true) ||
            className.contains("Edit", ignoreCase = true)
        return enabled && visible && (
            editable ||
                supportsSilentSetText(node) ||
                looksLikeInputClass ||
                hasInputViewHint(viewId, hint)
            )
    }

    private fun findEditableFieldMatchingExpectedInput(
        root: AccessibilityNodeInfo,
        expectedValue: String
    ): AccessibilityNodeInfo? =
        mutableListOf<AccessibilityNodeInfo>().let { candidates ->
            collectTextEntryCandidates(root, candidates)
            if (candidates.isEmpty()) {
                collectAggressiveTextEntryCandidates(root, candidates)
            }
            val verifiedMatch = candidates.firstOrNull { candidate ->
                isVerifiedFieldValue(readFieldText(candidate), expectedValue)
            }
            val preferred = verifiedMatch
                ?: candidates.maxByOrNull { candidate ->
                    scoreTextEntryCandidate(candidate) +
                        scoreExpectedInputFieldCandidate(candidate, expectedValue) +
                        if (matchesExpectedInput(readFieldText(candidate), expectedValue)) 700 else 0
                }
            val result = preferred?.let { AccessibilityNodeInfo.obtain(it) }
            candidates.forEach { it.recycle() }
            result
        }

    private fun scoreExpectedInputFieldCandidate(
        node: AccessibilityNodeInfo,
        expectedValue: String
    ): Int {
        val metadata = nodeFieldMetadata(node)
        if (metadata.isBlank()) return 0
        val normalizedExpected = normalizeInputValue(expectedValue)
        val expectedPhone = normalizePhoneComparable(expectedValue)
        var score = 0
        if (expectedPhone.isNotBlank() && PHONE_INPUT_HINTS.any { metadata.contains(it) }) score += 260
        if (normalizedExpected.all(Char::isDigit) && normalizedExpected.length in 4..8 &&
            (PIN_INPUT_HINTS.any { metadata.contains(it) } || CODE_INPUT_HINTS.any { metadata.contains(it) })
        ) score += 220
        if (normalizedExpected.all(Char::isDigit) && normalizedExpected.length >= 2 &&
            AMOUNT_INPUT_HINTS.any { metadata.contains(it) }
        ) score += 160
        if (normalizedExpected.any(Char::isLetter) && CODE_INPUT_HINTS.any { metadata.contains(it) }) score += 120
        return score
    }

    private fun isAggressiveActionCandidate(node: AccessibilityNodeInfo): Boolean {
        val enabled = try { node.isEnabled } catch (_: Exception) { true }
        if (!enabled) return false
        val editable = try { node.isEditable } catch (_: Exception) { false }
        if (editable) return false
        val actionable = try {
            node.isClickable || node.isFocusable || node.isLongClickable
        } catch (_: Exception) {
            false
        }
        val label = normalizeActionLabel(node.text?.toString())
        val desc = normalizeActionLabel(node.contentDescription?.toString())
        val viewId = normalizeActionLabel(try { node.viewIdResourceName } catch (_: Exception) { null })
        return actionable && (
            SEND_VIEW_ID_HINTS.any { viewId.contains(it) } ||
                SEND_BUTTON_LABELS.any { label.contains(it) || desc.contains(it) } ||
                label.isNotBlank() ||
                desc.isNotBlank()
            )
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
            val clicked = try {
                activeNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
