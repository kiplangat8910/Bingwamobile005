package com.bingwa.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instant UI synchronization for real-time updates.
 * Broadcast transactions, tokens, and settings changes immediately to UI.
 */
object InstantUISync {
    private const val TAG = "InstantUISync"

    // Broadcast action constants
    const val ACTION_TRANSACTION_UPDATED = "com.bingwa.mobile.TX_UPDATED_INSTANT"
    const val ACTION_TOKENS_UPDATED = "com.bingwa.mobile.TOKENS_UPDATED_INSTANT"
    const val ACTION_SETTINGS_UPDATED = "com.bingwa.mobile.SETTINGS_UPDATED_INSTANT"
    const val ACTION_BALANCE_UPDATED = "com.bingwa.mobile.BALANCE_UPDATED_INSTANT"

    // Extra keys
    const val EXTRA_TX_ID = "txId"
    const val EXTRA_STATUS = "status"
    const val EXTRA_TOKENS = "tokens"
    const val EXTRA_AMOUNT = "amount"
    const val EXTRA_SETTING_KEY = "settingKey"
    const val EXTRA_SETTING_VALUE = "settingValue"
    const val EXTRA_BALANCE = "balance"

    // LiveData observers
    private val transactionUpdates = MutableLiveData<TransactionUpdate>()
    private val tokenUpdates = MutableLiveData<Int>()
    private val settingUpdates = MutableLiveData<SettingUpdate>()
    private val balanceUpdates = MutableLiveData<String>()

    data class TransactionUpdate(val txId: Int, val status: String, val response: String = "")
    data class SettingUpdate(val key: String, val value: Any?)

    /**
     * Broadcast instant transaction update
     */
    fun broadcastTransactionUpdate(context: Context, txId: Int, status: String, response: String = "") {
        val intent = Intent(ACTION_TRANSACTION_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TX_ID, txId)
            putExtra(EXTRA_STATUS, status)
            putExtra("response", response)
        }
        context.sendBroadcast(intent)
        Handler(Looper.getMainLooper()).post {
            transactionUpdates.value = TransactionUpdate(txId, status, response)
        }
    }

    /**
     * Broadcast instant token update
     */
    fun broadcastTokenUpdate(context: Context, tokens: Int) {
        val intent = Intent(ACTION_TOKENS_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_TOKENS, tokens)
        }
        context.sendBroadcast(intent)
        Handler(Looper.getMainLooper()).post {
            tokenUpdates.value = tokens
        }
    }

    /**
     * Broadcast instant settings update
     */
    fun broadcastSettingUpdate(context: Context, key: String, value: Any?) {
        val intent = Intent(ACTION_SETTINGS_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_SETTING_KEY, key)
            when (value) {
                is String -> putExtra(EXTRA_SETTING_VALUE, value)
                is Int -> putExtra(EXTRA_SETTING_VALUE, value)
                is Boolean -> putExtra(EXTRA_SETTING_VALUE, value)
                else -> putExtra(EXTRA_SETTING_VALUE, value?.toString() ?: "")
            }
        }
        context.sendBroadcast(intent)
        Handler(Looper.getMainLooper()).post {
            settingUpdates.value = SettingUpdate(key, value)
        }
    }

    /**
     * Broadcast instant balance update
     */
    fun broadcastBalanceUpdate(context: Context, balance: String) {
        val intent = Intent(ACTION_BALANCE_UPDATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_BALANCE, balance)
        }
        context.sendBroadcast(intent)
        Handler(Looper.getMainLooper()).post {
            balanceUpdates.value = balance
        }
    }

    /**
     * Get LiveData for transaction updates
     */
    fun getTransactionUpdates() = transactionUpdates

    /**
     * Get LiveData for token updates
     */
    fun getTokenUpdates() = tokenUpdates

    /**
     * Get LiveData for settings updates
     */
    fun getSettingUpdates() = settingUpdates

    /**
     * Get LiveData for balance updates
     */
    fun getBalanceUpdates() = balanceUpdates
}

/**
 * Broadcast receiver for instant UI updates.
 * Activity/Fragment should register this during onStart and unregister during onStop.
 */
class InstantSyncReceiver(private val onUpdate: (Intent) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("InstantSyncReceiver", "Received: ${intent.action}")
        onUpdate(intent)
    }
}

/**
 * Lifecycle-aware instant sync observer for Activities.
 * Automatically registers/unregisters broadcast receiver.
 */
class InstantSyncLifecycleObserver(
    private val context: Context,
    private val onUpdate: (Intent) -> Unit
) : DefaultLifecycleObserver {

    private var receiver: InstantSyncReceiver? = null

    override fun onStart(owner: LifecycleOwner) {
        receiver = InstantSyncReceiver(onUpdate)
        val filter = IntentFilter().apply {
            addAction(InstantUISync.ACTION_TRANSACTION_UPDATED)
            addAction(InstantUISync.ACTION_TOKENS_UPDATED)
            addAction(InstantUISync.ACTION_SETTINGS_UPDATED)
            addAction(InstantUISync.ACTION_BALANCE_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
}
