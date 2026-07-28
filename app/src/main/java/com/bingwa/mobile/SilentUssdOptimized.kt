package com.bingwa.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object SilentUssdOptimized {
    private const val TAG = "SilentUssd"
    private const val TIMEOUT_MS = 15_000L
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 500L

    @Volatile private var successCb: ((String) -> Unit)? = null
    @Volatile private var failureCb: ((String) -> Unit)? = null
    private var timeoutRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val isProcessing = AtomicBoolean(false)
    private val methodCache = mutableMapOf<String, Method>()
    private val requestCounter = AtomicLong(0L)
    @Volatile private var activeRequestId: Long = 0L
    private var retryCount = 0
    private var lastError: String? = null

    fun execute(
        context: Context,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: run { onFailure("No TelephonyManager"); return false }
        return execute(tm, ussdCode, onSuccess, onFailure)
    }

    fun execute(
        telephonyManager: TelephonyManager,
        ussdCode: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ): Boolean {
        if (!isProcessing.compareAndSet(false, true)) {
            onFailure("USSD execution already in progress")
            return false
        }

        val code = normaliseCode(ussdCode)
        val requestId = requestCounter.incrementAndGet()
        retryCount = 0
        lastError = null
        Log.d(TAG, "execute: code=$code sdk=${Build.VERSION.SDK_INT}")

        successCb = onSuccess
        failureCb = onFailure
        activeRequestId = requestId
        armTimeout(requestId, code)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (tryPublicApi(telephonyManager, code, requestId)) return true
        }

        if (tryReflectionApi(telephonyManager, code, requestId)) return true

        if (retryCount < MAX_RETRIES) {
            retryCount++
            Log.w(TAG, "Silent USSD failed, retry $retryCount/$MAX_RETRIES after ${RETRY_DELAY_MS}ms")
            handler.postDelayed({
                if (!isProcessing.compareAndSet(false, true)) {
                    Log.w(TAG, "Retry aborted: another USSD request is already in progress")
                    return@postDelayed
                }
                activeRequestId = 0L
                successCb = null
                failureCb = null
                cancelTimeout()
                execute(telephonyManager, ussdCode, onSuccess, onFailure)
            }, RETRY_DELAY_MS)
            return true
        }

        isProcessing.set(false)
        activeRequestId = 0L
        successCb = null
        failureCb = null
        cancelTimeout()
        return false
    }

    fun isSilentUssdSupported(context: Context): Boolean {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return true
        return hasReflectionMethod(tm)
    }

    fun isExecutionInProgress(): Boolean = isProcessing.get()

    @SuppressLint("MissingPermission")
    private fun tryPublicApi(tm: TelephonyManager, code: String, requestId: Long): Boolean {
        return try {
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(
                    telephonyManager: TelephonyManager,
                    request: String,
                    response: CharSequence
                ) {
                    Log.d(TAG, "publicApi onReceiveUssdResponse")
                    val cleanResponse = extractUssdPopupResponse(response.toString())
                    deliverSuccess(requestId, cleanResponse)
                }

                override fun onReceiveUssdResponseFailed(
                    telephonyManager: TelephonyManager,
                    request: String,
                    failureCode: Int
                ) {
                    Log.w(TAG, "publicApi onReceiveUssdResponseFailed: $failureCode")
                    deliverFailure(requestId, "USSD failed: $failureCode")
                }
            }, handler)
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryPublicApi failed: ${e.message}")
            false
        }
    }

    private fun tryReflectionApi(tm: TelephonyManager, code: String, requestId: Long): Boolean {
        val candidates = listOf(
            "android.telephony.TelephonyManager" to "sendUssdRequest",
            "com.android.internal.telephony.Phone" to "sendUssdResponse",
            "android.telephony.TelephonyManager" to "handleUssdRequest"
        )

        for ((className, methodName) in candidates) {
            try {
                val callbackClass = Class.forName("android.telephony.TelephonyManager\$UssdResponseCallback")
                    ?: Class.forName("android.telephony.UssdResponseCallback")

                val proxy = buildCallbackProxy(callbackClass, requestId)
                val targetClass = if (className == "android.telephony.TelephonyManager") tm.javaClass
                else Class.forName(className)

                // Use cached method if available
                val cacheKey = "$className.$methodName"
                val method = methodCache.getOrPut(cacheKey) {
                    targetClass.getMethod(
                        methodName,
                        String::class.java,
                        callbackClass,
                        Handler::class.java
                    ).apply { isAccessible = true }
                }

                method.invoke(
                    if (className == "android.telephony.TelephonyManager") tm else null,
                    code, proxy, handler
                )
                return true
            } catch (_: Exception) { /* try next */ }
        }

        return false
    }

    private fun buildCallbackProxy(callbackClass: Class<*>, requestId: Long): Any {
        return Proxy.newProxyInstance(
            callbackClass.classLoader,
            arrayOf(callbackClass),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    when (method.name) {
                        "onReceiveUssdResponse" -> {
                            val response = args?.lastOrNull { it is CharSequence } as? CharSequence
                                ?: args?.getOrNull(2) as? CharSequence
                                ?: args?.getOrNull(1) as? CharSequence
                            val cleanResponse = extractUssdPopupResponse(response?.toString() ?: "")
                            deliverSuccess(requestId, cleanResponse)
                        }
                        "onReceiveUssdResponseFailed" -> {
                            val failureCode = args?.filterIsInstance<Int>()?.firstOrNull() ?: -1
                            deliverFailure(requestId, "USSD failed: $failureCode")
                        }
                    }
                    return null
                }
            }
        )
    }

    private fun extractUssdPopupResponse(rawResponse: String): String {
        if (rawResponse.isBlank()) return ""
        val trimmed = rawResponse.trim()

        val isMenuPopup = trimmed.contains(Regex("""\d+\s*[\)\].:\-]"""))
        val hasUssdKeywords = listOf(
            "success", "failed", "error", "balance", "ksh", "kes",
            "thank you", "wait", "enter", "confirm", "please", "option",
            "try again", "maintained", "process", "received", "activated",
            "bundle", "data", "airtime", "loan", "refund", "withdraw",
            "transfer", "send", "buy", "purchase", "subscription",
            "completed", "successful", "unsuccessful", "insufficient"
        ).any { keyword -> trimmed.lowercase().contains(keyword) }

        val lines = trimmed.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return trimmed

        // Single line or menu popup: return as-is
        if (lines.size <= 2 || isMenuPopup) return trimmed

        // Has USSD keywords: return full trimmed response
        if (hasUssdKeywords) return trimmed

        // Multi-line without keywords: extract the most meaningful content
        // Prioritize lines with actionable content (numbers, amounts, confirmations)
        val meaningfulLines = lines.filter { line ->
            line.length >= 5 &&
            (line.contains(Regex("""\d""")) ||
            listOf("success", "fail", "error", "confirm", "enter", "select", "choose", "amount", "balance", "ksh", "kes", "paid", "received", "sent", "to", "from", "account", "ref", "transaction", "id", "code").any { line.lowercase().contains(it) })
        }

        return if (meaningfulLines.isNotEmpty()) {
            meaningfulLines.joinToString("\n")
        } else {
            // Fallback: first 3 meaningful lines
            lines.take(3).joinToString("\n")
        }
    }

    private fun hasReflectionMethod(tm: TelephonyManager): Boolean {
        return try {
            val cb = try {
                Class.forName("android.telephony.TelephonyManager\$UssdResponseCallback")
            } catch (_: Exception) {
                Class.forName("android.telephony.UssdResponseCallback")
            }
            tm.javaClass.getMethod("sendUssdRequest", String::class.java, cb, Handler::class.java)
            true
        } catch (_: Exception) { false }
    }

    private fun deliverSuccess(requestId: Long, response: String) {
        synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD success for requestId=$requestId")
                return
            }
            cancelTimeout()
            val cb = successCb
            successCb = null
            failureCb = null
            activeRequestId = 0L
            isProcessing.set(false)
            val enrichedResponse = if (retryCount > 0) {
                "$response\n[Silent USSD succeeded after $retryCount retry/retries]"
            } else response
            handler.post { cb?.invoke(enrichedResponse) }
        }
    }

    private fun deliverFailure(requestId: Long, reason: String) {
        synchronized(this) {
            if (requestId != activeRequestId) {
                Log.d(TAG, "Ignoring stale USSD failure for requestId=$requestId")
                return
            }
            cancelTimeout()
            val cb = failureCb
            successCb = null
            failureCb = null
            activeRequestId = 0L
            isProcessing.set(false)
            lastError = reason
            handler.post { cb?.invoke(reason) }
        }
    }

    private fun armTimeout(requestId: Long, code: String) {
        cancelTimeout()
        val timeout = Runnable {
            Log.w(TAG, "USSD timeout for $code")
            deliverFailure(requestId, "Timeout waiting for USSD response on a weak or delayed network")
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun normaliseCode(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("*") && trimmed.endsWith("#") -> trimmed
            trimmed.startsWith("*") -> "$trimmed#"
            else -> {
                val decoded = trimmed.replace("%23", "#")
                when {
                    decoded.startsWith("*") && decoded.endsWith("#") -> decoded
                    decoded.startsWith("*") -> "$decoded#"
                    else -> decoded
                }
            }
        }
    }
}
