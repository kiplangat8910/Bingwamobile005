package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScheduledUssdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_SCHEDULED) return
        val txId = intent.getIntExtra("txId", -1)
        if (txId < 0) return
        val scheduled = ScheduledUssdStore.findByTxId(context, txId) ?: return
        ScheduledUssdStore.remove(context, txId)
        saveTransactionOutcome(context, txId, TransactionStatus.PENDING.value, "Scheduled delivery started.")
        broadcastTransactionUpdated(context, txId)
        val offer = OfferRepository.findById(context, scheduled.offerId)
        val finalOffer = offer ?: OfferItem(
            id = scheduled.offerId,
            name = scheduled.offerName.ifBlank { "Scheduled Offer" },
            price = -1,
            ussdCode = scheduled.ussdCode,
            enabled = true,
            executionMode = scheduled.executionMode
        )
        context.startOfferAutomation(
            offer = finalOffer,
            phoneNumber = scheduled.phoneNumber,
            txId = scheduled.txId,
            finalCode = scheduled.ussdCode,
            mode = scheduled.executionMode,
            signatureLearning = false,
            returnToAppAggressively = true
        )
    }

    companion object {
        const val ACTION_RUN_SCHEDULED = "com.bingwa.mobile.RUN_SCHEDULED_USSD"
    }
}

