package com.bingwa.mobile

private val TRANSACTION_AMOUNT_REGEX = Regex("""\d+(?:\.\d+)?""")

internal fun transactionReason(tx: Transaction): String {
    val raw = tx.ussdResponse.ifBlank { tx.response }.trim()
    if (raw.isBlank()) return ""
    val firstNonEmpty = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
    return firstNonEmpty.ifBlank { raw }
}

internal fun transactionReasonShort(tx: Transaction, maxChars: Int = 90): String {
    val reason = transactionReason(tx)
    if (reason.isBlank()) return ""
    val normalized = reason.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars - 1).trimEnd() + "…"
}

internal fun formatExecutionMs(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    return when {
        durationMs < 1_000L -> "${durationMs}ms"
        durationMs < 60_000L -> "${durationMs}ms"
        else -> {
            val totalSeconds = durationMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            "${minutes}m ${seconds}s"
        }
    }
}

internal fun transactionAmountValue(tx: Transaction): Double {
    if (tx.amountValue > 0.0) return tx.amountValue
    return TRANSACTION_AMOUNT_REGEX.find(tx.amount)?.value?.toDoubleOrNull() ?: 0.0
}

internal fun isScheduledTransaction(tx: Transaction): Boolean = DailyLimitPolicy.isDailyLimitHold(tx)

internal fun resolveConfiguredOfferForTransaction(
    tx: Transaction,
    offers: List<OfferItem>
): OfferItem? {
    tx.offerId.takeIf { it >= 0 }?.let { offerId ->
        offers.firstOrNull { it.id == offerId }?.let { return it }
    }
    offers.firstOrNull { it.name.trim().equals(tx.description.trim(), ignoreCase = true) }?.let { return it }
    transactionAmountValue(tx).takeIf { it > 0.0 }?.let { amount ->
        offers.firstOrNull { it.price == amount.toInt() }?.let { return it }
    }
    val normalizedCode = UssdHelper.normalizeUssdCode(tx.ussdCode, tx.phoneNumber)
    return offers.firstOrNull {
        UssdHelper.normalizeUssdCode(it.ussdCode, tx.phoneNumber) == normalizedCode
    }
}

internal fun isUnmatchedTransaction(tx: Transaction, offers: List<OfferItem>): Boolean {
    if (isScheduledTransaction(tx)) return false
    if (tx.statusEnum == TransactionStatus.FAILED || tx.statusEnum == TransactionStatus.CANCELLED) return false
    if (transactionAmountValue(tx) <= 0.0 || tx.source == TX_SOURCE_AIRTIME) return false
    return resolveConfiguredOfferForTransaction(tx, offers) == null
}
