package com.lumipage.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        PageEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class LumiPageDatabase : RoomDatabase() {
    abstract fun dao(): LumiPageDao

    companion object {
        @Volatile
        private var instance: LumiPageDatabase? = null

        fun getInstance(context: Context): LumiPageDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LumiPageDatabase::class.java,
                    "lumipage.db",
                ).build().also { instance = it }
            }
        }
    }
}
