package com.webdownloader.reader

import android.content.Context
import android.graphics.BitmapFactory
import com.webdownloader.data.db.Chapter
import com.webdownloader.data.storage.Cbz
import com.webdownloader.data.storage.LibraryStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Unpacks chapter CBZs into cache for display, keeping only the most recent few. */
class ReaderCache(context: Context, private val storage: LibraryStorage) {
    data class Page(val file: File, val width: Int, val height: Int)

    private val root = File(context.cacheDir, "reader")

    suspend fun open(chapter: Chapter): List<Page> = withContext(Dispatchers.IO) {
        val uri = chapter.fileUri ?: return@withContext emptyList()
        val dir = File(root, "${chapter.id}_${uri.hashCode()}_${chapter.sizeBytes ?: 0}")
        val marker = File(dir, ".complete")
        if (!marker.exists()) {
            dir.deleteRecursively()
            storage.openInput(uri).use { Cbz.extract(it, dir) }
            marker.createNewFile()
        }
        dir.setLastModified(System.currentTimeMillis())
        prune(keep = 4)
        Cbz.pagesIn(dir).map { file ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            Page(file, o.outWidth.coerceAtLeast(1), o.outHeight.coerceAtLeast(1))
        }
    }

    private fun prune(keep: Int) {
        root.listFiles()?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.deleteRecursively() }
    }

    fun clear() {
        root.deleteRecursively()
    }
}
