package com.bingwa.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object TransactionStore {
    private const val PREFS_NAME = "transactions"
    private const val KEY_LIST = "list"
    private const val MAX_RECENT_TRANSACTIONS = 100
    private val AMOUNT_REGEX = Regex("""\d+(?:\.\d+)?"""))
    private val lock = Any()

    @Volatile
    private var cachedRawJson: String? = null

    @Volatile
    private var cachedTransactions: List<Transaction>? = null

    fun load(context: Context): MutableList<Transaction> {
        return synchronized(lock) {
            val rawJson = rawJson(context)
            val cached = cachedTransactions
            if (rawJson == cachedRawJson && cached != null) {
                return@synchronized cached.map { it.copy() }.toMutableList()
            }
            val parsed = parse(rawJson)
            updateCache(rawJson, parsed)
            parsed.toMutableList()
        }
    }

    fun save(context: Context, list: List<Transaction>) {
        synchronized(lock) {
            val normalized = list.map { it.normalized() }
            val json = JSONArray().apply {
                normalized.forEach { put(it.toJson()) }
            }.toString()
            updateCache(json, normalized)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LIST, json)
                .apply()
        }
    }

    fun findById(context: Context, txId: Int): Transaction? {
        if (txId < 0) return null
        return load(context).firstOrNull { it.id == txId }
    }

    /**
     * Update an existing transaction without creating duplicates.
     * Used for retry operations.
     */
    fun updateExisting(
        context: Context,
        txId: Int,
        status: String,
        response: String,
        transcript: String? = null
    ): Boolean {
        return synchronized(lock) {
            if (txId < 0) return@synchronized false
            val current = load(context)
            val index = current.indexOfFirst { it.id == txId }
            if (index < 0) return@synchronized false

            val existing = current[index]
            val normalizedStatus = TransactionStatus.fromString(status)
            val completedAt = when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> System.currentTimeMillis()
                else -> existing.completedAt
            }
            val executionDurationMs = when {
                completedAt > 0L && existing.timestamp > 0L -> (completedAt - existing.timestamp).coerceAtLeast(0L)
                else -> existing.executionDurationMs
            }

            current[index] = existing.copy(
                status = status,
                statusEnum = normalizedStatus,
                ussdResponse = response,
                ussdTranscript = transcript ?: existing.ussdTranscript,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )
            save(context, current)
            true
        }
    }

    fun createPendingTransaction(
        context: Context,
        description: String,
        amount: String,
        phone: String,
        ussd: String,
        clientName: String = "",
        status: String = TransactionStatus.PENDING.value,
        source: String = TX_SOURCE_SYSTEM,
        showInRecent: Boolean = false,
        offerId: Int = -1
    ): Int {
        val created = synchronized(lock) {
            val current = load(context)
            val usedIds = current.asSequence().map { it.id }.toHashSet()
            var newId = ((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(1)
            while (!usedIds.add(newId)) {
                newId = if (newId == Int.MAX_VALUE) 1 else newId + 1
            }

            val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val created = Transaction(
                id = newId,
                description = description,
                amount = amount,
                amountValue = extractAmountValue(amount),
                date = date,
                status = status,
                statusEnum = TransactionStatus.fromString(status),
                ussdCode = ussd,
                phoneNumber = phone,
                clientName = formatClientName(clientName),
                ussdResponse = "",
                ussdTranscript = "",
                timestamp = System.currentTimeMillis(),
                source = source,
                showInRecent = showInRecent,
                offerId = offerId
            )

            val updated = ArrayList<Transaction>(minOf(current.size + 1, MAX_RECENT_TRANSACTIONS))
            updated += created
            current.take(MAX_RECENT_TRANSACTIONS - 1).forEach(updated::add)
            save(context, updated)
            created
        }
        broadcastTransactionCreated(context, created.id)
        notifyDispatchStarted(context, created)
        return created.id
    }

    fun insertTransaction(context: Context, tx: Transaction): Int {
        val created = synchronized(lock) {
            val current = load(context)
            val usedIds = current.asSequence().map { it.id }.toHashSet()
            var newId = ((System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()).coerceAtLeast(1)
            while (!usedIds.add(newId)) {
                newId = if (newId == Int.MAX_VALUE) 1 else newId + 1
            }

            val createdAt = tx.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
            val normalizedStatus = TransactionStatus.fromString(tx.status)
            val completedAt = tx.completedAt.takeIf { it > 0L } ?: when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> createdAt
                else -> 0L
            }
            val executionDurationMs = tx.executionDurationMs.takeIf { it > 0L } ?: when {
                completedAt > 0L -> (completedAt - createdAt).coerceAtLeast(0L)
                else -> 0L
            }

            val date = tx.date.ifBlank {
                java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt))
            }
            val created = tx.copy(
                id = newId,
                amountValue = tx.amountValue.takeIf { it > 0.0 } ?: extractAmountValue(tx.amount),
                date = date,
                statusEnum = normalizedStatus,
                clientName = formatClientName(tx.clientName),
                timestamp = createdAt,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )

            val updated = ArrayList<Transaction>(minOf(current.size + 1, MAX_RECENT_TRANSACTIONS))
            updated += created
            current.take(MAX_RECENT_TRANSACTIONS - 1).forEach(updated::add)
            save(context, updated)
            created
        }
        broadcastTransactionCreated(context, created.id)
        notifyInsertedTransaction(context, created)
        return created.id
    }

    fun saveOutcome(
        context: Context,
        txId: Int,
        status: String,
        response: String,
        transcript: String? = null
    ): Boolean {
        val updatedTransaction = synchronized(lock) {
            if (txId < 0) return@synchronized null
            val current = load(context)
            val index = current.indexOfFirst { it.id == txId }
            if (index < 0) return@synchronized null
            val existing = current[index]
            val normalizedStatus = TransactionStatus.fromString(status)
            val completedAt = when (normalizedStatus) {
                TransactionStatus.SUCCESS,
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED -> System.currentTimeMillis()
                else -> existing.completedAt
            }
            val executionDurationMs = when {
                completedAt > 0L && existing.timestamp > 0L -> (completedAt - existing.timestamp).coerceAtLeast(0L)
                else -> existing.executionDurationMs
            }
            current[index] = existing.copy(
                status = status,
                statusEnum = normalizedStatus,
                ussdResponse = response,
                ussdTranscript = transcript ?: existing.ussdTranscript,
                completedAt = completedAt,
                executionDurationMs = executionDurationMs
            )
            save(context, current)
            current[index]
        }
        if (updatedTransaction == null) return false
        notifyDispatchOutcome(context, updatedTransaction)
        return true
    }

    fun clear(context: Context) {
        synchronized(lock) {
            updateCache("[]", emptyList())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LIST)
                .apply()
        }
    }
