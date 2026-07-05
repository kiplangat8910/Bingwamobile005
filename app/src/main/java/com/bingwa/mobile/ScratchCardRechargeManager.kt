package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class ScratchCardStatus {
    Pending,
    Processing,
    Success,
    Failed
}

data class ScratchCardHistoryItem(
    val id: String,
    val code: String,
    val simSelection: Int,
    val status: ScratchCardStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val retryCount: Int = 0,
    val lastResponse: String = ""
)

data class ScratchCardScannerState(
    val history: List<ScratchCardHistoryItem> = emptyList(),
    val queue: List<String> = emptyList(),
    val activeId: String? = null
) {
    val queuedCount: Int get() = queue.size
    val processingCount: Int get() = if (activeId == null) 0 else 1
}

data class ScratchCardEnqueueResult(
    val addedCount: Int,
    val duplicateCount: Int,
    val queueFull: Boolean
)

object ScratchCardRechargeManager {
    private const val TAG = "ScratchCardManager"
    private const val PREFS_NAME = "scratch_card_scanner"
    private const val KEY_STATE = "scanner_state"
    private const val MAX_HISTORY = 50
    private const val QUEUE_DELAY_MS = 2_000L
    private const val RECHARGE_CODE_PREFIX = "141"

    const val EXTRA_SCRATCH_CARD_ID = "scratch_card_id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val _state = MutableStateFlow(ScratchCardScannerState())
    private var initialized = false
    private var launchJob: Job? = null

