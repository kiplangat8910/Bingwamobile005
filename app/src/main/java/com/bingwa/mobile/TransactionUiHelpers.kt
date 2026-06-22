package com.bingwa.mobile

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
