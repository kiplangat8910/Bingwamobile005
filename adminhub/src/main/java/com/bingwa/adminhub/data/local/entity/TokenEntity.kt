package com.bingwa.adminhub.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "token_transactions")
data class TokenEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val type: String,
    val amount: Double = 0.0,
    val code: String = "",
    val message: String = "",
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)
