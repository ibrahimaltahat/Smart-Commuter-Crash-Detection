package com.smartcommuter.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.smartcommuter.data.local.dao.SafetyDao
import com.smartcommuter.data.local.entity.*

@Database(
    entities = [
        TrustedContactEntity::class,
        TripSessionEntity::class,
        LocationBreadcrumbEntity::class,
        IncidentEventEntity::class,
        AudioBlackboxEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SmartCommuterDatabase : RoomDatabase() {

    abstract fun safetyDao(): SafetyDao

    companion object {
        @Volatile
        private var INSTANCE: SmartCommuterDatabase? = null

        fun getInstance(context: Context): SmartCommuterDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    SmartCommuterDatabase::class.java,
                    "smart_commuter_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}