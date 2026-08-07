package com.bingwa.adminhub.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bingwa.adminhub.data.local.entity.TokenEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TokenDao {
    @Query("SELECT * FROM token_transactions ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TokenEntity>>

    @Query("SELECT * FROM token_transactions WHERE userId = :userId ORDER BY createdAt DESC")
    fun getByUser(userId: String): Flow<List<TokenEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TokenEntity)

    @Update
    suspend fun update(entity: TokenEntity)

    @Delete
    suspend fun delete(entity: TokenEntity)

    @Query("DELETE FROM token_transactions")
    suspend fun clearAll()
}
