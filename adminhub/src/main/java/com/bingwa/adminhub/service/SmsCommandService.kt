package com.bingwa.adminhub.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bingwa.adminhub.data.models.*
import com.bingwa.adminhub.data.repositories.ScheduleRepository
import com.bingwa.adminhub.data.repositories.TokenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SmsCommandService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var tokenRepository: TokenRepository
    private lateinit var scheduleRepository: ScheduleRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val application = applicationContext as com.bingwa.adminhub.AdminHubApplication
        tokenRepository = TokenRepository(application.database.tokenDao())
        scheduleRepository = ScheduleRepository(application.database.scheduleDao())

        when (intent?.action) {
            ACTION_SEND_CODE -> handleSendCode(intent)
            ACTION_BULK_GIFT -> handleBulkGift(intent)
            ACTION_SCHEDULE -> handleSchedule(intent)
            ACTION_PING -> handlePing(intent)
        }

        return START_STICKY
    }

    private fun handleSendCode(intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        val recipient = intent.getStringExtra("recipient") ?: return
        val subId = intent.getIntExtra("sub_id", -1)
        val userId = intent.getStringExtra("user_id") ?: return
        val actionType = intent.getStringExtra("action_type") ?: return

        serviceScope.launch {
            val success = SmsSender.sendSms(this@SmsCommandService, recipient, code, subId)
            val status = if (success) TransactionStatus.SENT else TransactionStatus.FAILED
            val transaction = TokenTransaction(
                id = "tx_${System.currentTimeMillis()}",
                userId = userId,
                type = TokenType.valueOf(actionType.uppercase()),
                code = code,
                message = "Sent to $recipient",
                status = status
            )
            tokenRepository.addTransaction(transaction)
        }
    }

    private fun handleBulkGift(intent: Intent) {
        val code = intent.getStringExtra("code") ?: return
        val recipient = intent.getStringExtra("recipient") ?: return
        val subId = intent.getIntExtra("sub_id", -1)
        val userId = intent.getStringExtra("user_id") ?: return
        val delayMs = intent.getLongExtra("delay_ms", 30000L)
        val actionType = intent.getStringExtra("action_type") ?: "GIFT"

        serviceScope.launch {
            val success = SmsSender.sendSms(this@SmsCommandService, recipient, code, subId)
            val status = if (success) TransactionStatus.SENT else TransactionStatus.FAILED
            val transaction = TokenTransaction(
                id = "tx_${System.currentTimeMillis()}",
                userId = userId,
                type = TokenType.valueOf(actionType.uppercase()),
                code = code,
                message = "Bulk sent to $recipient",
                status = status
            )
            tokenRepository.addTransaction(transaction)
            delay(delayMs)
        }
    }

    private fun handleSchedule(intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        serviceScope.launch {
            try {
                val task = scheduleRepository.tasks.first().find { it.id == taskId } ?: return@launch
                val code = when (task.action) {
                    ScheduledAction.ACTIVATE -> OwnerCodeGenerator.generateActivateCode(task.code)
                    ScheduledAction.CLEAR -> OwnerCodeGenerator.generateClearCode()
                    ScheduledAction.GIFT -> OwnerCodeGenerator.generateGiftCode(0)
                    ScheduledAction.REMOTE_ADD -> OwnerCodeGenerator.generateRemoteAddCode(0)
                    ScheduledAction.SEND_SMS -> task.message
                    else -> return@launch
                }
                val recipient = intent.getStringExtra("recipient") ?: return@launch
                val subId = intent.getIntExtra("sub_id", -1)
                SmsSender.sendSms(this@SmsCommandService, recipient, code, subId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute scheduled task", e)
            }
        }
    }

    private fun handlePing(intent: Intent) {
        val recipient = intent.getStringExtra("recipient") ?: return
        val subId = intent.getIntExtra("sub_id", -1)
        serviceScope.launch {
            SmsSender.sendSms(this@SmsCommandService, recipient, "PING", subId)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Command Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SMS command processing active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Bingwa Admin Hub")
        .setContentText("Processing SMS commands...")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setOngoing(true)
        .build()

    companion object {
        const val TAG = "SmsCommandService"
        const val ACTION_SEND_CODE = "com.bingwa.adminhub.action.SEND_CODE"
        const val ACTION_BULK_GIFT = "com.bingwa.adminhub.action.BULK_GIFT"
        const val ACTION_SCHEDULE = "com.bingwa.adminhub.action.SCHEDULE"
        const val ACTION_PING = "com.bingwa.adminhub.action.PING"
        const val CHANNEL_ID = "adminhub_sms_channel"
        const val NOTIFICATION_ID = 1001
    }
}
