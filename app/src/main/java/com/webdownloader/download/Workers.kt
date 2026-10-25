package com.webdownloader.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.webdownloader.WebDownloaderApp
import com.webdownloader.data.db.ChapterDao
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.Quality
import com.webdownloader.data.prefs.SettingsRepository
import com.webdownloader.data.storage.Cbz
import com.webdownloader.extraction.ChallengeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.webdownloader.data.db.Chapter
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

private fun foregroundInfo(id: Int, notification: android.app.Notification): ForegroundInfo =
    if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else ForegroundInfo(id, notification)

/** Works through queued chapters one at a time until the queue is empty. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val c = (ctx as WebDownloaderApp).container
        Notifications.ensureChannels(ctx)
        runCatching {
            setForeground(foregroundInfo(Notifications.ID_DOWNLOAD, Notifications.progress(ctx, "Downloads", "Preparing…", 0, 0)))
        }
        c.chapterDao.resetInterrupted()
        val claimLock = Mutex()
        val active = AtomicInteger(0)
        // Several chapters download at once; the lock stops two loops claiming the same one.
        suspend fun claim(): Chapter? = claimLock.withLock {
            if (c.settings.current().downloadsPaused) return null
            c.chapterDao.nextQueued()?.also { c.chapterDao.markDownloading(it.id) }
        }
        val lanes = c.settings.current().chapterConcurrency.coerceIn(1, 4)
        coroutineScope {
            repeat(lanes) {
                launch {
                    while (true) {
                        val chapter = claim() ?: break
                        val book = c.bookDao.get(chapter.bookId)
                        val title = listOfNotNull(book?.title, chapter.label).joinToString(" · ")
                        active.incrementAndGet()
                        try {
                            c.chapterDownloader.download(chapter.id) { done, total ->
                                val others = active.get() - 1
                                val text = "$done / $total pages" + if (others > 0) " · +$others more" else ""
                                Notifications.notify(ctx, Notifications.ID_DOWNLOAD, Notifications.progress(ctx, title, text, done, total))
                            }
                        } catch (e: CancellationException) {
                            withContext(NonCancellable) { c.chapterDao.requeueIfDownloading(chapter.id) }
                            throw e
                        } catch (e: ChallengeException) {
                            c.chapterDao.requeueIfDownloading(chapter.id)
                            c.settings.update { it.copy(downloadsPaused = true, pauseReason = e.message) }
                            Notifications.alert(ctx, "Downloads paused", e.message.orEmpty())
                        } catch (e: Exception) {
                            c.chapterDao.fail(chapter.id, e.message ?: e.javaClass.simpleName)
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }
        }
        return Result.success()
    }
}

/** Re-encodes a book's downloaded chapters at a new quality. */
class RecompressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val c = (ctx as WebDownloaderApp).container
        val bookId = inputData.getLong(KEY_BOOK, -1)
        val quality = runCatching { Quality.valueOf(inputData.getString(KEY_QUALITY)!!) }.getOrNull() ?: return Result.failure()
        val book = c.bookDao.get(bookId) ?: return Result.failure()
        val spec = quality.spec(c.settings.current())
        Notifications.ensureChannels(ctx)
        val chapters = c.chapterDao.forBook(bookId).filter { it.status == ChapterStatus.DOWNLOADED && it.fileUri != null }
        chapters.forEachIndexed { index, chapter ->
            val n = Notifications.progress(ctx, "Re-compressing ${book.title}", chapter.label, index, chapters.size)
            runCatching { setForeground(foregroundInfo(Notifications.ID_RECOMPRESS, n)) }
            val work = File(ctx.cacheDir, "recompress/${chapter.id}")
            try {
                work.deleteRecursively()
                val input = File(work, "in")
                val output = File(work, "out").apply { mkdirs() }
                val pages = c.storage.openInput(chapter.fileUri!!).use { Cbz.extract(it, input) }
                pages.forEach { page ->
                    val processed = ImageProcessor.process(page.readBytes(), spec)
                    File(output, "${page.nameWithoutExtension}.${processed.ext}").writeBytes(processed.bytes)
                }
                val cbz = File(work, "out.cbz")
                Cbz.write(cbz, Cbz.pagesIn(output), Cbz.comicInfo(book.title, chapter.number, chapter.label, pages.size, chapter.url))
                val name = c.library.chapterFileName(chapter, chapters)
                val uri = c.storage.saveFile(book.folderName, name, cbz).toString()
                if (uri != chapter.fileUri) c.storage.delete(chapter.fileUri)
                c.chapterDao.update(chapter.copy(fileUri = uri, sizeBytes = cbz.length(), quality = quality))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Leave this chapter as it was and continue with the rest.
            } finally {
                work.deleteRecursively()
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_BOOK = "bookId"
        const val KEY_QUALITY = "quality"
    }
}

class DownloadScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val chapterDao: ChapterDao,
) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun enqueue() {
        val s = settings.current()
        if (s.downloadsPaused) return
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniqueWork(WORK_DOWNLOADS, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun pause() {
        settings.update { it.copy(downloadsPaused = true, pauseReason = null) }
        workManager.cancelUniqueWork(WORK_DOWNLOADS)
    }

    suspend fun resume() {
        settings.update { it.copy(downloadsPaused = false, pauseReason = null) }
        enqueue()
    }

    suspend fun restart() {
        workManager.cancelUniqueWork(WORK_DOWNLOADS)
        enqueue()
    }

    fun stop() {
        workManager.cancelUniqueWork(WORK_DOWNLOADS)
    }

    suspend fun resumeIfPending() {
        if (chapterDao.countPending() > 0) enqueue()
    }

    fun recompress(bookId: Long, quality: Quality) {
        val request = OneTimeWorkRequestBuilder<RecompressWorker>()
            .setInputData(workDataOf(RecompressWorker.KEY_BOOK to bookId, RecompressWorker.KEY_QUALITY to quality.name))
            .build()
        workManager.enqueueUniqueWork("recompress-$bookId", ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val WORK_DOWNLOADS = "downloads"
    }
}
