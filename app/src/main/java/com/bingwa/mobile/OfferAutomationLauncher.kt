package com.bingwa.mobile

import android.content.Context
import android.content.Intent

fun Context.startOfferAutomation(
    offer: OfferItem?,
    phoneNumber: String,
    txId: Int,
    finalCode: String,
    mode: String = offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE,
    signatureLearning: Boolean = false,
    returnToAppAggressively: Boolean = true
) {
    val requestedMode = mode.ifBlank { offer?.executionMode ?: OFFER_EXECUTION_MODE_SIMPLE }
    val effectiveMode = if (signatureLearning || offer?.signatureDetectionEnabled == true) {
        OFFER_EXECUTION_MODE_ADVANCED
    } else {
        requestedMode
    }
    ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
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
            putExtra("signatureEnabled", offer.signatureDetectionEnabled)
            putExtra("signatureMode", offer.signatureAction)
        }
    })
}

fun Context.startScratchCardRechargeAutomation(
    scratchCode: String,
    txId: Int,
    rechargeTemplate: String,
    simSelection: Int,
    queueCodes: List<String> = listOf(scratchCode),
    queueIndex: Int = 0,
    returnToAppAggressively: Boolean = true
) {
    val normalizedScratchCode = normalizeScratchCardDigits(scratchCode)
    val finalCode = buildScratchRechargeCode(rechargeTemplate, normalizedScratchCode)
    if (finalCode.isBlank()) return

    ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
        putExtra("mode", OFFER_EXECUTION_MODE_ADVANCED)
        putExtra("code", finalCode)
        putExtra("phoneNumber", "")
        putExtra("txId", txId)
        putExtra("offerId", -1)
        putExtra("offerName", "Scratch Card Recharge")
        putExtra("simSelection", simSelection)
        putExtra("signatureEnabled", false)
        putExtra("signatureMode", "STOP")
        putExtra("signatureLearning", false)
        putExtra("returnToAppAggressively", returnToAppAggressively)
        putStringArrayListExtra("scratchQueueCodes", ArrayList(queueCodes))
        putExtra("scratchQueueIndex", queueIndex)
        putExtra("scratchQueueTemplate", rechargeTemplate)
    })
}
