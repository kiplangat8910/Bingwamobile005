package com.bingwa.mobile

internal data class AlternativeNumberDispatchResult(
    val success: Boolean,
    val message: String,
    val newTxId: Int = -1
)

/**
 * Starts a new dispatch using the same offer/amount as [originalTx] but a different phone number.
 * This is used when the transaction failed because the original number already received the offer.
 */
internal fun dispatchAlternativeNumberNow(
    context: android.content.Context,
    originalTx: Transaction,
    alternativePhone: String
): AlternativeNumberDispatchResult {
    val normalizedAlt = SmsCommandHandler.normalizePhone(alternativePhone)
    if (!normalizedAlt.matches(Regex("^0\\d{9}$"))) {
        return AlternativeNumberDispatchResult(false, "Invalid alternative number.")
    }

    val offer = OfferRepository.findByName(context, originalTx.description)
        ?: originalTx.amountValue.toInt().takeIf { it > 0 }?.let { OfferRepository.findByPrice(context, it) }

    if (offer != null && RelayManager.isPrimary(context) && offer.targetDevice.uppercase() == "RELAY") {
        val sent = RelayManager.forwardBuyAmount(context, normalizedAlt, offer.price)
        return if (sent) {
            AlternativeNumberDispatchResult(
                success = true,
                message = "Forwarded to Relay for $normalizedAlt using ${offer.name}."
            )
        } else {
            AlternativeNumberDispatchResult(
                success = false,
                message = "Relay forwarding failed. Please try again shortly."
            )
        }
    }

    val finalCode = when {
        offer != null -> UssdHelper.normalizeUssdCode(offer.ussdCode, normalizedAlt)
        originalTx.ussdCode.isNotBlank() && originalTx.phoneNumber.isNotBlank() && originalTx.ussdCode.contains(originalTx.phoneNumber) ->
            originalTx.ussdCode.replace(originalTx.phoneNumber, normalizedAlt, ignoreCase = true)
        else -> ""
    }
    if (finalCode.isBlank()) {
        return AlternativeNumberDispatchResult(false, "Could not rebuild the bundle command for the alternative number.")
    }

    val txId = createPendingTransaction(
        ctx = context,
        description = originalTx.description.ifBlank { offer?.name ?: "Bundle" },
        amount = originalTx.amount.ifBlank { offer?.price?.let { "KSh $it" } ?: "" },
        phone = normalizedAlt,
        ussd = finalCode,
        clientName = originalTx.clientName,
        status = TransactionStatus.PROCESSING.value,
        source = originalTx.source,
        showInRecent = originalTx.showInRecent,
        offerId = offer?.id ?: originalTx.offerId
    )
    if (txId < 0) {
        return AlternativeNumberDispatchResult(false, "Could not save the alternative-number request.")
    }

    context.startOfferAutomation(
        offer = offer,
        phoneNumber = normalizedAlt,
        txId = txId,
        finalCode = finalCode,
        mode = offer?.executionMode ?: "ADVANCED"
    )

    val offerLabel = offer?.name ?: originalTx.description.ifBlank { "bundle" }
    return AlternativeNumberDispatchResult(
        success = true,
        message = "Dispatch started for $normalizedAlt using $offerLabel.",
        newTxId = txId
    )
}

