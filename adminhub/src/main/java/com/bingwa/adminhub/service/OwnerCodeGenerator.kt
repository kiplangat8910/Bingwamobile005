package com.bingwa.adminhub.service

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

object OwnerCodeGenerator {
    private const val TAG = "OwnerCodeGenerator"

    fun generateActivateCode(code: String = ""): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return if (code.isNotBlank()) {
            "D%d%s%dT%sY".format(min, part, hourCode, code)
        } else {
            "D%d%s%dT%dY".format(min, part, hourCode, (now.timeInMillis / 60000 % 100000).toInt())
        }
    }

    fun generateClearCode(): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "R%d%s%dLC".format(min, part, hourCode)
    }

    fun generateClearUnlimitedCode(): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "R%d%s%dLU".format(min, part, hourCode)
    }

    fun generateGiftCode(tokens: Int): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "D%d%s%dT%dY".format(min, part, hourCode, tokens.coerceAtLeast(1))
    }

    fun generateRemoteAddCode(tokens: Int): String {
        return generateGiftCode(tokens)
    }

    fun generateDailyUnlimitedCode(days: Int = 1): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "U%d%s%dTD%d".format(min, part, hourCode, days.coerceAtLeast(1))
    }

    fun generateWeeklyUnlimitedCode(weeks: Int = 1): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "U%d%s%dTW%d".format(min, part, hourCode, weeks.coerceAtLeast(1))
    }

    fun generateMonthlyUnlimitedCode(months: Int = 1): String {
        val now = Calendar.getInstance()
        val min = now.get(Calendar.MINUTE)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val part = getPart(hour)
        val hourCode = toHourCode(hour, part)
        return "U%d%s%dTM%d".format(min, part, hourCode, months.coerceAtLeast(1))
    }

    fun generateBuyCode(phone: String, offerId: Int): String {
        return "BUY %s %d".format(phone, offerId)
    }

    fun generateBuyAmountCode(phone: String, amount: Int): String {
        return "BUYAMT %s %d".format(phone, amount)
    }

    fun generateBalanceCode(): String = "BALANCE"
    fun generateStatusCode(): String = "STATUS"
    fun generateTokensCode(): String = "TOKENS"

    private fun getPart(hour24: Int): Char = when (hour24) {
        in 5..11 -> 'M'
        in 12..16 -> 'A'
        in 17..20 -> 'E'
        else -> 'N'
    }

    private fun toHourCode(hour24: Int, part: Char): Int = when (part) {
        'M' -> hour24.coerceIn(5, 11)
        'A' -> when (hour24) {
            12 -> 12
            in 13..16 -> hour24 - 12
            else -> 1
        }
        'E' -> (hour24 - 12).coerceIn(1, 8)
        'N' -> when (hour24) {
            0 -> 12
            in 1..4 -> hour24
            in 21..23 -> hour24 - 12
            else -> 12
        }
        else -> 1
    }

    fun isValidNow(min: Int, part: Char, hourCode: Int): Boolean {
        val now = Calendar.getInstance()
        val nowHour = now.get(Calendar.HOUR_OF_DAY)
        val nowMin = now.get(Calendar.MINUTE)

        if (!isInPart(nowHour, part)) return false
        val expectedHour24 = toHour24(hourCode, part)
        if (expectedHour24 < 0) return false
        if (nowHour != expectedHour24) return false
        return nowMin in min..minOf(59, min + 9)
    }

    private fun isInPart(hour24: Int, part: Char): Boolean = when (part) {
        'M' -> hour24 in 5..11
        'A' -> hour24 in 12..16
        'E' -> hour24 in 17..20
        'N' -> hour24 in 21..23 || hour24 in 0..4
        else -> false
    }

    private fun toHour24(hour12: Int, part: Char): Int = when (part) {
        'M' -> if (hour12 in 5..11) hour12 else -1
        'A' -> when (hour12) {
            12 -> 12
            in 1..4 -> hour12 + 12
            else -> -1
        }
        'E' -> if (hour12 in 1..8) hour12 + 12 else -1
        'N' -> when (hour12) {
            12 -> 0
            in 1..4 -> hour12
            in 9..11 -> hour12 + 12
            else -> -1
        }
        else -> -1
    }
}
