package com.bingwa.adminhub.service

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

object SmsSender {
    private const val TAG = "SmsSender"

    fun sendSms(context: Context, destination: String, message: String, preferredSubId: Int = -1): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SEND_SMS permission not granted")
            return false
        }

        try {
            val prefs = context.getSharedPreferences("adminhub_settings", Context.MODE_PRIVATE)
            val adminSubId = prefs.getInt("admin_sms_sim_id", -1)
            val ussdSubId = resolvePreferredUssdSubId(context) ?: -1
            val subId = listOf(preferredSubId, ussdSubId, adminSubId).firstOrNull { it != -1 } ?: -1

            val managers = buildList {
                if (subId != -1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    runCatching { add(SmsManager.getSmsManagerForSubscriptionId(subId)) }
                }
                add(SmsManager.getDefault())
            }.distinctBy { it.hashCode() }

            val candidates = buildList {
                add(destination.trim())
                add(normalizePhoneForSms(destination))
            }.distinct().filter { it.isNotBlank() }

            managers.forEach { mgr ->
                candidates.forEach { dest ->
                    try {
                        val parts = mgr.divideMessage(message)
                        mgr.sendMultipartTextMessage(dest, null, parts, null, null)
                        Log.i(TAG, "SMS sent to $dest via subId=$subId: $message")
                        return true
                    } catch (e: Exception) {
                        Log.e(TAG, "SMS send failed dest=$dest", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendSms failed", e)
        }
        return false
    }

    fun sendCode(context: Context, code: String, recipient: String, subId: Int = -1): Boolean {
        return sendSms(context, recipient, code, subId)
    }

    fun buildAutoReply(phone: String, amount: Double, message: String? = null): String {
        return message ?: "Thank you for purchasing tokens on Bingwa Mobile. KSH %.0f received from %s. Your account has been credited.".format(amount, phone)
    }

    private fun resolvePreferredUssdSubId(context: Context): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val sm = context.getSystemService(SubscriptionManager::class.java)
                val activeSubs = sm.activeSubscriptionInfoList ?: return null
                if (activeSubs.size == 1) {
                    activeSubs[0].subscriptionId
                } else {
                    val prefs = context.getSharedPreferences("adminhub_settings", Context.MODE_PRIVATE)
                    prefs.getInt("ussd_sim_id", -1).takeIf { it != -1 }
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizePhoneForSms(phone: String): String {
        val raw = phone.trim().replace("+", "").replace(Regex("\\D+"), "")
        return when {
            raw.startsWith("254") && raw.length >= 12 -> raw.take(12)
            raw.length == 10 && raw.startsWith("0") -> "254${raw.drop(1)}"
            raw.length == 9 && (raw.startsWith("7") || raw.startsWith("1")) -> "254$raw"
            else -> raw
        }
    }
}
