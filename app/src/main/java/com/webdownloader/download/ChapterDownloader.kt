package com.webdownloader.download

import com.webdownloader.AppContainer
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.Quality
import com.webdownloader.data.storage.Cbz
import com.webdownloader.extraction.ChallengeException
import com.webdownloader.extraction.SiteRule
import com.webdownloader.extraction.WebViewExtractor
import com.webdownloader.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private val IMAGE_URL = Regex("""https?://[^"'\s<>\\)]+?\.(?:webp|jpe?g|png|avif|gif)(?:\?[^"'\s<>\\)]*)?""", RegexOption.IGNORE_CASE)

class ChapterDownloader(private val c: AppContainer) {

    suspend fun download(chapterId: Long, onProgress: (done: Int, total: Int) -> Unit) {
        val chapter = c.chapterDao.get(chapterId) ?: return
        val book = c.bookDao.get(chapter.bookId) ?: return
        val settings = c.settings.current()
        val quality = chapter.requestedQuality?.let { runCatching { Quality.valueOf(it) }.getOrNull() }
            ?: book.qualityOverride ?: settings.defaultQuality
        val spec = quality.spec(settings)

        c.chapterDao.markDownloading(chapter.id)
        val rule = c.siteRules.forUrl(chapter.url)
        val images = fastImages(chapter.url, rule).ifEmpty {
            val result = c.extractor.extract(chapter.url, WebViewExtractor.Mode.CHAPTER)
            if (result.challenge) throw ChallengeException(book.sourceHost)
            result.images.map { it.url }.distinct()
        }
        if (images.isEmpty()) {
            throw IOException("No page images found. The site layout may have changed; check its rule in Settings → Sites.")
        }

        val delayMs = (rule?.requestDelayMs ?: settings.requestDelayMs).toLong()
        val work = File(c.app.cacheDir, "dl/${chapter.id}").apply { mkdirs() }
        val pad = if (images.size >= 1000) 4 else 3
        val done = AtomicInteger(0)
        val semaphore = Semaphore(settings.imageConcurrency.coerceIn(1, 8))
        c.chapterDao.progress(chapter.id, 0, images.size)
        onProgress(0, images.size)

        fun pageFile(index: Int): File? {
            val base = (index + 1).toString().padStart(pad, '0')
            return work.listFiles()?.firstOrNull { it.nameWithoutExtension == base && Cbz.isImage(it.name) }
        }

        coroutineScope {
            images.mapIndexed { i, imageUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        // Pages already fetched by an interrupted attempt are reused.
                        if (pageFile(i) == null) {
                            val processed = fetchWithRetry(imageUrl, chapter.url, spec)
                            val base = (i + 1).toString().padStart(pad, '0')
                            val part = File(work, "$base.part")
                            part.writeBytes(processed.bytes)
                            part.renameTo(File(work, "$base.${processed.ext}"))
                            if (delayMs > 0) delay(delayMs)
                        }
                        val d = done.incrementAndGet()
                        c.chapterDao.progress(chapter.id, d, images.size)
                        onProgress(d, images.size)
                    }
                }
            }.awaitAll()
        }

        val pages = images.indices.map { pageFile(it) ?: throw IOException("Missing page ${it + 1}") }
        val all = c.chapterDao.forBook(book.id)
        val fileName = c.library.chapterFileName(chapter, all)
        val cbz = File(c.app.cacheDir, "dl/${chapter.id}.cbz")
        Cbz.write(cbz, pages, Cbz.comicInfo(book.title, chapter.number, chapter.label, pages.size, chapter.url))
        val uri = c.storage.saveFile(book.folderName, fileName, cbz).toString()

        val fresh = c.chapterDao.get(chapter.id) ?: chapter
        if (fresh.fileUri != null && fresh.fileUri != uri) c.storage.delete(fresh.fileUri)
        c.chapterDao.update(
            fresh.copy(
                status = ChapterStatus.DOWNLOADED,
                fileUri = uri,
                pageCount = pages.size,
                sizeBytes = cbz.length(),
                quality = quality,
                requestedQuality = null,
                error = null,
                progressDone = pages.size,
                progressTotal = pages.size,
            ),
        )
        cbz.delete()
        work.deleteRecursively()
        runCatching { c.library.writeBookJson(book.id) }
    }

    /**
     * Fast path: read page image URLs straight from the chapter HTML (they're embedded in
     * the markup or page data on most readers), skipping the hidden WebView entirely.
     * Only used for sites whose rule says which URLs are page images.
     */
    private suspend fun fastImages(url: String, rule: SiteRule?): List<String> {
        val filter = rule?.imageFilter ?: return emptyList()
        val contains = filter.urlContains ?: return emptyList()
        val html = try {
            c.http.getText(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val text = Parser.unescapeEntities(html, false)
            .replace("\\u002F", "/").replace("\\u002f", "/").replace("\\/", "/")
        val exclude = filter.urlExcludes?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        return IMAGE_URL.findAll(text)
            .map { it.value }
            .filter { contains in it && exclude?.containsMatchIn(it) != true }
            .distinct()
            .toList()
    }

    private suspend fun fetchWithRetry(url: String, referer: String, spec: QualitySpec): ImageProcessor.Processed {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val bytes = c.http.get(url, referer)
                return ImageProcessor.process(bytes, spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code == 404) throw IOException("Image not found (404): $url")
                last = e
            } catch (e: Exception) {
                last = e
            }
            delay(1000L shl attempt)
        }
        throw last ?: IOException("Download failed")
    }
}
