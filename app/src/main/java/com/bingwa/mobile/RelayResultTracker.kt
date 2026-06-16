package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

object RelayResultTracker {
    private val pending = ConcurrentHashMap<Int, String>()
    @Volatile private var registered = false

    fun trackAndReply(context: Context, txId: Int, replyTo: String) {
        if (txId < 0 || replyTo.isBlank()) return
        pending[txId] = replyTo
        ensureReceiver(context.applicationContext)
        Handler(Looper.getMainLooper()).postDelayed({ pending.remove(txId) }, 3 * 60 * 1000L)
    }

    private fun ensureReceiver(ctx: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val txId = intent.getIntExtra("txId", -1)
                    val status = intent.getStringExtra("status") ?: return
                    val dest = pending.remove(txId) ?: return
                    val msg = "Relay result for transaction #$txId: $status."
                    SmsCommandHandler.sendSms(context, dest, msg)
                }
            }
            registered = registerAppReceiver(
                ctx,
                receiver,
                android.content.IntentFilter("com.bingwa.mobile.TX_UPDATED")
            )
        }
    }
}
