package com.bingwa.adminhub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val fullMessage = messages?.joinToString("\n") { it.messageBody ?: "" } ?: return

            Log.d(TAG, "SMS received: $fullMessage")

            val purchase = com.bingwa.adminhub.data.parser.SmsPurchaseParser.parse(fullMessage)
            if (purchase != null) {
                Log.i(TAG, "Purchase detected: ${purchase.phone} - ${purchase.amount} KSH")
                val serviceIntent = Intent(context, SmsCommandService::class.java).apply {
                    action = SmsCommandService.ACTION_SEND_SMS
                    putExtra("purchase_phone", purchase.phone)
                    putExtra("purchase_amount", purchase.amount)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    @Suppress("DEPRECATION")
                    context.startService(serviceIntent)
                }
            }
        }
    }

    companion object {
        const val TAG = "SmsCommandReceiver"
    }
}
