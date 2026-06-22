package com.bingwa.mobile

import android.content.Context

class TokenManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("TokenStore", Context.MODE_PRIVATE)
    fun getBalance(): Int = prefs.safeGetInt("balance", 0)
    fun addTokens(amount: Int) { val newBal = getBalance() + amount; prefs.edit().putInt("balance", newBal).apply(); tokenBalanceListener?.invoke(newBal) }
    fun spendTokens(amount: Int): Boolean { val cur = getBalance(); if (cur < amount) return false; val newBal = cur - amount; prefs.edit().putInt("balance", newBal).apply(); tokenBalanceListener?.invoke(newBal); return true }
    fun clearBalance(clearUnlimited: Boolean = false) {
        prefs.edit().putInt("balance", 0).apply()
        if (clearUnlimited) {
            UnlimitedManager(context).clear()
        }
        tokenBalanceListener?.invoke(0)
    }
    companion object {
        var tokenBalanceListener: ((Int) -> Unit)? = null
        fun convertAmountToTokens(ksh: Int): Int = when (ksh) {
            15 -> 105
            25 -> 255
            55 -> 605
            100 -> 1200
            else -> ksh
        }
    }
}
