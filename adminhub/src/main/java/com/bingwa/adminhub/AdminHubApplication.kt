package com.bingwa.adminhub

import android.app.Application
import androidx.room.Room
import com.bingwa.adminhub.data.local.AdminHubDatabase
import com.bingwa.adminhub.ui.theme.AdminHubTheme

class AdminHubApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, AdminHubDatabase::class.java, AdminHubDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
    }
}
