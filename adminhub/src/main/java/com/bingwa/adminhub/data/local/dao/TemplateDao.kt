package com.bingwa.adminhub.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bingwa.adminhub.data.local.entity.TemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Query("SELECT * FROM sms_templates ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM sms_templates WHERE category = :category ORDER BY createdAt DESC")
    fun getByCategory(category: String): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM sms_templates WHERE id = :templateId")
    suspend fun getById(templateId: String): TemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TemplateEntity)

    @Update
    suspend fun update(entity: TemplateEntity)

    @Delete
    suspend fun delete(entity: TemplateEntity)

    @Query("DELETE FROM sms_templates")
    suspend fun clearAll()
}
