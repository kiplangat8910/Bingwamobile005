package com.bingwa.mobile

import android.content.Context
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase

const val SCRATCH_CARD_STATUS_PENDING = "Pending"
const val SCRATCH_CARD_STATUS_PROCESSING = "Processing"
const val SCRATCH_CARD_STATUS_SUCCESS = "Success"
const val SCRATCH_CARD_STATUS_FAILED = "Failed"
const val SCRATCH_CARD_MAX_HISTORY = 50

@Entity(
    tableName = "scratch_card_history",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
data class ScratchCardHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val code: String,
    val simSelection: Int,
    val status: String = SCRATCH_CARD_STATUS_PENDING,
    val retryCount: Int = 0,
    val lastResponse: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "scratch_card_queue",
    foreignKeys = [
        ForeignKey(
            entity = ScratchCardHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["historyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["historyId"], unique = true),
        Index(value = ["queueOrder"]),
        Index(value = ["status"])
    ]
)
data class ScratchCardQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val historyId: Long,
    val code: String,
    val simSelection: Int,
    val queueOrder: Long,
    val status: String = SCRATCH_CARD_STATUS_PENDING,
    val retryCount: Int = 0,
    val lastResponse: String = "",
    val createdAt: Long,
    val updatedAt: Long
)

data class ScratchCardHistoryWithQueue(
    @Embedded val history: ScratchCardHistoryEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "historyId"
    )
    val queueItems: List<ScratchCardQueueEntity>
)

@Database(
    entities = [ScratchCardHistoryEntity::class, ScratchCardQueueEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ScratchCardDatabase : RoomDatabase() {

    abstract fun scratchCardDao(): ScratchCardDao

    companion object {
        @Volatile
        private var instance: ScratchCardDatabase? = null

        fun getInstance(context: Context): ScratchCardDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ScratchCardDatabase::class.java,
                    "scratch_card_database"
                ).build().also { instance = it }
            }
        }
    }
}
