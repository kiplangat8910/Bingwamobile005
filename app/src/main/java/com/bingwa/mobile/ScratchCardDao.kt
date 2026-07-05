package com.bingwa.mobile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScratchCardDao {

    @Transaction
    @Query("SELECT * FROM scratch_card_history ORDER BY createdAt DESC")
    fun observeHistoryWithQueue(): Flow<List<ScratchCardHistoryWithQueue>>

    @Query("SELECT COUNT(*) FROM scratch_card_history")
    suspend fun countHistory(): Int

    @Query("SELECT COALESCE(MAX(queueOrder), 0) FROM scratch_card_queue")
    suspend fun getMaxQueueOrder(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ScratchCardHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQueue(queue: ScratchCardQueueEntity): Long

    @Query("SELECT * FROM scratch_card_queue WHERE status = :status ORDER BY queueOrder ASC LIMIT 1")
    suspend fun getQueueByStatus(status: String): ScratchCardQueueEntity?

    @Query("SELECT * FROM scratch_card_queue WHERE id = :queueId LIMIT 1")
    suspend fun getQueueById(queueId: Long): ScratchCardQueueEntity?

    @Query("SELECT id FROM scratch_card_history ORDER BY createdAt DESC LIMIT -1 OFFSET :keepLimit")
    suspend fun getHistoryIdsBeyondLimit(keepLimit: Int): List<Long>

    @Query(
        """
        UPDATE scratch_card_history
        SET status = :status,
            simSelection = :simSelection,
            retryCount = :retryCount,
            lastResponse = :lastResponse,
            updatedAt = :updatedAt
        WHERE id = :historyId
        """
    )
    suspend fun updateHistoryState(
        historyId: Long,
        status: String,
        simSelection: Int,
        retryCount: Int,
        lastResponse: String,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE scratch_card_queue
        SET status = :status,
            simSelection = :simSelection,
            retryCount = :retryCount,
            lastResponse = :lastResponse,
            updatedAt = :updatedAt
        WHERE id = :queueId
        """
    )
    suspend fun updateQueueState(
        queueId: Long,
        status: String,
        simSelection: Int,
        retryCount: Int,
        lastResponse: String,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE scratch_card_history
        SET simSelection = :simSelection,
            updatedAt = :updatedAt
        WHERE id = :historyId
        """
    )
    suspend fun updateHistorySim(historyId: Long, simSelection: Int, updatedAt: Long)

    @Query(
        """
        UPDATE scratch_card_queue
        SET simSelection = :simSelection,
            updatedAt = :updatedAt
        WHERE historyId = :historyId
        """
    )
    suspend fun updateQueueSim(historyId: Long, simSelection: Int, updatedAt: Long)

    @Query(
        """
        UPDATE scratch_card_history
        SET status = :status,
            updatedAt = :updatedAt
        WHERE status = :fromStatus
        """
    )
    suspend fun moveHistoryStatus(fromStatus: String, status: String, updatedAt: Long)

    @Query(
        """
        UPDATE scratch_card_queue
        SET status = :status,
            updatedAt = :updatedAt
        WHERE status = :fromStatus
        """
    )
    suspend fun moveQueueStatus(fromStatus: String, status: String, updatedAt: Long)

    @Query("DELETE FROM scratch_card_history WHERE id IN (:historyIds)")
    suspend fun deleteHistoryByIds(historyIds: List<Long>)

    @Query("DELETE FROM scratch_card_history")
    suspend fun clearHistory()

    @Transaction
    suspend fun insertScannedCodes(codes: List<String>, simSelection: Int, now: Long): Int {
        var nextOrder = getMaxQueueOrder()
        codes.forEach { code ->
            val historyId = insertHistory(
                ScratchCardHistoryEntity(
                    code = code,
                    simSelection = simSelection,
                    createdAt = now,
                    updatedAt = now
                )
            )
            nextOrder += 1L
            insertQueue(
                ScratchCardQueueEntity(
                    historyId = historyId,
                    code = code,
                    simSelection = simSelection,
                    queueOrder = nextOrder,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
        trimToMaxEntries(SCRATCH_CARD_MAX_HISTORY)
        return codes.size
    }

    @Transaction
    suspend fun trimToMaxEntries(keepLimit: Int) {
        if (keepLimit <= 0) {
            clearHistory()
            return
        }
        val extraIds = getHistoryIdsBeyondLimit(keepLimit)
        if (extraIds.isNotEmpty()) {
            deleteHistoryByIds(extraIds)
        }
    }

    @Transaction
    suspend fun clearAllScratchCards() {
        clearHistory()
    }

    @Transaction
    suspend fun resetProcessingCards(updatedAt: Long) {
        moveHistoryStatus(
            fromStatus = SCRATCH_CARD_STATUS_PROCESSING,
            status = SCRATCH_CARD_STATUS_PENDING,
            updatedAt = updatedAt
        )
        moveQueueStatus(
            fromStatus = SCRATCH_CARD_STATUS_PROCESSING,
            status = SCRATCH_CARD_STATUS_PENDING,
            updatedAt = updatedAt
        )
    }
}
