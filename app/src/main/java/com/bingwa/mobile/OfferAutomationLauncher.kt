package com.bingwa.mobile

import android.content.Context
import android.content.Intent

private const val AUTOMATION_DISABLED_REASON = "Cancelled: automation is off. Turn automation on to continue."

fun isAutomationEnabled(context: Context): Boolean =
    context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .safeGetBoolean("automation_enabled", true)

fun stopAutomationActivities(context: Context) {
    context.stopService(Intent(context, BalanceChecker::class.java))
    context.stopService(Intent(context, AutomationService::class.java))
    UssdNavigationService.abortActiveSession()
}

private fun cancelAutomationStart(context: Context, txId: Int) {
    if (txId < 0) return
    saveTransactionOutcome(
        context = context,
        txId = txId,
        status = TransactionStatus.CANCELLED.value,
        response = AUTOMATION_DISABLED_REASON
    )
    broadcastTransactionUpdated(context, txId)
}

fun Context.startOfferAutomation(
    offer: OfferItem?,
    phoneNumber: String,
    txId: Int,
    finalCode: String,
    mode: String = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
    signatureLearning: Boolean = false,
    returnToAppAggressively: Boolean = true
): Boolean {
    if (!isAutomationEnabled(this)) {
        cancelAutomationStart(this, txId)
        return false
    }
    val requestedMode = mode.ifBlank { offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE }
    // Speed rule:
    // - When caller requests SIMPLE, do not force ADVANCED just because signature detection is enabled.
    // - Signature learning always needs ADVANCED.
    val effectiveMode = if (signatureLearning) OFFER_EXECUTION_MODE_ADVANCED else requestedMode
    val effectiveSignatureEnabled = (offer?.signatureDetectionEnabled == true) &&
        effectiveMode.equals(OFFER_EXECUTION_MODE_ADVANCED, ignoreCase = true) &&
        !signatureLearning
    val started = ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
        putExtra("mode", effectiveMode)
        putExtra("code", finalCode)
        putExtra("phoneNumber", phoneNumber)
        putExtra("txId", txId)
        putExtra("signatureLearning", signatureLearning)
        putExtra("returnToAppAggressively", returnToAppAggressively)
        if (offer != null) {
            putExtra("offerId", offer.id)
            putExtra("offerName", offer.name)
            putExtra("simSelection", offer.simSelection)
            putExtra("signatureEnabled", effectiveSignatureEnabled)
            putExtra("signatureMode", offer.signatureAction)
        }
    })
    if (!started && !isAutomationEnabled(this)) {
        cancelAutomationStart(this, txId)
    }
    return started
}
