package com.bingwa.adminhub.data.repositories

import com.bingwa.adminhub.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UserRepository {
    private val _users = MutableStateFlow<List<AdminUser>>(emptyList())
    val users: Flow<List<AdminUser>> = _users.asStateFlow()

    suspend fun addUser(user: AdminUser) {
        _users.value = _users.value + user
    }

    suspend fun updateUser(user: AdminUser) {
        _users.value = _users.value.map { if (it.id == user.id) user else it }
    }

    suspend fun deleteUser(userId: String) {
        _users.value = _users.value.filter { it.id != userId }
    }

    suspend fun getUser(userId: String): AdminUser? {
        return _users.value.find { it.id == userId }
    }

    suspend fun searchUsers(query: String): List<AdminUser> {
        if (query.isBlank()) return _users.value
        val lower = query.lowercase()
        return _users.value.filter {
            it.phone.contains(lower) || it.name.contains(lower) || it.category.contains(lower)
        }
    }
}

class PurchaseRepository {
    private val _purchases = MutableStateFlow<List<PurchaseSms>>(emptyList())
    val purchases: Flow<List<PurchaseSms>> = _purchases.asStateFlow()

    suspend fun addPurchase(purchase: PurchaseSms) {
        _purchases.value = listOf(purchase) + _purchases.value
    }

    suspend fun getRecentPurchases(limit: Int = 50): List<PurchaseSms> {
        return _purchases.value.take(limit)
    }

    suspend fun getPurchasesForUser(phone: String): List<PurchaseSms> {
        return _purchases.value.filter { it.phone == phone }
    }
}

class TokenRepository {
    private val _transactions = MutableStateFlow<List<TokenTransaction>>(emptyList())
    val transactions: Flow<List<TokenTransaction>> = _transactions.asStateFlow()

    suspend fun addTransaction(transaction: TokenTransaction) {
        _transactions.value = listOf(transaction) + _transactions.value
    }

    suspend fun updateTransaction(transaction: TokenTransaction) {
        _transactions.value = _transactions.value.map { if (it.id == transaction.id) transaction else it }
    }

    suspend fun getTransactionsForUser(userId: String): List<TokenTransaction> {
        return _transactions.value.filter { it.userId == userId }
    }
}

class ScheduleRepository {
    private val _tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())
    val tasks: Flow<List<ScheduledTask>> = _tasks.asStateFlow()

    suspend fun addTask(task: ScheduledTask) {
        _tasks.value = listOf(task) + _tasks.value
    }

    suspend fun updateTask(task: ScheduledTask) {
        _tasks.value = _tasks.value.map { if (it.id == task.id) task else it }
    }

    suspend fun deleteTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
    }

    suspend fun getEnabledTasks(): List<ScheduledTask> {
        return _tasks.value.filter { it.enabled }
    }
}

class SmsTemplateRepository {
    private val _templates = MutableStateFlow<List<SmsTemplate>>(emptyList())
    val templates: Flow<List<SmsTemplate>> = _templates.asStateFlow()

    suspend fun addTemplate(template: SmsTemplate) {
        _templates.value = listOf(template) + _templates.value
    }

    suspend fun updateTemplate(template: SmsTemplate) {
        _templates.value = _templates.value.map { if (it.id == template.id) template else it }
    }

    suspend fun deleteTemplate(templateId: String) {
        _templates.value = _templates.value.filter { it.id != templateId }
    }

    suspend fun getTemplatesByCategory(category: TemplateCategory): List<SmsTemplate> {
        return _templates.value.filter { it.category == category }
    }
}
