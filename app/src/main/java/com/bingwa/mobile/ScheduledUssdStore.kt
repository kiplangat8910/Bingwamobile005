package com.bingwa.mobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledUssd(
    val txId: Int,
    val offerId: Int,
    val offerName: String,
    val phoneNumber: String,
    val ussdCode: String,
    val executionMode: String,
    val triggerAtMillis: Long,
    val createdAtMillis: Long
)

internal object ScheduledUssdStore {
    private const val PREFS_NAME = "scheduled_ussd"
    private const val KEY_LIST = "list"
    const val ACTION_SCHEDULES_UPDATED = "com.bingwa.mobile.SCHEDULES_UPDATED"
    private val lock = Any()

    @Volatile
    private var cachedRawJson: String? = null

    @Volatile
    private var cachedSchedules: List<ScheduledUssd>? = null

    fun load(context: Context): List<ScheduledUssd> {
        return synchronized(lock) {
            val rawJson = rawJson(context)
            val cached = cachedSchedules
            if (rawJson == cachedRawJson && cached != null) return@synchronized cached.toList()
            val parsed = parse(rawJson)
            updateCache(rawJson, parsed)
            parsed
        }
    }

    fun upsert(context: Context, item: ScheduledUssd) {
        val updated = synchronized(lock) {
            val current = load(context)
                .filterNot { it.txId == item.txId }
                .toMutableList()
            current += item
            val normalized = current
                .filter { it.txId >= 0 && it.triggerAtMillis > 0L && it.phoneNumber.isNotBlank() && it.ussdCode.isNotBlank() }
                .sortedBy { it.triggerAtMillis }
            saveLocked(context, normalized)
            normalized
        }
        broadcastUpdated(context)
    }

    fun remove(context: Context, txId: Int): ScheduledUssd? {
        val removed = synchronized(lock) {
            val current = load(context)
            val match = current.firstOrNull { it.txId == txId }
            if (match == null) return@synchronized null
            val remaining = current.filterNot { it.txId == txId }
            saveLocked(context, remaining)
            match
        }
        cancelAlarm(context, txId)
        broadcastUpdated(context)
        return removed
    }

    fun findByTxId(context: Context, txId: Int): ScheduledUssd? {
        if (txId < 0) return null
        return load(context).firstOrNull { it.txId == txId }
    }

    fun scheduleAlarm(context: Context, item: ScheduledUssd): Boolean {
        if (item.txId < 0 || item.triggerAtMillis <= 0L) return false
        val intent = Intent(context, ScheduledUssdReceiver::class.java).apply {
            action = ScheduledUssdReceiver.ACTION_RUN_SCHEDULED
            putExtra("txId", item.txId)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            item.txId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return AlarmCompat.scheduleRtcWakeup(
            context = context,
            triggerAtMillis = item.triggerAtMillis,
            pendingIntent = pi,
            preferExact = true,
            allowWhileIdle = true
        )
    }

    fun restoreAlarms(context: Context) {
        val now = System.currentTimeMillis()
        load(context).forEach { item ->
            if (item.txId < 0) return@forEach
            if (item.triggerAtMillis <= now) return@forEach
            scheduleAlarm(context, item)
        }
    }

    private fun cancelAlarm(context: Context, txId: Int) {
        if (txId < 0) return
        runCatching {
            val intent = Intent(context, ScheduledUssdReceiver::class.java).apply {
                action = ScheduledUssdReceiver.ACTION_RUN_SCHEDULED
                putExtra("txId", txId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                txId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.cancel(pi)
            pi.cancel()
        }
    }

    private fun broadcastUpdated(context: Context) {
        context.sendBroadcast(Intent(ACTION_SCHEDULES_UPDATED).setPackage(context.packageName))
    }

    private fun rawJson(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .safeGetString(KEY_LIST, "[]")
            ?: "[]"

    private fun parse(rawJson: String?): List<ScheduledUssd> {
        if (rawJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            List(arr.length()) { index ->
                val obj = arr.optJSONObject(index) ?: JSONObject()
                ScheduledUssd(
                    txId = obj.optInt("txId", -1),
                    offerId = obj.optInt("offerId", -1),
                    offerName = obj.optString("offerName", ""),
                    phoneNumber = obj.optString("phoneNumber", ""),
                    ussdCode = obj.optString("ussdCode", ""),
                    executionMode = obj.optString("executionMode", OFFER_EXECUTION_MODE_SIMPLE),
                    triggerAtMillis = obj.optLong("triggerAtMillis", 0L),
                    createdAtMillis = obj.optLong("createdAtMillis", 0L)
                )
            }.filter { it.txId >= 0 }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocked(context: Context, list: List<ScheduledUssd>) {
        val json = JSONArray().apply {
            list.forEach { item ->
                put(
                    JSONObject().apply {
                        put("txId", item.txId)
                        put("offerId", item.offerId)
                        put("offerName", item.offerName)
                        put("phoneNumber", item.phoneNumber)
                        put("ussdCode", item.ussdCode)
                        put("executionMode", item.executionMode)
                        put("triggerAtMillis", item.triggerAtMillis)
                        put("createdAtMillis", item.createdAtMillis)
                    }
                )
            }
        }.toString()
        updateCache(json, list)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIST, json)
            .apply()
    }

    private fun updateCache(rawJson: String?, list: List<ScheduledUssd>) {
        cachedRawJson = rawJson
        cachedSchedules = list.toList()
    }
}

