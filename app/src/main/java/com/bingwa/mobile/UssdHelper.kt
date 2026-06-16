package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

object UssdHelper {
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
        val tm = selectedTelephonyManager(context)
        if (tm == null) {
            val reason = "Telephony unavailable on this phone"
            if (!silentOnly) return fallbackToVisible(context, code, onSuccess, onFailure)
            onFailure?.invoke(reason)
            return false
        }
        val silentOk = SilentUssd.execute(tm, code, onSuccess = { onSuccess?.invoke(it) }, onFailure = { if (!silentOnly) fallbackToVisible(context, code, onSuccess, onFailure) else onFailure?.invoke(it) })
        if (silentOk) return true
        if (!silentOnly) return fallbackToVisible(context, code, onSuccess, onFailure)
        onFailure?.invoke("Silent unsupported"); return false
    }

    fun dialUssd(context: Context, ussdCode: String) {
        dialUssd(context, ussdCode, silentOnly = false, onSuccess = null, onFailure = null)
    }

    private fun fallbackToVisible(context: Context, code: String, onSuccess: ((String) -> Unit)?, onFailure: ((String) -> Unit)?): Boolean {
        try {
            val intent = buildCallIntent(context, code)
            if (intent.resolveActivity(context.packageManager) != null) { context.startActivity(intent); return false }
            else { onFailure?.invoke("No dialer"); return false }
        } catch (e: Exception) { onFailure?.invoke(e.message ?: "Error"); return false }
    }

    fun buildCallIntent(context: Context, ussdCode: String): Intent {
        val code = normalizeUssdCode(ussdCode)
        val uri = Uri.parse("tel:" + Uri.encode(code))
        val intent = Intent(Intent.ACTION_CALL).apply { data = uri; addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val subId = selectedSubId(context)
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

    private fun selectedSubId(context: Context): Int {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).safeGetInt("selected_sim_id", -1)
    }

    private fun selectedTelephonyManager(context: Context): TelephonyManager? {
        val subId = selectedSubId(context)
        val baseTm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return baseTm.createForSubscriptionId(subId)
        return baseTm
    }
}
