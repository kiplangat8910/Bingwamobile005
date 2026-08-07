package com.bingwa.adminhub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bingwa.adminhub.data.repositories.ScheduleRepository
import com.bingwa.adminhub.util.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val bootScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Boot completed, starting SmsCommandService")
            val serviceIntent = Intent(context, SmsCommandService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(serviceIntent)
            }

            val application = context.applicationContext as com.bingwa.adminhub.AdminHubApplication
            val scheduleRepository = ScheduleRepository(application.database.scheduleDao())
            bootScope.launch {
                try {
                    val tasks = scheduleRepository.tasks.first().filter { it.enabled }
                    tasks.forEach { task ->
                        Scheduler.scheduleTask(
                            context = context,
                            taskId = task.id,
                            triggerAt = task.scheduledAt,
                            repeatInterval = Scheduler.getRepeatInterval(task.repeat.name)
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reschedule tasks after boot", e)
                }
            }
        }
    }

    companion object {
        const val TAG = "BootReceiver"
    }
}

