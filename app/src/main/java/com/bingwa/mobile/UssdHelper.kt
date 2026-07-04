package com.bingwa.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

object UssdHelper {
    // Some OEM dialers keep reclaiming focus for a short time after the initial call launch.
    // A few later retries help restore the app UI on slower or customized Android builds.
    private val RETURN_TO_APP_DELAYS_MS = longArrayOf(0L, 120L, 300L, 650L, 1_100L, 1_800L)

    fun normalizeRecipientForUssdInput(phone: String): String {
        val digits = phone.trim().replace("+", "").replace(Regex("\\D+"), "")
        if (digits.isBlank()) return ""

        val local = SmsCommandHandler.normalizePhone(phone).replace(Regex("\\D+"), "")
        return when {
            local.length == 10 && local.startsWith("0") -> local
            local.length == 9 && (local.startsWith("7") || local.startsWith("1")) -> "0$local"
            digits.length == 12 && digits.startsWith("254") -> "0${digits.drop(3)}"
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0$digits"
            digits.length == 10 && digits.startsWith("0") -> digits
            else -> digits
        }
    }

    fun normalizeUssdCode(raw: String, phoneNumber: String? = null): String {
        val v = raw.trim()
            .let {
                if (phoneNumber != null) {
                    it.replace("pn", normalizeRecipientForUssdInput(phoneNumber), ignoreCase = true)
                } else {
                    it
                }
            }
            .replace("%23", "#")
        if (v.startsWith("*") && !v.endsWith("#")) return "$v#"
        return v
    }

