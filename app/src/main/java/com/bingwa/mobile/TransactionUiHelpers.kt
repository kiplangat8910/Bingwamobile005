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

private val SCHEDULED_TOMORROW_PATTERNS = listOf(
    "scheduled for tomorrow",
    "queued for tomorrow",
    "will be dispatched tomorrow",
    "confirmed dispatch for tomorrow",
    "confirmed for tomorrow morning",
    "dispatch for tomorrow morning was confirmed"
)

internal fun isTransactionScheduled(tx: Transaction): Boolean {
    if (!DailyLimitPolicy.isDailyLimitHold(tx)) return false
    val details = buildString {
        append(tx.ussdResponse)
        append('\n')
        append(tx.response)
        append('\n')
        append(tx.ussdTranscript)
    }.lowercase()
    return SCHEDULED_TOMORROW_PATTERNS.any(details::contains)
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
