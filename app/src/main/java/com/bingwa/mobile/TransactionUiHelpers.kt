package com.bingwa.mobile

import android.content.Context

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

internal fun suggestedAlternativeNumbers(context: Context, tx: Transaction, maxResults: Int = 3): List<String> {
    val name = formatClientName(tx.clientName)
    if (name.isBlank()) return emptyList()
    val normalizedCurrent = SmsCommandHandler.normalizePhone(tx.phoneNumber)
    val results = linkedSetOf<String>()

    loadContacts(context.getSharedPreferences("saved_contacts", Context.MODE_PRIVATE))
        .asSequence()
        .filter { it.name.isNotBlank() && namesLikelyMatch(it.name, name) }
        .map { SmsCommandHandler.normalizePhone(it.phone) }
        .filter { it.matches(Regex("^0\\d{9}$")) && it != normalizedCurrent }
        .forEach { if (results.size < maxResults) results.add(it) }

    if (results.size >= maxResults) return results.toList()

    TransactionStore.load(context)
        .asSequence()
        .filter { it.clientName.isNotBlank() && namesLikelyMatch(it.clientName, name) }
        .map { SmsCommandHandler.normalizePhone(it.phoneNumber) }
        .filter { it.matches(Regex("^0\\d{9}$")) && it != normalizedCurrent }
        .forEach { if (results.size < maxResults) results.add(it) }

    return results.toList()
}

