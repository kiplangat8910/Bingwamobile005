package com.bingwa.adminhub.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "purchases")
data class PurchaseEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val amount: Double,
    val balance: Double,
    val expirationDate: String,
    val rawMessage: String,
    val timestamp: Long = System.currentTimeMillis()
)
