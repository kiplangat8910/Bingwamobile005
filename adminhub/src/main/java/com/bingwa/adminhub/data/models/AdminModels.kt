package com.bingwa.adminhub.data.models

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
