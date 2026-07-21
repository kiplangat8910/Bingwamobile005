package com.bingwa.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

object UssdQueue {
    private const val NEXT_EXECUTION_DELAY_MS = 2_000L
    private val pending = ArrayDeque<Runnable>()
    private var processing = false
    private var scheduled = false
    private val handler = Handler(Looper.getMainLooper())

    fun enqueue(task: Runnable) {
        synchronized(this) {
            pending.addLast(task)
            scheduleNextIfPossible(useDelay = false)
        }
    }

    fun markCompleted() {
        synchronized(this) {
            processing = false
            scheduled = false
            scheduleNextIfPossible(useDelay = true)
        }
    }

    fun hasWork(): Boolean = synchronized(this) {
        processing || scheduled || pending.isNotEmpty()
    }

    private fun scheduleNextIfPossible(useDelay: Boolean) {
        if (processing || scheduled || pending.isEmpty()) return
        scheduled = true
        val task = Runnable {
            val nextTask = synchronized(this) {
                scheduled = false
                if (processing || pending.isEmpty()) {
                    null
                } else {
                    processing = true
                    pending.removeFirst()
                }
            }
            nextTask?.run()
        }
        if (useDelay) handler.postDelayed(task, NEXT_EXECUTION_DELAY_MS) else handler.post(task)
    }

    fun enqueueBalanceCheck(context: Context) {
        enqueue(Runnable { BalanceChecker.requestBalanceCheck(context) })
    }
}
