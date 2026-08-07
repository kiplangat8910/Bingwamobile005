package com.bingwa.adminhub.data.parser

import com.bingwa.adminhub.data.models.PurchaseSms
import java.util.regex.Pattern

object SmsPurchaseParser {

    private val SMS_PATTERN = Pattern.compile(
        "The\\s+subscriber\\s+(\\d{10,13})\\s+transferred\\s+([\\d.]+)\\s+KSH\\s+for\\s+you\\." +
                "\\s*Your\\s+balance\\s+is\\s+([\\d.]+)\\s+KSH\\s+now,\\s+and\\s+the\\s+expiration\\s+date\\s+is\\s+([A-Za-z]+\\s+\\d{1,2}\\s+\\d{4})\\." +
                "(.*)",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(message: String): PurchaseSms? {
        val matcher = SMS_PATTERN.matcher(message.trim())
        if (!matcher.find()) return null

        val phone = matcher.group(1) ?: return null
        val amount = matcher.group(2)?.toDoubleOrNull() ?: return null
        val balance = matcher.group(3)?.toDoubleOrNull() ?: return null
        val expirationDate = matcher.group(4) ?: return null
        val extra = matcher.group(5) ?: ""

        return PurchaseSms(
            id = generateId(phone, amount, System.currentTimeMillis()),
            phone = phone,
            amount = amount,
            balance = balance,
            expirationDate = expirationDate,
            rawMessage = message.trim()
        )
    }

    private fun generateId(phone: String, amount: Double, timestamp: Long): String {
        return "purchase_${phone}_${amount}_$timestamp"
    }
}
