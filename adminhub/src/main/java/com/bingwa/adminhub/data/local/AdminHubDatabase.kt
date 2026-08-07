package com.bingwa.adminhub.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.bingwa.adminhub.data.local.dao.PurchaseDao
import com.bingwa.adminhub.data.local.dao.ScheduleDao
import com.bingwa.adminhub.data.local.dao.TemplateDao
import com.bingwa.adminhub.data.local.dao.TokenDao
import com.bingwa.adminhub.data.local.dao.UserDao
import com.bingwa.adminhub.data.local.entity.PurchaseEntity
import com.bingwa.adminhub.data.local.entity.ScheduleEntity
import com.bingwa.adminhub.data.local.entity.TemplateEntity
import com.bingwa.adminhub.data.local.entity.TokenEntity
import com.bingwa.adminhub.data.local.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        PurchaseEntity::class,
        TokenEntity::class,
        ScheduleEntity::class,
        TemplateEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AdminHubDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun tokenDao(): TokenDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun templateDao(): TemplateDao

    companion object {
        const val DATABASE_NAME = "adminhub.db"
    }
}
