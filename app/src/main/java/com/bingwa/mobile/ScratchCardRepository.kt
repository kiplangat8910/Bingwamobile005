package com.bingwa.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class ScratchCardHistoryItem(
    val historyId: Long,
    val queueId: Long,
    val code: String,
    val simSelection: Int,
    val status: String,
    val retryCount: Int,
    val lastResponse: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ScratchCardEnqueueResult(
    val addedCount: Int,
    val skippedCount: Int,
    val totalRecognized: Int
)

class ScratchCardRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dao = ScratchCardDatabase.getInstance(appContext).scratchCardDao()
    private val settings = SettingsDataStore(appContext)

    val historyFlow: Flow<List<ScratchCardHistoryItem>> = dao.observeHistoryWithQueue().map { entries ->
        entries.map { entry ->
            val queue = entry.queueItems.firstOrNull()
            ScratchCardHistoryItem(
                historyId = entry.history.id,
                queueId = queue?.id ?: -1L,
                code = entry.history.code,
                simSelection = queue?.simSelection ?: entry.history.simSelection,
                status = queue?.status ?: entry.history.status,
                retryCount = queue?.retryCount ?: entry.history.retryCount,
                lastResponse = queue?.lastResponse ?: entry.history.lastResponse,
                createdAt = entry.history.createdAt,
                updatedAt = maxOf(entry.history.updatedAt, queue?.updatedAt ?: entry.history.updatedAt)
            )
        }
    }

    val defaultSimFlow: Flow<Int> = settings.rechargeScannerSimFlow

    val simConfiguredFlow: Flow<Boolean> = settings.rechargeScannerSimConfiguredFlow

    val screenStateFlow: Flow<Pair<Int, Boolean>> = combine(defaultSimFlow, simConfiguredFlow) { sim, configured ->
        sim to configured
    }

    suspend fun enqueueRecognizedCodes(codes: List<String>, simSelection: Int): ScratchCardEnqueueResult {
        return withContext(Dispatchers.IO) {
            val deduped = codes.map { it.trim() }.filter { it.matches(ScratchCardTextRecognizer.CODE_REGEX) }.distinct()
            val currentCount = dao.countHistory()
            val roomLeft = (SCRATCH_CARD_MAX_HISTORY - currentCount).coerceAtLeast(0)
            val accepted = deduped.take(roomLeft)
            if (accepted.isNotEmpty()) {
                dao.insertScannedCodes(
                    codes = accepted,
                    simSelection = normalizeScannerSimSelection(simSelection),
                    now = System.currentTimeMillis()
                )
            }
            ScratchCardEnqueueResult(
                addedCount = accepted.size,
                skippedCount = deduped.size - accepted.size,
                totalRecognized = deduped.size
            )
        }
    }

    suspend fun setDefaultSim(simSelection: Int) {
        settings.setRechargeScannerSim(normalizeScannerSimSelection(simSelection))
    }

    suspend fun updateHistorySim(historyId: Long, simSelection: Int) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val normalized = normalizeScannerSimSelection(simSelection)
            dao.updateHistorySim(historyId, normalized, now)
            dao.updateQueueSim(historyId, normalized, now)
        }
    }

    suspend fun clearHistory() {
        withContext(Dispatchers.IO) {
            dao.clearAllScratchCards()
        }
    }

    suspend fun resetStuckProcessing() {
        withContext(Dispatchers.IO) {
            dao.resetProcessingCards(updatedAt = System.currentTimeMillis())
        }
    }

    suspend fun getNextPendingQueue(): ScratchCardQueueEntity? {
        return withContext(Dispatchers.IO) {
            dao.getQueueByStatus(SCRATCH_CARD_STATUS_PENDING)
        }
    }

    suspend fun markQueueProcessing(queueId: Long) {
        withContext(Dispatchers.IO) {
            val queue = dao.getQueueById(queueId) ?: return@withContext
            val now = System.currentTimeMillis()
            dao.updateQueueState(
                queueId = queue.id,
                status = SCRATCH_CARD_STATUS_PROCESSING,
                simSelection = queue.simSelection,
                retryCount = queue.retryCount,
                lastResponse = queue.lastResponse,
                updatedAt = now
            )
            dao.updateHistoryState(
                historyId = queue.historyId,
                status = SCRATCH_CARD_STATUS_PROCESSING,
                simSelection = queue.simSelection,
                retryCount = queue.retryCount,
                lastResponse = queue.lastResponse,
                updatedAt = now
            )
        }
    }

    suspend fun markRechargeResult(queueId: Long, status: String, response: String) {
        withContext(Dispatchers.IO) {
            val queue = dao.getQueueById(queueId) ?: return@withContext
            val now = System.currentTimeMillis()
            val success = status == SCRATCH_CARD_STATUS_SUCCESS
            if (success) {
                dao.updateQueueState(
                    queueId = queue.id,
                    status = SCRATCH_CARD_STATUS_SUCCESS,
                    simSelection = queue.simSelection,
                    retryCount = queue.retryCount,
                    lastResponse = response,
                    updatedAt = now
                )
                dao.updateHistoryState(
                    historyId = queue.historyId,
                    status = SCRATCH_CARD_STATUS_SUCCESS,
                    simSelection = queue.simSelection,
                    retryCount = queue.retryCount,
                    lastResponse = response,
                    updatedAt = now
                )
                return@withContext
            }

            val nextRetryCount = queue.retryCount + 1
            val nextStatus = if (queue.retryCount < 1) {
                SCRATCH_CARD_STATUS_PENDING
            } else {
                SCRATCH_CARD_STATUS_FAILED
            }
            dao.updateQueueState(
                queueId = queue.id,
                status = nextStatus,
                simSelection = queue.simSelection,
                retryCount = nextRetryCount,
                lastResponse = response,
                updatedAt = now
            )
            dao.updateHistoryState(
                historyId = queue.historyId,
                status = nextStatus,
                simSelection = queue.simSelection,
                retryCount = nextRetryCount,
                lastResponse = response,
                updatedAt = now
            )
        }
    }

    private fun normalizeScannerSimSelection(selection: Int): Int {
        return when (selection) {
            USSD_SIM_SELECTION_SLOT_2 -> USSD_SIM_SELECTION_SLOT_2
            else -> USSD_SIM_SELECTION_SLOT_1
        }
    }

    companion object {
        @Volatile
        private var instance: ScratchCardRepository? = null

        fun get(context: Context): ScratchCardRepository {
            return instance ?: synchronized(this) {
                instance ?: ScratchCardRepository(context).also { instance = it }
            }
        }
    }
}
