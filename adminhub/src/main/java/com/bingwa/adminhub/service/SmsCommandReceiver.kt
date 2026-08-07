package com.bingwa.adminhub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import com.bingwa.adminhub.data.parser.SmsPurchaseParser
import com.bingwa.adminhub.data.models.PurchaseSms
import com.bingwa.adminhub.data.repositories.PurchaseRepository
import com.bingwa.adminhub.data.repositories.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsCommandReceiver : BroadcastReceiver() {
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val fullMessage = messages?.joinToString("\n") { it.messageBody ?: "" } ?: return

            Log.d(TAG, "SMS received: $fullMessage")

            val purchase = SmsPurchaseParser.parse(fullMessage)
            if (purchase != null) {
                Log.i(TAG, "Purchase detected: ${purchase.phone} - ${purchase.amount} KSH")
                val application = context.applicationContext as com.bingwa.adminhub.AdminHubApplication
                val purchaseRepository = PurchaseRepository(application.database.purchaseDao())
                val userRepository = UserRepository(application.database.userDao())

                receiverScope.launch {
                    purchaseRepository.addPurchase(purchase)

                    val existingUser = userRepository.getUser(purchase.phone)
                    if (existingUser == null) {
                        val newUser = com.bingwa.adminhub.data.models.AdminUser(
                            id = "user_${purchase.phone}",
                            phone = purchase.phone,
                            name = "User ${purchase.phone.takeLast(4)}",
                            category = "Auto-added",
                            notes = "Added from purchase SMS on ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(purchase.timestamp))}"
                        )
                        userRepository.addUser(newUser)
                        Log.i(TAG, "Auto-added user: ${newUser.phone}")
                    }

                    val prefs = context.getSharedPreferences("adminhub_settings", Context.MODE_PRIVATE)
                    val autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", true)
                    if (autoReplyEnabled) {
                        val autoReply = SmsSender.buildAutoReply(purchase.phone, purchase.amount)
                        val simId = prefs.getInt("admin_sms_sim_id", -1)
                        SmsSender.sendSms(context, purchase.phone, autoReply, simId)
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "SmsCommandReceiver"
    }
}
