package com.bingwa.mobile

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

object ScratchCardRechargeManager {
    private const val TAG = "ScratchCardRechargeMgr"
    private const val BETWEEN_CARDS_DELAY_MS = 2_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val activeQueueId = AtomicLong(-1L)

    fun resumePendingQueue(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            ScratchCardRepository.get(appContext).resetStuckProcessing()
            startNextIfIdle(appContext)
        }
    }

    fun onQueueUpdated(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            startNextIfIdle(appContext)
        }
    }

    fun onRechargeResult(context: Context, queueId: Long, status: String, response: String) {
        val appContext = context.applicationContext
        scope.launch {
            Log.d(TAG, "Recharge result queueId=$queueId status=$status")
            ScratchCardRepository.get(appContext).markRechargeResult(
                queueId = queueId,
                status = status,
                response = response
            )
            activeQueueId.compareAndSet(queueId, -1L)
            delay(BETWEEN_CARDS_DELAY_MS)
            startNextIfIdle(appContext)
        }
    }

    private suspend fun startNextIfIdle(context: Context) {
        startMutex.withLock {
            if (activeQueueId.get() > 0L) return
            val repo = ScratchCardRepository.get(context)
            val nextQueue = repo.getNextPendingQueue() ?: return
            activeQueueId.set(nextQueue.id)
            repo.markQueueProcessing(nextQueue.id)
            val started = ServiceLauncher.startAutomationService(
                context,
                Intent(context, AutomationService::class.java).apply {
                    putExtra("mode", OFFER_EXECUTION_MODE_SIMPLE)
                    putExtra("code", "141${nextQueue.code}#")
                    putExtra("phoneNumber", "")
                    putExtra("simSelection", nextQueue.simSelection)
                    putExtra("rechargeQueueId", nextQueue.id)
                }
            )
            if (!started) {
                Log.w(TAG, "Unable to start recharge automation for queueId=${nextQueue.id}")
                activeQueueId.set(-1L)
                repo.markRechargeResult(
                    queueId = nextQueue.id,
                    status = SCRATCH_CARD_STATUS_FAILED,
                    response = "Unable to start recharge automation"
                )
                delay(BETWEEN_CARDS_DELAY_MS)
                startNextIfIdle(context)
            }
        }
    }
}
