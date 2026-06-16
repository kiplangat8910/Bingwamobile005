package com.bingwa.mobile

import android.content.Context
import android.content.Intent

fun Context.startOfferAutomation(
    offer: OfferItem?,
    phoneNumber: String,
    txId: Int,
    finalCode: String,
    mode: String = offer?.executionMode ?: "ADVANCED",
    signatureLearning: Boolean = false
) {
    ServiceLauncher.startAutomationService(this, Intent(this, AutomationService::class.java).apply {
        putExtra("mode", mode)
        putExtra("code", finalCode)
        putExtra("phoneNumber", phoneNumber)
        putExtra("txId", txId)
        putExtra("signatureLearning", signatureLearning)
        if (offer != null) {
            putExtra("offerId", offer.id)
            putExtra("offerName", offer.name)
            putExtra("signatureEnabled", offer.signatureDetectionEnabled)
            putExtra("signatureMode", offer.signatureAction)
        }
    })
}
