package com.patentia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlateSighting::class],
    version = 2,
    exportSchema = false,
)
abstract class PatentIADatabase : RoomDatabase() {

    abstract fun plateSightingDao(): PlateSightingDao

    companion object {
        @Volatile
        private var instance: PatentIADatabase? = null

        fun getInstance(context: Context): PatentIADatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PatentIADatabase::class.java,
                    "patentia.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}