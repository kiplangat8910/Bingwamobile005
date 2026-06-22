package com.bingwa.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fast best-effort name lookup for manual runs:
 * scans a limited number of latest M-PESA inbox messages and tries to match the provided phone.
 */
@SuppressLint("Range")
internal fun resolveClientNameFromRecentMpesaSms(
    context: Context,
    phone: String,
    maxMessages: Int = 160,
    daysBack: Int = 365
): String {
    val normalized = SmsCommandHandler.normalizePhone(phone)
    if (!normalized.matches(Regex("^0\\d{9}$"))) return ""
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return ""

    val cutoff = System.currentTimeMillis() - (daysBack * 86_400_000L)
    val receiver = MpesaReceiver()
    val wantedDigits = normalized.drop(1) // 7xxxxxxxx
    val wantedPrefix = normalized.take(4) // 07xx
    val wantedSuffix = normalized.takeLast(3)
    var scanned = 0

    return try {
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER
        )?.use { c ->
            while (c.moveToNext() && scanned < maxMessages) {
                scanned++
                val address = c.getString(c.getColumnIndex(Telephony.Sms.ADDRESS)) ?: continue
                val date = c.getLong(c.getColumnIndex(Telephony.Sms.DATE))
                if (date < cutoff) break
                if (!address.equals("MPESA", true) && !address.equals("M-PESA", true)) continue

                val body = c.getString(c.getColumnIndex(Telephony.Sms.BODY)) ?: continue
                if (!body.contains(wantedDigits) && !(body.contains(wantedPrefix) && body.contains(wantedSuffix))) continue

                val rawPhone = receiver.extractPhoneOrMasked(body)
                val extractedName = formatClientName(receiver.extractClientName(body))
                val resolvedPhone = receiver.resolveMaskedNumber(context, rawPhone, extractedName)
                if (SmsCommandHandler.normalizePhone(resolvedPhone) != normalized) continue
                if (extractedName.isBlank()) continue
                return extractedName
            }
            ""
        } ?: ""
    } catch (e: Exception) {
        Log.e("MpesaNameResolver", "resolveClientNameFromRecentMpesaSms failed", e)
        ""
    }
}

