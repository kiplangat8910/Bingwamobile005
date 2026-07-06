package com.bingwa.mobile

import android.content.Context
import android.util.Log

class UssdResponsePatternManager(private val context: Context) {

    companion object {
        private const val TAG = "UssdPatternManager"
        private const val DEFAULT_MAINTENANCE_RETRY_DELAY_MS = 5_000L
        private const val DEFAULT_MAX_MAINTENANCE_RETRIES = 3
        private val WHITESPACE_REGEX = Regex("\\s+")

        val DEFAULT_SUCCESS_PATTERNS: List<String> = listOf(
            "You have successfully purchased",
            "Kindly wait as we process your request. Thank you",
            "Kindly wait while we process your request",
            "Keep selling",
            "Submitted successfully",
            "Keep selling!!",
            "Keep selling!! Be a Bingwa Sokoni Champion",
            "Kindly wait",
            "successful",
            "success",
            "processed",
            "delivered",
            "activated",
            "confirmed",
            "bundle activated"
        )
        val DEFAULT_FAILED_PATTERNS: List<String> = listOf(
            "USSD failure",
            "insufficient airtime",
            "Okoa Jahazi and cannot receive bundles",
            "Dear Partner",
            "Recommendation failed",
            "failed",
            "not allowed",
            "invalid",
            "error",
            "rejected"
        )
        val DEFAULT_MAINTENANCE_PATTERNS: List<String> = listOf(
            "Service is currently under maintenance",
            "under maintenance",
            "under maintainance",
            "please try again later",
            "temporarily unavailable",
            "service unavailable",
            "technical problem"
        )
        val DEFAULT_RETRIABLE_FINAL_PATTERNS: List<String> = listOf(
            "connection problem",
            "invalid mmi",
            "mmi code",
            "network error",
            "temporary error",
            "request timeout",
            "timeout",
            "service unavailable",
            "temporarily unavailable",
            "technical problem",
            "under maintenance",
            "under maintainance",
            "maintenance",
            "maintainance",
            "session expired",
            "error"
        )
        val DEFAULT_ALREADY_RECOMMENDED_PATTERNS: List<String> = listOf(
            "has already been recommended the same product today",
            "has already been recommended",
            "Failed. 254 has already been recommended today",
            "has already been recommended today",
            "already been recommended",
            "already purchased",
            "already received",
            "same product today",
            "once per day"
        )
        val DEFAULT_FAILED_RETRY_PATTERNS: List<String> = listOf("*1#", "***#")
    }

    init { Log.d(TAG, "Using built-in USSD response patterns") }

    fun determineResponseStatus(response: CharSequence?): String {
        val normalized = normalize(response)
        if (normalized.isBlank()) return "Failed"
        return when {
            matchesFailedRetryPattern(normalized) -> "Failed"
            matchesAlreadyRecommendedPattern(normalized) -> "Pending"
            matchesSuccessPattern(normalized) -> "Success"
            matchesMaintenancePattern(normalized) -> "UnderMaintenance"
            matchesFailedPattern(normalized) -> "Failed"
            else -> "Failed"
        }
    }

    fun getSuccessPatterns() = DEFAULT_SUCCESS_PATTERNS
    fun getFailedPatterns() = DEFAULT_FAILED_PATTERNS
    fun getMaintenancePatterns() = DEFAULT_MAINTENANCE_PATTERNS
    fun getRetriableFinalPatterns() = DEFAULT_RETRIABLE_FINAL_PATTERNS
    fun getAlreadyRecommendedPatterns() = DEFAULT_ALREADY_RECOMMENDED_PATTERNS
    fun getFailedRetryPatterns() = DEFAULT_FAILED_RETRY_PATTERNS
    fun getMaxMaintenanceRetries() = DEFAULT_MAX_MAINTENANCE_RETRIES
    fun getMaintenanceRetryDelayMs() = DEFAULT_MAINTENANCE_RETRY_DELAY_MS

    fun matchesSuccessPattern(response: CharSequence?) = matchAny(response, getSuccessPatterns())
    fun matchesFailedPattern(response: CharSequence?) = matchAny(response, getFailedPatterns())
    fun matchesMaintenancePattern(response: CharSequence?) = matchAny(response, getMaintenancePatterns())
    fun matchesRetriableFinalPattern(response: CharSequence?) = matchAny(response, getRetriableFinalPatterns())
    fun matchesAlreadyRecommendedPattern(response: CharSequence?) = matchAny(response, getAlreadyRecommendedPatterns())
    fun matchesFailedRetryPattern(response: CharSequence?) = matchAny(response, getFailedRetryPatterns())

    fun saveSuccessPatterns(patterns: List<String>) { Log.w(TAG, "saveSuccessPatterns ignored; using built-in patterns") }
    fun saveFailedPatterns(patterns: List<String>) { Log.w(TAG, "saveFailedPatterns ignored; using built-in patterns") }
    fun saveMaintenancePatterns(patterns: List<String>) { Log.w(TAG, "saveMaintenancePatterns ignored; using built-in patterns") }
    fun saveAlreadyRecommendedPatterns(patterns: List<String>) { Log.w(TAG, "saveAlreadyRecommendedPatterns ignored; using built-in patterns") }
    fun saveFailedRetryPatterns(patterns: List<String>) { Log.w(TAG, "saveFailedRetryPatterns ignored; using built-in patterns") }
    fun saveMaxMaintenanceRetries(max: Int) { Log.w(TAG, "saveMaxMaintenanceRetries ignored; using built-in value") }
    fun saveMaintenanceRetryDelayMs(delayMs: Long) { Log.w(TAG, "saveMaintenanceRetryDelayMs ignored; using built-in value") }

    fun resetToDefaults() { Log.i(TAG, "resetToDefaults ignored; using built-in patterns") }

    private fun matchAny(response: CharSequence?, patterns: List<String>): Boolean {
        val normalizedResponse = normalize(response)
        if (normalizedResponse.isBlank()) return false
        return patterns.any { normalizedResponse.contains(normalize(it)) }
    }

    private fun normalize(value: CharSequence?): String =
        value?.toString()
            ?.lowercase()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            .orEmpty()
}
