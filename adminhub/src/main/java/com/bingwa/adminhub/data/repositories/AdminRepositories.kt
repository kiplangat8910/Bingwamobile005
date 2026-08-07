package com.bingwa.adminhub.data.repositories

import com.bingwa.adminhub.data.local.dao.PurchaseDao
import com.bingwa.adminhub.data.local.dao.ScheduleDao
import com.bingwa.adminhub.data.local.dao.TemplateDao
import com.bingwa.adminhub.data.local.dao.TokenDao
import com.bingwa.adminhub.data.local.dao.UserDao
import com.bingwa.adminhub.data.local.entity.PurchaseEntity
import com.bingwa.adminhub.data.local.entity.ScheduleEntity
import com.bingwa.adminhub.data.local.entity.TemplateEntity
import com.bingwa.adminhub.data.local.entity.TokenEntity
import com.bingwa.adminhub.data.local.entity.UserEntity
import com.bingwa.adminhub.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class UserRepository(private val dao: UserDao) {
    val users: Flow<List<AdminUser>> = dao.getAll().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun addUser(user: AdminUser) {
        dao.insert(user.toEntity())
    }

    suspend fun updateUser(user: AdminUser) {
        dao.update(user.toEntity())
    }

    suspend fun deleteUser(userId: String) {
        dao.getById(userId)?.let { dao.delete(it) }
    }

    suspend fun getUser(userId: String): AdminUser? {
        return dao.getById(userId)?.toModel()
    }

    fun searchUsers(query: String): Flow<List<AdminUser>> {
        return dao.search(query).map { entities ->
            entities.map { it.toModel() }
        }
    }
}

class PurchaseRepository(private val dao: PurchaseDao) {
    val purchases: Flow<List<PurchaseSms>> = dao.getAll().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun addPurchase(purchase: PurchaseSms) {
        dao.insert(purchase.toEntity())
    }

    suspend fun getRecentPurchases(limit: Int = 50): List<PurchaseSms> {
        return dao.getRecent(limit).map { it.toModel() }
    }

    fun getPurchasesForUser(phone: String): Flow<List<PurchaseSms>> {
        return dao.getByPhone(phone).map { entities ->
            entities.map { it.toModel() }
        }
    }
}

class TokenRepository(private val dao: TokenDao) {
    val transactions: Flow<List<TokenTransaction>> = dao.getAll().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun addTransaction(transaction: TokenTransaction) {
        dao.insert(transaction.toEntity())
    }

    suspend fun updateTransaction(transaction: TokenTransaction) {
        dao.update(transaction.toEntity())
    }

    fun getTransactionsForUser(userId: String): Flow<List<TokenTransaction>> {
        return dao.getByUser(userId).map { entities ->
            entities.map { it.toModel() }
        }
    }
}

class ScheduleRepository(private val dao: ScheduleDao) {
    val tasks: Flow<List<ScheduledTask>> = dao.getAll().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun addTask(task: ScheduledTask) {
        dao.insert(task.toEntity())
    }

    suspend fun updateTask(task: ScheduledTask) {
        dao.update(task.toEntity())
    }

    suspend fun deleteTask(taskId: String) {
        dao.getById(taskId)?.let { dao.delete(it) }
    }

    fun getEnabledTasks(): Flow<List<ScheduledTask>> {
        return dao.getEnabled().map { entities ->
            entities.map { it.toModel() }
        }
    }
}

class SmsTemplateRepository(private val dao: TemplateDao) {
    val templates: Flow<List<SmsTemplate>> = dao.getAll().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun addTemplate(template: SmsTemplate) {
        dao.insert(template.toEntity())
    }

    suspend fun updateTemplate(template: SmsTemplate) {
        dao.update(template.toEntity())
    }

    suspend fun deleteTemplate(templateId: String) {
        dao.getById(templateId)?.let { dao.delete(it) }
    }

    fun getTemplatesByCategory(category: TemplateCategory): Flow<List<SmsTemplate>> {
        return dao.getByCategory(category.name).map { entities ->
            entities.map { it.toModel() }
        }
    }
}
