package com.patentia.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlateSighting::class],
    version = 3,
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
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupSource TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupOwnerName TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupOwnerRut TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupVehicleMake TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupVehicleModel TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupVehicleYear TEXT")
                database.execSQL("ALTER TABLE plate_sightings ADD COLUMN lookupVehicleColor TEXT")
            }
        }
    }
}