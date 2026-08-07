package com.bingwa.adminhub.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val body: String,
    val category: String,
    val createdAt: Long = System.currentTimeMillis()
)
