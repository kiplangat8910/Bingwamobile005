package com.bingwa.adminhub.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bingwa.adminhub.service.SmsCommandService

object Scheduler {
    private const val TAG = "Scheduler"

    fun scheduleTask(context: Context, taskId: String, triggerAt: Long, repeatInterval: Long? = null) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SmsCommandService::class.java).apply {
            action = SmsCommandService.ACTION_SCHEDULE
            putExtra("task_id", taskId)
        }

        val pendingIntent = PendingIntent.getService(
            context,
            taskId.hashCode() and 0xFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (repeatInterval != null && repeatInterval > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                Log.i(TAG, "Repeating task scheduled: $taskId at $triggerAt, interval $repeatInterval")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                Log.i(TAG, "One-time task scheduled: $taskId at $triggerAt")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing SCHEDULE_EXACT_ALARM permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule task", e)
        }
    }

    fun cancelTask(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SmsCommandService::class.java)
        val pendingIntent = PendingIntent.getService(
            context,
            taskId.hashCode() and 0xFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Task cancelled: $taskId")
    }

    fun getRepeatInterval(repeatMode: String): Long? {
        return when (repeatMode) {
            "DAILY" -> AlarmManager.INTERVAL_DAY
            "WEEKLY" -> AlarmManager.INTERVAL_DAY * 7
            "MONTHLY" -> AlarmManager.INTERVAL_DAY * 30
            else -> null
        }
    }
}
