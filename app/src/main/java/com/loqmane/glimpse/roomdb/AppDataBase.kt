package com.loqmane.glimpse.roomdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [FaceEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class) // Register your type converters here
abstract class FaceDatabase : RoomDatabase() {

    abstract fun faceDao(): FaceDao

    companion object {
        @Volatile
        private var INSTANCE: FaceDatabase? = null

        fun getDatabase(context: Context): FaceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FaceDatabase::class.java,
                    "face_database"
                )
                    // Wipes and rebuilds instead of migrating if no Migration object.
                    // In production, you'd implement a proper migration strategy.
                    .fallbackToDestructiveMigration(false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}