package com.bingwa.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log

object AlarmCompat {
    private const val TAG = "AlarmCompat"

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
    }

    fun scheduleRtcWakeup(
        context: Context,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        preferExact: Boolean = true,
        allowWhileIdle: Boolean = true
    ): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return runCatching {
            val exactAllowed = !preferExact || canScheduleExactAlarms(context)
            when {
                allowWhileIdle && exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                preferExact && exactAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ->
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                else ->
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to schedule alarm at $triggerAtMillis", error)
            false
        }
    }
}
