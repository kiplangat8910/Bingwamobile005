package com.bingwa.adminhub.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val phone: String,
    val name: String,
    val category: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
