package com.webdownloader.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class Quality(val label: String, val maxWidth: Int, val webpQuality: Int) {
    ORIGINAL("Original", 0, 100),
    HIGH("High", 1600, 90),
    MEDIUM("Medium", 1200, 80),
    LOW("Low", 900, 65),
    CUSTOM("Custom", 0, 0),
}

enum class ChapterStatus { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

@Entity(tableName = "books", indices = [Index(value = ["sourceUrl"], unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val sourceUrl: String,
    val sourceHost: String,
    val coverUrl: String? = null,
    val coverPath: String? = null,
    val qualityOverride: Quality? = null,
    val folderName: String,
    val addedAt: Long,
    val updatedAt: Long,
    val lastReadAt: Long = 0,
    val lastCheckedAt: Long? = null,
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("bookId"), Index(value = ["bookId", "url"], unique = true)],
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val url: String,
    val number: Double?,
    val label: String,
    val sortIndex: Int,
    val status: ChapterStatus = ChapterStatus.NOT_DOWNLOADED,
    val pageCount: Int? = null,
    val fileUri: String? = null,
    val sizeBytes: Long? = null,
    val quality: Quality? = null,
    /** Quality chosen for a pending download; overrides the book/global default. */
    val requestedQuality: String? = null,
    val lastPageRead: Int = 0,
    val isRead: Boolean = false,
    val queuedAt: Long? = null,
    val error: String? = null,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val removedFromSource: Boolean = false,
    /** When the chapter was last opened in the reader; opened chapters are shown darker. */
    val openedAt: Long? = null,
)

data class BookWithCounts(
    @Embedded val book: Book,
    val total: Int,
    val downloaded: Int,
    val unread: Int,
    val active: Int,
)

data class QueueItem(
    @Embedded val chapter: Chapter,
    val bookTitle: String,
)
