package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal enum class ScratchCardStatus(val label: String) {
    PENDING("Pending"),
    PROCESSING("Processing"),
    SUCCESS("Success"),
    FAILED("Failed");

    companion object {
        fun fromLabel(value: String): ScratchCardStatus =
            entries.firstOrNull { it.label.equals(value, ignoreCase = true) } ?: PENDING
    }
}

internal data class ScratchCardEntry(
    val id: String,
    val code: String,
    val simSlot: Int,
    val status: ScratchCardStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val txId: Int = -1,
    val attemptCount: Int = 0,
    val lastMessage: String = ""
)

internal object ScratchCardRechargeManager {
    private const val TAG = "ScratchCardRecharge"
    private const val MAX_HISTORY = 50
    private const val MAX_ATTEMPTS = 2
    private const val NEXT_CARD_DELAY_MS = 2_000L
    private const val DESCRIPTION = "Scratch Card Recharge"
    private const val AMOUNT_LABEL = "Airtime Recharge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val loadMutex = Mutex()
    private val cardsFlow = MutableStateFlow<List<ScratchCardEntry>>(emptyList())

    @Volatile
    private var initialized = false

    @Volatile
    private var loaded = false

    @Volatile
    private var queuedStartJob: Job? = null

    val cards: StateFlow<List<ScratchCardEntry>> = cardsFlow.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            ensureLoaded(appContext)
            resumePendingQueue(appContext)
        }
    }

    suspend fun enqueueCodes(context: Context, codes: List<String>, simSlot: Int): Int {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        val normalizedCodes = LinkedHashSet<String>().apply {
            codes.forEach { raw ->
                val normalized = raw.filter(Char::isDigit)
                if (normalized.length == 16) add(normalized)
            }
        }
        if (normalizedCodes.isEmpty()) return 0

        var addedCount = 0
        stateMutex.withLock {
            val seenCodes = cardsFlow.value.mapTo(linkedSetOf()) { it.code }
            val updated = cardsFlow.value.toMutableList()
            val now = System.currentTimeMillis()
            normalizedCodes.forEach { code ->
                if (seenCodes.add(code)) {
                    updated += ScratchCardEntry(
                        id = generateCardId(code, now, updated.size),
                        code = code,
                        simSlot = simSlot.coerceIn(1, 2),
                        status = ScratchCardStatus.PENDING,
                        createdAt = now + addedCount,
                        updatedAt = now + addedCount
                    )
                    addedCount += 1
                }
            }
            cardsFlow.value = trimToHistoryLimit(updated)
        }
        persist(appContext)
        if (addedCount > 0) scheduleNext(appContext, delayMs = 0L)
        return addedCount
    }

    suspend fun updateCardSim(context: Context, cardId: String, simSlot: Int) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        var changed = false
        stateMutex.withLock {
            val updated = cardsFlow.value.map { card ->
                if (card.id != cardId || card.status == ScratchCardStatus.PROCESSING) {
                    card
                } else {
                    changed = true
                    card.copy(
                        simSlot = simSlot.coerceIn(1, 2),
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
            if (changed) cardsFlow.value = updated
        }
        if (changed) persist(appContext)
    }

    suspend fun clearHistory(context: Context) {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        stateMutex.withLock {
            cardsFlow.value = emptyList()
        }
        persist(appContext)
    }

    fun handleAutomationStarted(context: Context, txId: Int) {
        if (txId < 0) return
        val appContext = context.applicationContext
        scope.launch {
            ensureLoaded(appContext)
            var changed = false
            stateMutex.withLock {
                val updated = cardsFlow.value.map { card ->
                    if (card.txId != txId) {
                        card
                    } else {
                        changed = true
                        card.copy(
                            status = ScratchCardStatus.PROCESSING,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                }
                if (changed) cardsFlow.value = updated
            }
            if (changed) persist(appContext)
        }
    }

    fun handleTransactionUpdate(context: Context, txId: Int, status: String, response: String) {
        if (txId < 0) return
        val appContext = context.applicationContext
        scope.launch {
            ensureLoaded(appContext)
            val shouldScheduleNext = stateMutex.withLock {
                val index = cardsFlow.value.indexOfFirst { it.txId == txId }
                if (index < 0) return@withLock false
                val card = cardsFlow.value[index]
                val now = System.currentTimeMillis()
                val message = response.normalizeStatusMessage()
                val updated = cardsFlow.value.toMutableList()
                updated[index] = when {
                    status.equals(TransactionStatus.SUCCESS.value, ignoreCase = true) -> {
                        card.copy(
                            status = ScratchCardStatus.SUCCESS,
                            txId = -1,
                            updatedAt = now,
                            lastMessage = message
                        )
                    }

                    card.attemptCount < MAX_ATTEMPTS -> {
                        card.copy(
                            status = ScratchCardStatus.PENDING,
                            txId = -1,
                            updatedAt = now,
                            lastMessage = message
                        )
                    }

                    else -> {
                        card.copy(
                            status = ScratchCardStatus.FAILED,
                            txId = -1,
                            updatedAt = now,
                            lastMessage = message
                        )
                    }
                }
                cardsFlow.value = updated
                true
            }
            if (shouldScheduleNext) {
                persist(appContext)
                scheduleNext(appContext, NEXT_CARD_DELAY_MS)
            }
        }
    }

    fun resumePendingQueue(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ensureLoaded(appContext)
            var changed = false
            stateMutex.withLock {
                val recovered = recoverInterruptedCards(cardsFlow.value)
                if (recovered != cardsFlow.value) {
                    changed = true
                    cardsFlow.value = recovered
                }
            }
            if (changed) persist(appContext)
            scheduleNext(appContext, NEXT_CARD_DELAY_MS)
        }
    }

    private fun scheduleNext(context: Context, delayMs: Long) {
        queuedStartJob?.cancel()
        queuedStartJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            startNextCard(context)
        }
    }

    private suspend fun startNextCard(context: Context) {
        ensureLoaded(context)
        val launchPayload = stateMutex.withLock {
            if (cardsFlow.value.any { it.status == ScratchCardStatus.PROCESSING }) {
                return@withLock null
            }
            val nextIndex = cardsFlow.value.indexOfFirst { it.status == ScratchCardStatus.PENDING }
            if (nextIndex < 0) return@withLock null
            val nextCard = cardsFlow.value[nextIndex]
            val txId = createPendingTransaction(
                ctx = context,
                description = DESCRIPTION,
                amount = AMOUNT_LABEL,
                phone = "",
                ussd = rechargeCodeFor(nextCard.code),
                source = TX_SOURCE_AIRTIME,
                showInRecent = false
            )
            if (txId < 0) {
                val failedCards = cardsFlow.value.toMutableList()
                failedCards[nextIndex] = failOrRequeue(
                    nextCard.copy(attemptCount = nextCard.attemptCount + 1),
                    "Unable to create recharge transaction"
                )
                cardsFlow.value = failedCards
                return@withLock StartResult(persistOnly = true)
            }
            val updated = cardsFlow.value.toMutableList()
            updated[nextIndex] = nextCard.copy(
                status = ScratchCardStatus.PROCESSING,
                txId = txId,
                attemptCount = nextCard.attemptCount + 1,
                updatedAt = System.currentTimeMillis()
            )
            cardsFlow.value = updated
            StartResult(
                txId = txId,
                code = nextCard.code,
                simSlot = nextCard.simSlot,
                persistOnly = false
            )
        } ?: return

        persist(context)
        if (launchPayload.persistOnly) {
            scheduleNext(context, NEXT_CARD_DELAY_MS)
            return
        }

        val started = ServiceLauncher.startAutomationService(
            context,
            buildRechargeIntent(
                context = context,
                code = launchPayload.code,
                txId = launchPayload.txId,
                simSlot = launchPayload.simSlot
            )
        )
        if (!started) {
            saveTransactionOutcome(
                context = context,
                txId = launchPayload.txId,
                status = TransactionStatus.FAILED.value,
                response = "Unable to start recharge automation"
            )
            broadcastTransactionUpdated(context, launchPayload.txId)
            handleTransactionUpdate(
                context = context,
                txId = launchPayload.txId,
                status = TransactionStatus.FAILED.value,
                response = "Unable to start recharge automation"
            )
        }
    }

    private suspend fun ensureLoaded(context: Context) {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            val store = SettingsDataStore(context)
            val rawJson = store.readRechargeScannerHistoryJson()
            val parsed = parseCards(rawJson)
            val recovered = recoverInterruptedCards(parsed)
            cardsFlow.value = recovered
            loaded = true
            if (recovered != parsed) {
                persist(context)
            }
        }
    }

    private suspend fun persist(context: Context) {
        val json = JSONArray().apply {
            cardsFlow.value.forEach { card -> put(card.toJson()) }
        }.toString()
        SettingsDataStore(context).writeRechargeScannerHistoryJson(json)
    }

    private fun recoverInterruptedCards(cards: List<ScratchCardEntry>): List<ScratchCardEntry> {
        if (cards.isEmpty()) return cards
        var changed = false
        val now = System.currentTimeMillis()
        val recovered = cards.map { card ->
            if (card.status != ScratchCardStatus.PROCESSING) {
                card
            } else {
                changed = true
                if (card.attemptCount < MAX_ATTEMPTS) {
                    card.copy(
                        status = ScratchCardStatus.PENDING,
                        txId = -1,
                        updatedAt = now,
                        lastMessage = "Retrying after app restart"
                    )
                } else {
                    card.copy(
                        status = ScratchCardStatus.FAILED,
                        txId = -1,
                        updatedAt = now,
                        lastMessage = "Recharge interrupted before completion"
                    )
                }
            }
        }
        return if (changed) recovered else cards
    }

    private fun failOrRequeue(card: ScratchCardEntry, message: String): ScratchCardEntry {
        val now = System.currentTimeMillis()
        return if (card.attemptCount + 1 < MAX_ATTEMPTS) {
            card.copy(
                status = ScratchCardStatus.PENDING,
                txId = -1,
                updatedAt = now,
                lastMessage = message.normalizeStatusMessage()
            )
        } else {
            card.copy(
                status = ScratchCardStatus.FAILED,
                txId = -1,
                updatedAt = now,
                lastMessage = message.normalizeStatusMessage()
            )
        }
    }

    private fun trimToHistoryLimit(cards: List<ScratchCardEntry>): List<ScratchCardEntry> {
        if (cards.size <= MAX_HISTORY) return cards.sortedBy { it.createdAt }
        val mutable = cards.sortedBy { it.createdAt }.toMutableList()
        while (mutable.size > MAX_HISTORY) {
            val removableIndex = mutable.indexOfFirst { it.status != ScratchCardStatus.PROCESSING && it.status != ScratchCardStatus.PENDING }
            if (removableIndex >= 0) {
                mutable.removeAt(removableIndex)
            } else {
                mutable.removeAt(0)
            }
        }
        return mutable
    }

    private fun rechargeCodeFor(code: String): String = "141${code.filter(Char::isDigit)}#"

    private fun buildRechargeIntent(
        context: Context,
        code: String,
        txId: Int,
        simSlot: Int
    ): Intent = Intent(context, AutomationService::class.java).apply {
        putExtra("mode", OFFER_EXECUTION_MODE_SIMPLE)
        putExtra("code", rechargeCodeFor(code))
        putExtra("phoneNumber", "")
        putExtra("txId", txId)
        putExtra("offerId", -1)
        putExtra("offerName", DESCRIPTION)
        putExtra("simSelection", if (simSlot == 2) USSD_SIM_SELECTION_SLOT_2 else USSD_SIM_SELECTION_SLOT_1)
        putExtra("returnToAppAggressively", false)
    }

    private fun parseCards(rawJson: String): List<ScratchCardEntry> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(rawJson)
            List(array.length()) { index ->
                array.getJSONObject(index).toScratchCardEntry(index)
            }.sortedBy { it.createdAt }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to parse scratch card history", error)
            emptyList()
        }
    }

    private fun JSONObject.toScratchCardEntry(index: Int): ScratchCardEntry {
        val createdAt = optLong("createdAt").takeIf { it > 0L }
            ?: optLong("timestamp").takeIf { it > 0L }
            ?: System.currentTimeMillis() + index
        return ScratchCardEntry(
            id = optString("id").takeIf { it.isNotBlank() } ?: generateCardId(optString("code"), createdAt, index),
            code = optString("code").filter(Char::isDigit),
            simSlot = optInt("simSlot", 1).coerceIn(1, 2),
            status = ScratchCardStatus.fromLabel(optString("status", ScratchCardStatus.PENDING.label)),
            createdAt = createdAt,
            updatedAt = optLong("updatedAt").takeIf { it > 0L } ?: createdAt,
            txId = optInt("txId", -1),
            attemptCount = optInt("attemptCount", 0).coerceAtLeast(0),
            lastMessage = optString("lastMessage", "")
        )
    }

    private fun ScratchCardEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("code", code)
        put("simSlot", simSlot)
        put("status", status.label)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("txId", txId)
        put("attemptCount", attemptCount)
        put("lastMessage", lastMessage)
    }

    private fun generateCardId(code: String, timestamp: Long, index: Int): String {
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(timestamp))
        val suffix = code.takeLast(4).ifBlank { "0000" }
        return "sc-$date-$suffix-$index"
    }

    private fun String.normalizeStatusMessage(): String =
        replace(Regex("\\s+"), " ").trim().take(160)

    private data class StartResult(
        val txId: Int = -1,
        val code: String = "",
        val simSlot: Int = 1,
        val persistOnly: Boolean
    )
}