    fun dialUssd(context: Context, ussdCode: String, silentOnly: Boolean = false, onSuccess: ((String) -> Unit)? = null, onFailure: ((String) -> Unit)? = null): Boolean {
        val code = normalizeUssdCode(ussdCode)
        Log.d("UssdHelper", "Dialing: $code")
        val targets = resolveUssdSimTargets(context)
        return dialUssdAttempt(
            context = context,
            code = code,
            silentOnly = silentOnly,
            targets = targets,
            attemptIndex = 0,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    fun dialUssd(context: Context, ussdCode: String) {
        dialUssd(context, ussdCode, silentOnly = false, onSuccess = null, onFailure = null)
    }

    private fun dialUssdAttempt(
        context: Context,
        code: String,
        silentOnly: Boolean,
        targets: List<UssdSimTarget>,
        attemptIndex: Int,
        onSuccess: ((String) -> Unit)?,
        onFailure: ((String) -> Unit)?
    ): Boolean {
        val target = targets.getOrNull(attemptIndex)
        val tm = selectedTelephonyManager(context, target?.subId)
        if (tm == null) {
            val reason = "Telephony unavailable on this phone"
            return if (attemptIndex + 1 < targets.size) {
                dialUssdAttempt(context, code, silentOnly, targets, attemptIndex + 1, onSuccess, onFailure)
            } else {
                if (!silentOnly) {
                    fallbackToVisible(context, code, onSuccess, onFailure, target?.subId)
                } else {
                    onFailure?.invoke(reason)
                    false
                }
            }
        }

        val slotLabel = target?.slotIndex?.let { "slot ${it + 1}" } ?: "default telephony"
        Log.d("UssdHelper", "Trying USSD on $slotLabel")

        val silentOk = SilentUssd.execute(
            tm,
            code,
            onSuccess = { onSuccess?.invoke(it) },
            onFailure = { error ->
                val nextTarget = targets.getOrNull(attemptIndex + 1)
                if (nextTarget != null) {
                    Log.w(
                        "UssdHelper",
                        "USSD failed on $slotLabel, retrying slot ${nextTarget.slotIndex + 1}: $error"
                    )
                    dialUssdAttempt(context, code, silentOnly, targets, attemptIndex + 1, onSuccess, onFailure)
                } else if (!silentOnly) {
                    fallbackToVisible(context, code, onSuccess, onFailure, target?.subId)
                } else {
                    onFailure?.invoke(error)
                }
            }
        )
        if (silentOk) return true

        return if (attemptIndex + 1 < targets.size) {
            val nextTarget = targets[attemptIndex + 1]
            Log.w(
                "UssdHelper",
                "Silent USSD could not start on $slotLabel, retrying slot ${nextTarget.slotIndex + 1}"
            )
            dialUssdAttempt(context, code, silentOnly, targets, attemptIndex + 1, onSuccess, onFailure)
        } else {
            if (!silentOnly) {
                fallbackToVisible(context, code, onSuccess, onFailure, target?.subId)
            } else {
                onFailure?.invoke("Silent unsupported")
                false
            }
        }
    }

    private fun fallbackToVisible(
        context: Context,
        code: String,
        onSuccess: ((String) -> Unit)?,
        onFailure: ((String) -> Unit)?,
        subIdOverride: Int? = null
    ): Boolean {
        try {
            val intent = buildCallIntent(context, code, subIdOverride)
            if (intent.resolveActivity(context.packageManager) != null) {
                val keepAppUiVisible = BingwaMobileApp.wasInForegroundRecently()
                if (keepAppUiVisible) UssdNavigationService.armForegroundUi()
                context.startActivity(intent)
                if (keepAppUiVisible) relaunchAppUi(context)
                return false
            }
            else { onFailure?.invoke("No dialer"); return false }
        } catch (e: SecurityException) {
            OfferNotifications.notify(context, "Permission required", "Open Bingwa Mobile and allow call permission to continue USSD.")
            onFailure?.invoke(e.message ?: "SecurityException")
            return false
        } catch (e: Exception) {
            OfferNotifications.notify(context, "Open app to continue", "Tap to complete USSD request.")
            onFailure?.invoke(e.message ?: "Error")
            return false
        }
    }

    fun buildCallIntent(context: Context, ussdCode: String, subIdOverride: Int? = null): Intent {
        val code = normalizeUssdCode(ussdCode)
        val uri = Uri.parse("tel:" + Uri.encode(code))
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action).apply { data = uri; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val subId = subIdOverride ?: resolvePreferredUssdSubId(context) ?: -1
        if (subId != -1) {
            intent.putExtra("android.telephony.extra.SUBSCRIPTION_ID", subId)
            intent.putExtra("subscription", subId)
            val slot = runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
                    null
                } else {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    sm?.getActiveSubscriptionInfo(subId)?.simSlotIndex
                }
            }.getOrNull()
            if (slot != null && slot >= 0) {
                intent.putExtra("com.android.phone.force.slot", slot)
                intent.putExtra("com.android.phone.extra.slot", slot)
                intent.putExtra("slot", slot)
                intent.putExtra("simSlot", slot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val handle = telecom?.callCapablePhoneAccounts
                    ?.firstOrNull { h ->
                        h.id == subId.toString() ||
                            h.id.contains(subId.toString()) ||
                            runCatching {
                                telecom.getPhoneAccount(h)?.extras?.let { ex ->
                                    ex.getInt("android.telephony.extra.SUBSCRIPTION_ID", -1) == subId ||
                                        ex.getInt("subscription_id", -1) == subId ||
                                        (slot != null && (ex.getInt("android.telephony.extra.SLOT_INDEX", -1) == slot || ex.getInt("slot_index", -1) == slot))
                                } ?: false
                            }.getOrDefault(false)
                    }
                if (handle != null) intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
        }
        return intent
    }

    fun relaunchAppUi(
        context: Context,
        delayMs: Long = RETURN_TO_APP_DELAYS_MS.first(),
        aggressiveRetries: Boolean = true
    ) {
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        val handler = Handler(Looper.getMainLooper())
        val delays = if (aggressiveRetries) {
            buildList {
                add(delayMs.coerceAtLeast(0L))
                RETURN_TO_APP_DELAYS_MS.forEach { scheduled ->
                    if (scheduled > delayMs) add(scheduled)
                }
            }.distinct()
        } else {
            listOf(delayMs.coerceAtLeast(0L))
        }
        delays.forEach { scheduledDelay ->
            val action = Runnable {
                runCatching { context.startActivity(appIntent) }
                    .onFailure { Log.w("UssdHelper", "Unable to relaunch app UI after USSD start", it) }
            }
            if (scheduledDelay <= 0L) {
                handler.post(action)
            } else {
                handler.postDelayed(action, scheduledDelay)
            }
        }
    }

    private fun selectedTelephonyManager(context: Context, subIdOverride: Int? = null): TelephonyManager? {
        val subId = subIdOverride ?: resolvePreferredUssdSubId(context) ?: -1
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return baseTm.createForSubscriptionId(subId)
        return baseTm
    }
}
