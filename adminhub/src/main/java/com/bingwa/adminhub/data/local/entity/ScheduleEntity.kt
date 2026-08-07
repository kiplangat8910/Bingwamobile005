package com.bingwa.adminhub.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val action: String,
    val scheduledAt: Long,
    val repeat: String = "ONCE",
    val code: String = "",
    val message: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
