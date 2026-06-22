package com.bingwa.mobile

import java.util.Locale

internal fun transactionFailureReason(tx: Transaction): String {
    val candidate = tx.ussdResponse.ifBlank { tx.response }
        .ifBlank { tx.ussdTranscript }
        .trim()
    if (candidate.isBlank()) return ""
    return candidate
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?.take(120)
        .orEmpty()
}

internal fun isAlreadyPurchasedReason(text: String): Boolean {
    val t = text.lowercase(Locale.getDefault())
    return t.contains("already") && (
        t.contains("purchased") ||
            t.contains("bought") ||
            t.contains("received") ||
            t.contains("subscribed") ||
            t.contains("active") ||
            t.contains("requested") ||
            t.contains("benefit")
        )
}

internal fun shouldSuggestAlternativeNumber(tx: Transaction): Boolean {
    if (tx.phoneNumber.isBlank()) return false
    if (tx.statusEnum != TransactionStatus.FAILED && tx.statusEnum != TransactionStatus.CANCELLED) return false
    return isAlreadyPurchasedReason(transactionFailureReason(tx))
}

