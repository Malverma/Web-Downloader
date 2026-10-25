package com.webdownloader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Book::class, Chapter::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
}