    val state: StateFlow<ScratchCardScannerState> = _state.asStateFlow()

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        var shouldPersist = false
        synchronized(lock) {
            if (!initialized) {
                initialized = true
                val restored = normalizeLoadedState(loadState(appContext))
                _state.value = restored
                shouldPersist = true
            }
        }
        if (shouldPersist) persistState(appContext, _state.value)
        launchNextIfIdle(appContext, immediate = true)
    }

    fun enqueueCodes(
        context: Context,
        codes: List<String>,
        defaultSimSelection: Int
    ): ScratchCardEnqueueResult {
        initialize(context)
        val appContext = context.applicationContext
        lateinit var result: ScratchCardEnqueueResult
        synchronized(lock) {
            val current = _state.value
            val deduped = LinkedHashSet<String>()
            var duplicates = 0
            val existingCodes = current.history.map { it.code }.toHashSet()
            codes.forEach { rawCode ->
                val code = rawCode.trim()
                if (code.length != 16 || code.any { !it.isDigit() }) return@forEach
                if (!deduped.add(code) || code in existingCodes) {
                    duplicates++
                }
            }

            val historyWithoutOldFinals = makeRoomForNewItems(current)
            val availableSlots = (MAX_HISTORY - historyWithoutOldFinals.size).coerceAtLeast(0)
            val acceptedCodes = deduped.take(availableSlots)
            val now = System.currentTimeMillis()
            val newItems = acceptedCodes.mapIndexed { index, code ->
                ScratchCardHistoryItem(
                    id = UUID.randomUUID().toString(),
                    code = code,
                    simSelection = defaultSimSelection,
                    status = ScratchCardStatus.Pending,
                    createdAt = now + index,
                    updatedAt = now + index
                )
            }
            val updated = current.copy(
                history = newItems + historyWithoutOldFinals,
                queue = current.queue + newItems.map { it.id }
            )
            _state.value = updated
            persistState(appContext, updated)
            result = ScratchCardEnqueueResult(
                addedCount = newItems.size,
                duplicateCount = duplicates,
                queueFull = acceptedCodes.size < deduped.size
            )
        }
        launchNextIfIdle(appContext)
        return result
    }

    fun updateItemSimSelection(context: Context, itemId: String, simSelection: Int): Boolean {
        initialize(context)
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _state.value
            val index = current.history.indexOfFirst { it.id == itemId }
            if (index < 0) return false
            val updatedHistory = current.history.toMutableList()
            updatedHistory[index] = updatedHistory[index].copy(
                simSelection = simSelection,
                updatedAt = System.currentTimeMillis()
            )
            val updated = current.copy(history = updatedHistory)
            _state.value = updated
            persistState(appContext, updated)
        }
        return true
    }

    fun clearHistory(context: Context): Boolean {
        initialize(context)
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _state.value
            if (current.activeId != null) return false
            launchJob?.cancel()
            launchJob = null
            val cleared = ScratchCardScannerState()
            _state.value = cleared
            persistState(appContext, cleared)
        }
        return true
    }

    fun onAutomationResult(
        context: Context,
        itemId: String,
        status: String,
        response: String
    ) {
        initialize(context)
        val appContext = context.applicationContext
        synchronized(lock) {
            val current = _state.value
            val index = current.history.indexOfFirst { it.id == itemId }
            if (index < 0) {
                Log.w(TAG, "Ignoring result for unknown item $itemId")
                return
            }
            val item = current.history[index]
            val now = System.currentTimeMillis()
            val succeeded = status.equals("Success", ignoreCase = true)
            val updatedHistory = current.history.toMutableList()
            val updatedQueue = current.queue.toMutableList().apply { remove(itemId) }
            val updatedItem = when {
                succeeded -> item.copy(
                    status = ScratchCardStatus.Success,
                    updatedAt = now,
                    lastResponse = response
                )
                item.retryCount < 1 -> {
                    updatedQueue += itemId
                    item.copy(
                        status = ScratchCardStatus.Pending,
                        retryCount = item.retryCount + 1,
                        updatedAt = now,
                        lastResponse = response
                    )
                }
                else -> item.copy(
                    status = ScratchCardStatus.Failed,
                    updatedAt = now,
                    lastResponse = response
                )
            }
            updatedHistory[index] = updatedItem
            val updated = current.copy(
                history = updatedHistory,
                queue = updatedQueue,
                activeId = current.activeId.takeUnless { it == itemId }
            )
            _state.value = updated
            persistState(appContext, updated)
        }
        launchNextIfIdle(appContext)
    }

    private fun launchNextIfIdle(context: Context, immediate: Boolean = false) {
        synchronized(lock) {
            val current = _state.value
            if (current.activeId != null || current.queue.isEmpty()) return
            if (launchJob?.isActive == true) return
            val nextId = current.queue.first()
            launchJob = scope.launch {
                if (!immediate) delay(QUEUE_DELAY_MS)
                startNextItem(context, nextId)
            }
        }
    }

    private fun startNextItem(context: Context, itemId: String) {
        val appContext = context.applicationContext
        val itemToLaunch = synchronized(lock) {
            val current = _state.value
            if (current.activeId != null || current.queue.firstOrNull() != itemId) {
                return
            }
            val item = current.history.firstOrNull { it.id == itemId } ?: return
            val updatedHistory = current.history.map { historyItem ->
                if (historyItem.id == itemId) {
                    historyItem.copy(
                        status = ScratchCardStatus.Processing,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    historyItem
                }
            }
            val updated = current.copy(
                history = updatedHistory,
                queue = current.queue.drop(1),
                activeId = itemId
            )
            _state.value = updated
            persistState(appContext, updated)
            item
        }

        val started = ServiceLauncher.startAutomationService(
            appContext,
            Intent(appContext, AutomationService::class.java).apply {
                putExtra("mode", OFFER_EXECUTION_MODE_SIMPLE)
                putExtra("code", buildRechargeCode(itemToLaunch.code))
                putExtra("simSelection", itemToLaunch.simSelection)
                putExtra(EXTRA_SCRATCH_CARD_ID, itemToLaunch.id)
            }
        )
        if (!started) {
            onAutomationResult(
                context = appContext,
                itemId = itemToLaunch.id,
                status = TransactionStatus.FAILED.value,
                response = "Automation service could not start."
            )
        }
    }

    private fun buildRechargeCode(code: String): String = "$RECHARGE_CODE_PREFIX$code#"

    private fun makeRoomForNewItems(current: ScratchCardScannerState): List<ScratchCardHistoryItem> {
        val protectedIds = current.queue.toSet() + listOfNotNull(current.activeId)
        val trimmed = current.history.toMutableList()
        while (trimmed.size >= MAX_HISTORY) {
            val removableIndex = trimmed.indexOfLast { item ->
                item.id !in protectedIds &&
                    item.status != ScratchCardStatus.Pending &&
                    item.status != ScratchCardStatus.Processing
            }
            if (removableIndex < 0) break
            trimmed.removeAt(removableIndex)
        }
        return trimmed
    }

    private fun normalizeLoadedState(raw: ScratchCardScannerState): ScratchCardScannerState {
        val history = raw.history
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(MAX_HISTORY)
        val validIds = history.map { it.id }.toSet()
        val queue = raw.queue.filter { it in validIds }.distinct()
        val activeId = raw.activeId?.takeIf { active ->
            active in validIds && history.firstOrNull { it.id == active }?.status !in
                setOf(ScratchCardStatus.Success, ScratchCardStatus.Failed)
        }
        if (activeId == null) {
            return raw.copy(history = history, queue = queue)
        }

        val recoveredQueue = buildList {
            add(activeId)
            queue.filterTo(this) { it != activeId }
        }
        val recoveredHistory = history.map { item ->
            if (item.id == activeId && item.status == ScratchCardStatus.Processing) {
                item.copy(status = ScratchCardStatus.Pending, updatedAt = System.currentTimeMillis())
            } else {
                item
            }
        }
        return ScratchCardScannerState(
            history = recoveredHistory,
            queue = recoveredQueue,
            activeId = null
        )
    }

    private fun loadState(context: Context): ScratchCardScannerState {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .safeGetString(KEY_STATE, null)
            ?: return ScratchCardScannerState()
        return runCatching {
            val root = JSONObject(raw)
            val historyArray = root.optJSONArray("history") ?: JSONArray()
            val queueArray = root.optJSONArray("queue") ?: JSONArray()
            val history = buildList {
                for (index in 0 until historyArray.length()) {
                    val obj = historyArray.optJSONObject(index) ?: continue
                    add(
                        ScratchCardHistoryItem(
                            id = obj.optString("id"),
                            code = obj.optString("code"),
                            simSelection = obj.optInt("simSelection", USSD_SIM_SELECTION_SLOT_1),
                            status = runCatching {
                                ScratchCardStatus.valueOf(obj.optString("status", ScratchCardStatus.Pending.name))
                            }.getOrDefault(ScratchCardStatus.Pending),
                            createdAt = obj.optLong("createdAt", 0L),
                            updatedAt = obj.optLong("updatedAt", 0L),
                            retryCount = obj.optInt("retryCount", 0),
                            lastResponse = obj.optString("lastResponse", "")
                        )
                    )
                }
            }.filter { it.id.isNotBlank() && it.code.length == 16 }
            val queue = buildList {
                for (index in 0 until queueArray.length()) {
                    queueArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            ScratchCardScannerState(
                history = history,
                queue = queue,
                activeId = root.optString("activeId").ifBlank { null }
            )
        }.getOrElse {
            Log.e(TAG, "Unable to restore scratch card state", it)
            ScratchCardScannerState()
        }
    }

    private fun persistState(context: Context, state: ScratchCardScannerState) {
        val json = JSONObject().apply {
            put("activeId", state.activeId)
            put("queue", JSONArray().apply {
                state.queue.forEach(::put)
            })
            put("history", JSONArray().apply {
                state.history.take(MAX_HISTORY).forEach { item ->
                    put(
                        JSONObject().apply {
                            put("id", item.id)
                            put("code", item.code)
                            put("simSelection", item.simSelection)
                            put("status", item.status.name)
                            put("createdAt", item.createdAt)
                            put("updatedAt", item.updatedAt)
                            put("retryCount", item.retryCount)
                            put("lastResponse", item.lastResponse)
                        }
                    )
                }
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, json.toString())
            .apply()
    }
}
