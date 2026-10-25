package com.webdownloader.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.webdownloader.AppContainer
import com.webdownloader.data.db.Book
import com.webdownloader.data.db.Chapter
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.Quality
import com.webdownloader.extraction.ExtractResult
import com.webdownloader.extraction.ExtractedChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

class LibraryRepository(private val c: AppContainer) {

    data class UpsertResult(val bookId: Long, val newChapters: Int, val created: Boolean)

    @Serializable
    private data class BookJson(val title: String, val sourceUrl: String, val chapters: List<ChapterJson>)

    @Serializable
    private data class ChapterJson(val label: String, val number: Double?, val url: String, val downloaded: Boolean, val read: Boolean)

    fun canonical(url: String): String = url.substringBefore('#').trimEnd('/')

    fun cleanTitle(raw: String?, fallbackUrl: String): String {
        var t = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        // Drop a trailing " - Site Name" / " | Site Name".
        Regex("^(.{2,}?)\\s+[|–—-]\\s+[^|–—-]{1,40}$").find(t)?.let { t = it.groupValues[1].trim() }
        if (t.isEmpty()) t = Uri.parse(fallbackUrl).lastPathSegment ?: "Untitled"
        return t
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|{}\\[\\]]"), "")
            .replace(Regex("\\s+"), " ")
            .trim().trimEnd('.')
            .take(100)
            .ifEmpty { "Untitled" }

    fun formatNumber(n: Double): String = if (n % 1.0 == 0.0) n.toLong().toString() else n.toString()

    fun labelFor(ch: ExtractedChapter): String {
        val label = ch.label.trim()
        if (label.isNotEmpty() && label.any { it.isDigit() }) return label
        ch.number?.let { return "Chapter ${formatNumber(it)}" }
        return label.ifEmpty { ch.url.substringAfterLast('/') }
    }

    /** Ascending reading order: by chapter number, falling back to reversed page order. */
    fun sortChapters(list: List<ExtractedChapter>): List<ExtractedChapter> {
        val unique = list.map { it.copy(url = canonical(it.url)) }.distinctBy { it.url }
        if (unique.none { it.number != null }) return unique.reversed()
        return unique.withIndex()
            .sortedWith(compareBy({ it.value.number == null }, { it.value.number ?: 0.0 }, { -it.index }))
            .map { it.value }
    }

    private suspend fun uniqueFolder(base: String): String {
        var name = base
        var i = 2
        while (c.bookDao.countFolder(name) > 0) name = "$base (${i++})"
        return name
    }

    suspend fun upsertSeries(seriesUrl: String, result: ExtractResult): UpsertResult = withContext(Dispatchers.IO) {
        val url = canonical(seriesUrl)
        val now = System.currentTimeMillis()
        val fresh = sortChapters(result.chapters)
        val freshUrls = fresh.map { it.url }.toSet()

        var created = false
        var newCount = 0
        val book = c.db.withTransaction {
            val existing = c.bookDao.findByUrl(url)
            val book = existing ?: run {
                created = true
                val title = cleanTitle(result.title, url)
                val b = Book(
                    title = title,
                    sourceUrl = url,
                    sourceHost = Uri.parse(url).host.orEmpty(),
                    coverUrl = result.cover,
                    folderName = uniqueFolder(sanitize(title)),
                    addedAt = now,
                    updatedAt = now,
                )
                b.copy(id = c.bookDao.insert(b))
            }
            val old = c.chapterDao.forBook(book.id).associateBy { it.url }

            data class Row(val url: String, val number: Double?, val label: String, val existing: Chapter?)
            val rows = fresh.map { Row(it.url, it.number, labelFor(it), old[it.url]) } +
                old.values.filter { it.url !in freshUrls }.map { Row(it.url, it.number, it.label, it) }
            val ordered = rows.withIndex()
                .sortedWith(compareBy({ it.value.number == null }, { it.value.number ?: 0.0 }, { it.index }))
                .map { it.value }

            val inserts = mutableListOf<Chapter>()
            val updates = mutableListOf<Chapter>()
            ordered.forEachIndexed { i, r ->
                if (r.existing == null) {
                    newCount++
                    inserts += Chapter(bookId = book.id, url = r.url, number = r.number, label = r.label, sortIndex = i)
                } else {
                    updates += r.existing.copy(sortIndex = i, label = r.label, number = r.number, removedFromSource = r.url !in freshUrls)
                }
            }
            c.chapterDao.updateAll(updates)
            c.chapterDao.insertAll(inserts)
            val updated = book.copy(updatedAt = now, lastCheckedAt = now, coverUrl = book.coverUrl ?: result.cover)
            c.bookDao.update(updated)
            updated
        }
        if (book.coverPath == null && book.coverUrl != null) fetchCover(book, result.url)
        runCatching { writeBookJson(book.id) }
        UpsertResult(book.id, if (created) 0 else newCount, created)
    }

