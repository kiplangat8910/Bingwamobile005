package com.bingwa.mobile

import android.content.Context
import android.os.SystemClock
import kotlin.math.abs

class UnlimitedManager(private val context: Context) {
    data class Plan(val id: String, val label: String, val ksh: Int, val durationMs: Long)

    companion object {
        val PLANS = listOf(
            Plan("DAILY", "Daily", 20, 24L * 60L * 60L * 1000L),
            Plan("WEEKLY", "Weekly", 140, 7L * 24L * 60L * 60L * 1000L),
            Plan("MONTHLY", "Monthly", 480, 30L * 24L * 60L * 60L * 1000L)
        )

        fun planForAmount(ksh: Int): Plan? = PLANS.firstOrNull { it.ksh == ksh }
    }

    private val prefs = context.getSharedPreferences("UnlimitedStore", Context.MODE_PRIVATE)

    fun activate(plan: Plan) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putString("plan_id", plan.id)
            .putString("plan_label", plan.label)
            .putInt("plan_ksh", plan.ksh)
            .putLong("duration_ms", plan.durationMs)
            .putLong("start_wall", nowWall)
            .putLong("start_elapsed", nowElapsed)
            .putLong("last_wall", nowWall)
            .putLong("last_elapsed", nowElapsed)
            .putBoolean("tampered", false)
            .apply()
    }

    fun activateCustom(id: String, label: String, durationMs: Long) {
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        prefs.edit()
            .putString("plan_id", id)
            .putString("plan_label", label)
            .putInt("plan_ksh", 0)
            .putLong("duration_ms", durationMs)
            .putLong("start_wall", nowWall)
            .putLong("start_elapsed", nowElapsed)
            .putLong("last_wall", nowWall)
            .putLong("last_elapsed", nowElapsed)
            .putBoolean("tampered", false)
            .apply()
    }

    fun getActivePlan(): Plan? {
        val id = prefs.safeGetString("plan_id", null) ?: return null
        return PLANS.firstOrNull { it.id == id } ?: run {
            val label = prefs.safeGetString("plan_label", "") ?: ""
            val ksh = prefs.safeGetInt("plan_ksh", 0)
            val duration = prefs.safeGetLong("duration_ms", 0L)
            if (label.isBlank() || duration <= 0L) null else Plan(id, label, ksh, duration)
        }
    }

    fun isActive(): Boolean = remainingMs() > 0L

    fun remainingMs(): Long {
        if (prefs.safeGetBoolean("tampered", false)) return 0L

        val duration = prefs.safeGetLong("duration_ms", 0L)
        val startWall = prefs.safeGetLong("start_wall", 0L)
        val startElapsed = prefs.safeGetLong("start_elapsed", 0L)
        if (duration <= 0L || (startWall <= 0L && startElapsed <= 0L)) return 0L

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastWall = prefs.safeGetLong("last_wall", 0L)
        val lastElapsed = prefs.safeGetLong("last_elapsed", 0L)

        val tampered = when {
            lastWall > 0L && nowWall < lastWall - 60_000L -> true
            lastWall > 0L && lastElapsed > 0L -> {
                val wallDelta = nowWall - lastWall
                val elapsedDelta = nowElapsed - lastElapsed
                elapsedDelta >= 0L && abs(wallDelta - elapsedDelta) > 5L * 60_000L
            }
            else -> false
        }

        if (tampered) {
            prefs.edit().putBoolean("tampered", true).apply()
            return 0L
        }

        val useElapsed = startElapsed > 0L && nowElapsed >= startElapsed
        val end = if (useElapsed) startElapsed + duration else startWall + duration
        val now = if (useElapsed) nowElapsed else nowWall
        val rem = end - now

        prefs.edit().putLong("last_wall", nowWall).putLong("last_elapsed", nowElapsed).apply()

        if (rem <= 0L) {
            clear()
            return 0L
        }
        return rem
    }

    fun clear() {
        prefs.edit()
            .remove("plan_id")
            .remove("plan_label")
            .remove("plan_ksh")
            .remove("duration_ms")
            .remove("start_wall")
            .remove("start_elapsed")
            .remove("last_wall")
            .remove("last_elapsed")
            .remove("tampered")
            .apply()
    }

    fun remainingLabel(): String {
        val rem = remainingMs()
        if (rem <= 0L) return "Expired"
        val totalMinutes = rem / 60_000L
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L
        return when {
            days > 0L -> "${days}d ${hours}h ${minutes}m remaining"
            hours > 0L -> "${hours}h ${minutes}m remaining"
            else -> "${minutes}m remaining"
        }
    }
}
