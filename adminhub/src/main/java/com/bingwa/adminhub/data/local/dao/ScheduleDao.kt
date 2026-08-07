package com.bingwa.adminhub.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bingwa.adminhub.data.local.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY scheduledAt ASC")
    fun getAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 ORDER BY scheduledAt ASC")
    fun getEnabled(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId")
    suspend fun getById(taskId: String): ScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScheduleEntity)

    @Update
    suspend fun update(entity: ScheduleEntity)

    @Delete
    suspend fun delete(entity: ScheduleEntity)

    @Query("DELETE FROM scheduled_tasks")
    suspend fun clearAll()
}