    private suspend fun fetchCover(book: Book, referer: String) {
        runCatching {
            val bytes = c.http.get(book.coverUrl!!, referer)
            val dir = File(c.app.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${book.id}.img")
            file.writeBytes(bytes)
            c.bookDao.get(book.id)?.let { c.bookDao.update(it.copy(coverPath = file.absolutePath)) }
        }
    }

    /** File name such as "Chapter 007.cbz" or "Chapter 120 - S3 END.cbz". */
    fun chapterFileName(chapter: Chapter, all: List<Chapter>): String {
        val maxNumber = all.mapNotNull { it.number }.maxOrNull() ?: 0.0
        val pad = if (maxNumber >= 1000) 4 else 3
        val extra = chapter.label
            .replace(Regex("(?i)(chapter|chap|ch\\.?|episode|ep\\.?)\\s*\\d+(\\.\\d+)?"), "")
            .replace(Regex("[{}\\[\\]()]"), " ")
            .trim(' ', '-', ':', '–', '|', '.')
            .replace(Regex("\\s+"), " ")
        val base = chapter.number?.let {
            val whole = it.toLong().toString().padStart(pad, '0')
            "Chapter " + if (it % 1.0 == 0.0) whole else whole + "." + formatNumber(it).substringAfter('.')
        } ?: "Chapter %0${pad}d".format(chapter.sortIndex + 1)
        val name = if (extra.isNotEmpty() && extra.any { it.isLetterOrDigit() }) "$base - $extra" else base
        return sanitize(name) + ".cbz"
    }

    suspend fun writeBookJson(bookId: Long) = withContext(Dispatchers.IO) {
        val book = c.bookDao.get(bookId) ?: return@withContext
        val chapters = c.chapterDao.forBook(bookId)
        val data = BookJson(
            book.title, book.sourceUrl,
            chapters.map { ChapterJson(it.label, it.number, it.url, it.status == ChapterStatus.DOWNLOADED, it.isRead) },
        )
        val tmp = File(c.app.cacheDir, "book-$bookId.json")
        tmp.writeText(c.json.encodeToString(BookJson.serializer(), data))
        c.storage.saveFile(book.folderName, "book.json", tmp)
        tmp.delete()
    }

    suspend fun queue(ids: List<Long>, quality: Quality? = null) {
        if (ids.isEmpty()) return
        c.chapterDao.queue(ids, System.currentTimeMillis(), quality?.name)
        c.downloads.enqueue()
    }

    suspend fun cancel(chapter: Chapter) {
        c.chapterDao.cancel(chapter.id)
        if (chapter.status == ChapterStatus.DOWNLOADING) c.downloads.restart()
    }

    suspend fun deleteDownload(chapter: Chapter) {
        chapter.fileUri?.let { c.storage.delete(it) }
        c.chapterDao.update(
            chapter.copy(status = ChapterStatus.NOT_DOWNLOADED, fileUri = null, sizeBytes = null, pageCount = null, quality = null),
        )
    }

    suspend fun deleteBook(book: Book, deleteFiles: Boolean) {
        val chapters = c.chapterDao.forBook(book.id)
        if (chapters.any { it.status == ChapterStatus.DOWNLOADING }) c.downloads.restart()
        if (deleteFiles) {
            chapters.mapNotNull { it.fileUri }.forEach { c.storage.delete(it) }
            runCatching { c.storage.deleteBookFolder(book.folderName) }
        }
        book.coverPath?.let { File(it).delete() }
        c.bookDao.delete(book)
    }
}
