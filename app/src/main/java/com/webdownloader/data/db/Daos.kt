package com.webdownloader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """SELECT b.*,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id) AS total,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.status = 'DOWNLOADED') AS downloaded,
            (SELECT COUNT(*) FROM chapters c WHERE c.bookId = b.id AND c.status = 'DOWNLOADED' AND c.isRead = 0) AS unread
           FROM books b"""
    )
    fun observeLibrary(): Flow<List<BookWithCounts>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun observe(id: Long): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): Book?

    @Query("SELECT * FROM books WHERE sourceUrl = :url")
    suspend fun findByUrl(url: String): Book?

    @Query("SELECT COUNT(*) FROM books WHERE folderName = :name")
    suspend fun countFolder(name: String): Int

    @Query("UPDATE books SET lastReadAt = :time WHERE id = :id")
    suspend fun touchRead(id: Long, time: Long)

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY sortIndex")
    fun observeForBook(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY sortIndex")
    suspend fun forBook(bookId: Long): List<Chapter>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun get(id: Long): Chapter?

    @Query("SELECT * FROM chapters WHERE id = :id")
    fun observe(id: Long): Flow<Chapter?>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND sortIndex > :sortIndex ORDER BY sortIndex LIMIT 1")
    suspend fun next(bookId: Long, sortIndex: Int): Chapter?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND sortIndex < :sortIndex ORDER BY sortIndex DESC LIMIT 1")
    suspend fun previous(bookId: Long, sortIndex: Int): Chapter?

    @Query("SELECT * FROM chapters WHERE status = 'QUEUED' ORDER BY queuedAt, sortIndex LIMIT 1")
    suspend fun nextQueued(): Chapter?

    @Query("SELECT COUNT(*) FROM chapters WHERE status IN ('QUEUED', 'DOWNLOADING')")
    suspend fun countPending(): Int

    @Query(
        """SELECT c.*, b.title AS bookTitle FROM chapters c JOIN books b ON b.id = c.bookId
           WHERE c.status IN ('QUEUED', 'DOWNLOADING', 'FAILED')
           ORDER BY CASE c.status WHEN 'DOWNLOADING' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, c.queuedAt, c.sortIndex"""
    )
    fun observeQueue(): Flow<List<QueueItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<Chapter>): List<Long>

    @Update
    suspend fun update(chapter: Chapter)

    @Update
    suspend fun updateAll(chapters: List<Chapter>)

    @Query(
        """UPDATE chapters SET status = 'QUEUED', queuedAt = :now, error = NULL, requestedQuality = :quality
           WHERE id IN (:ids) AND status IN ('NOT_DOWNLOADED', 'FAILED')"""
    )
    suspend fun queue(ids: List<Long>, now: Long, quality: String?)

    @Query("UPDATE chapters SET status = 'QUEUED', queuedAt = :now, error = NULL WHERE status = 'FAILED'")
    suspend fun retryFailed(now: Long)

    @Query("UPDATE chapters SET status = 'DOWNLOADING', error = NULL, progressDone = 0, progressTotal = 0 WHERE id = :id")
    suspend fun markDownloading(id: Long)

    @Query("UPDATE chapters SET progressDone = :done, progressTotal = :total WHERE id = :id")
    suspend fun progress(id: Long, done: Int, total: Int)

    @Query("UPDATE chapters SET status = 'FAILED', error = :error WHERE id = :id")
    suspend fun fail(id: Long, error: String?)

    @Query("UPDATE chapters SET status = 'QUEUED' WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun requeueIfDownloading(id: Long)

    @Query("UPDATE chapters SET status = 'QUEUED' WHERE status = 'DOWNLOADING'")
    suspend fun resetInterrupted()

    @Query("UPDATE chapters SET status = 'NOT_DOWNLOADED', queuedAt = NULL, error = NULL WHERE status IN ('QUEUED', 'DOWNLOADING', 'FAILED')")
    suspend fun clearQueue()

    @Query("UPDATE chapters SET status = 'NOT_DOWNLOADED', queuedAt = NULL, error = NULL WHERE id = :id AND status != 'DOWNLOADED'")
    suspend fun cancel(id: Long)

    @Query("UPDATE chapters SET lastPageRead = :page WHERE id = :id")
    suspend fun saveProgress(id: Long, page: Int)

    @Query("UPDATE chapters SET isRead = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)
}
