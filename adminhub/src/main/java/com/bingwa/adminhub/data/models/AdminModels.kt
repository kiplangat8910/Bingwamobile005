package com.bingwa.adminhub.data.models

import com.bingwa.adminhub.data.local.entity.PurchaseEntity
import com.bingwa.adminhub.data.local.entity.ScheduleEntity
import com.bingwa.adminhub.data.local.entity.TemplateEntity
import com.bingwa.adminhub.data.local.entity.TokenEntity
import com.bingwa.adminhub.data.local.entity.UserEntity

data class PurchaseSms(
    val id: String,
    val phone: String,
    val amount: Double,
    val balance: Double,
    val expirationDate: String,
    val rawMessage: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AdminUser(
    val id: String,
    val phone: String,
    val name: String,
    val category: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class TokenTransaction(
    val id: String,
    val userId: String,
    val type: TokenType,
    val amount: Double = 0.0,
    val code: String = "",
    val message: String = "",
    val status: TransactionStatus = TransactionStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis()
)

data class ScheduledTask(
    val id: String,
    val userId: String,
    val action: ScheduledAction,
    val scheduledAt: Long,
    val repeat: RepeatMode = RepeatMode.ONCE,
    val code: String = "",
    val message: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class SmsTemplate(
    val id: String,
    val name: String,
    val body: String,
    val category: TemplateCategory,
    val createdAt: Long = System.currentTimeMillis()
)

enum class TokenType {
    ACTIVATE,
    CLEAR,
    GIFT,
    UNLIMITED,
    REMOTE_ADD
}

enum class TransactionStatus {
    PENDING,
    SENT,
    FAILED
}

enum class ScheduledAction {
    ACTIVATE,
    CLEAR,
    GIFT,
    REMOTE_ADD,
    SEND_SMS
}

enum class RepeatMode {
    ONCE,
    DAILY,
    WEEKLY,
    MONTHLY
}

enum class TemplateCategory {
    ACTIVATION,
    PURCHASE,
    NOTIFICATION,
    CUSTOM
}

fun UserEntity.toModel() = AdminUser(
    id = id,
    phone = phone,
    name = name,
    category = category,
    notes = notes,
    createdAt = createdAt
)

fun AdminUser.toEntity() = UserEntity(
    id = id,
    phone = phone,
    name = name,
    category = category,
    notes = notes,
    createdAt = createdAt
)

fun PurchaseEntity.toModel() = PurchaseSms(
    id = id,
    phone = phone,
    amount = amount,
    balance = balance,
    expirationDate = expirationDate,
    rawMessage = rawMessage,
    timestamp = timestamp
)

fun PurchaseSms.toEntity() = PurchaseEntity(
    id = id,
    phone = phone,
    amount = amount,
    balance = balance,
    expirationDate = expirationDate,
    rawMessage = rawMessage,
    timestamp = timestamp
)

fun TokenEntity.toModel() = TokenTransaction(
    id = id,
    userId = userId,
    type = TokenType.valueOf(type),
    amount = amount,
    code = code,
    message = message,
    status = TransactionStatus.valueOf(status),
    createdAt = createdAt
)

fun TokenTransaction.toEntity() = TokenEntity(
    id = id,
    userId = userId,
    type = type.name,
    amount = amount,
    code = code,
    message = message,
    status = status.name,
    createdAt = createdAt
)

fun ScheduleEntity.toModel() = ScheduledTask(
    id = id,
    userId = userId,
    action = ScheduledAction.valueOf(action),
    scheduledAt = scheduledAt,
    repeat = RepeatMode.valueOf(repeat),
    code = code,
    message = message,
    enabled = enabled,
    createdAt = createdAt
)

fun ScheduledTask.toEntity() = ScheduleEntity(
    id = id,
    userId = userId,
    action = action.name,
    scheduledAt = scheduledAt,
    repeat = repeat.name,
    code = code,
    message = message,
    enabled = enabled,
    createdAt = createdAt
)

fun TemplateEntity.toModel() = SmsTemplate(
    id = id,
    name = name,
    body = body,
    category = TemplateCategory.valueOf(category),
    createdAt = createdAt
)

fun SmsTemplate.toEntity() = TemplateEntity(
    id = id,
    name = name,
    body = body,
    category = category.name,
    createdAt = createdAt
)
