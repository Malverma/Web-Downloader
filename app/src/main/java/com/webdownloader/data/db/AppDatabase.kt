package com.webdownloader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Book::class, Chapter::class, ReadSeries::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readDao(): ReadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN openedAt INTEGER")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `read_series` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT, " +
                        "`siteId` TEXT NOT NULL, `siteName` TEXT NOT NULL, `chapters` INTEGER, `rating` REAL, " +
                        "`markedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))"
                )
            }
        }
    }
}
