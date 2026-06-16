package com.bingwa.mobile

import android.content.Context
import android.os.Handler
import android.os.Looper

object UssdQueue {
    private val pending = mutableListOf<Runnable>()
    private var processing = false
    private val handler = Handler(Looper.getMainLooper())

    fun enqueue(task: Runnable) {
        synchronized(pending) {
            pending.add(task)
            if (!processing) processNext()
        }
    }

    fun markCompleted() {
        synchronized(pending) {
            processing = false
            processNext()
        }
    }

    private fun processNext() {
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                processing = true
                handler.post(pending.removeAt(0))
            }
        }
    }

    fun enqueueBalanceCheck(context: Context) {
        enqueue { BalanceChecker.requestBalanceCheck(context) }
    }
}