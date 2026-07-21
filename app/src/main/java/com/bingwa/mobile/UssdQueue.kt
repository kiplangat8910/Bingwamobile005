package com.bingwa.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

object UssdQueue {
    private const val NEXT_EXECUTION_DELAY_MS = 2_000L
    private val specialPriorityPending = ArrayDeque<Runnable>()
    private val highPriorityPending = ArrayDeque<Runnable>()
    private val normalPriorityPending = ArrayDeque<Runnable>()
    private var processing = false
    private var scheduled = false
    private val handler = Handler(Looper.getMainLooper())

    fun enqueue(task: Runnable, priority: String = USSD_EXECUTION_PRIORITY_NORMAL) {
        synchronized(this) {
            when {
                priority.equals(USSD_EXECUTION_PRIORITY_SPECIAL, ignoreCase = true) -> {
                    specialPriorityPending.addLast(task)
                }
                priority.equals(USSD_EXECUTION_PRIORITY_HIGH, ignoreCase = true) -> {
                    highPriorityPending.addLast(task)
                }
                else -> {
                    normalPriorityPending.addLast(task)
                }
            }
            scheduleNextIfPossible(useDelay = false)
        }
    }

    fun markCompleted() {
        synchronized(this) {
            processing = false
            scheduled = false
            scheduleNextIfPossible(useDelay = !hasSpecialPendingLocked())
        }
    }

    fun hasWork(): Boolean = synchronized(this) {
        processing ||
            scheduled ||
            specialPriorityPending.isNotEmpty() ||
            highPriorityPending.isNotEmpty() ||
            normalPriorityPending.isNotEmpty()
    }

    private fun scheduleNextIfPossible(useDelay: Boolean) {
        if (processing || scheduled || isEmptyLocked()) return
        scheduled = true
        val task = Runnable {
            val nextTask = synchronized(this) {
                scheduled = false
                if (processing || isEmptyLocked()) {
                    null
                } else {
                    processing = true
                    when {
                        specialPriorityPending.isNotEmpty() -> specialPriorityPending.removeFirst()
                        highPriorityPending.isNotEmpty() -> highPriorityPending.removeFirst()
                        else -> normalPriorityPending.removeFirst()
                    }
                }
            }
            nextTask?.run()
        }
        if (useDelay) handler.postDelayed(task, NEXT_EXECUTION_DELAY_MS) else handler.post(task)
    }

    fun enqueueBalanceCheck(context: Context) {
        enqueue(
            task = Runnable {
                BalanceChecker.requestBalanceCheck(
                    context = context,
                    specialHandling = true
                )
            },
            priority = USSD_EXECUTION_PRIORITY_SPECIAL
        )
    }

    private fun isEmptyLocked(): Boolean =
        specialPriorityPending.isEmpty() &&
            highPriorityPending.isEmpty() &&
            normalPriorityPending.isEmpty()

    private fun hasSpecialPendingLocked(): Boolean = specialPriorityPending.isNotEmpty()
}
