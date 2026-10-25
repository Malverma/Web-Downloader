package com.webdownloader.data.storage

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Chapter archives: zero-padded page images stored uncompressed, plus ComicInfo.xml. */
object Cbz {
    private val imageExtensions = setOf("webp", "jpg", "jpeg", "png", "gif", "avif")

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExtensions

    fun write(out: File, pages: List<File>, comicInfo: String?) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            val buf = ByteArray(64 * 1024)
            for (page in pages) {
                val crc = CRC32()
                page.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                    }
                }
                val entry = ZipEntry(page.name).apply {
                    method = ZipEntry.STORED
                    size = page.length()
                    compressedSize = page.length()
                    this.crc = crc.value
                }
                zos.putNextEntry(entry)
                page.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            if (comicInfo != null) {
                zos.putNextEntry(ZipEntry("ComicInfo.xml"))
                zos.write(comicInfo.toByteArray())
                zos.closeEntry()
            }
        }
    }

    /** Extracts page images into [dest] and returns them in reading order. */
    fun extract(input: InputStream, dest: File): List<File> {
        dest.mkdirs()
        ZipInputStream(BufferedInputStream(input)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = File(entry.name).name
                if (entry.isDirectory || !isImage(name)) continue
                File(dest, name).outputStream().use { zis.copyTo(it) }
            }
        }
        return pagesIn(dest)
    }

    fun pagesIn(dir: File): List<File> =
        dir.listFiles()?.filter { isImage(it.name) }?.sortedBy { it.name } ?: emptyList()

    fun comicInfo(series: String, number: Double?, title: String, pageCount: Int, url: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val num = number?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo>\n")
            append("  <Series>${esc(series)}</Series>\n")
            if (num != null) append("  <Number>$num</Number>\n")
            append("  <Title>${esc(title)}</Title>\n")
            append("  <PageCount>$pageCount</PageCount>\n")
            append("  <Web>${esc(url)}</Web>\n")
            append("  <Manga>YesAndRightToLeft</Manga>\n")
            append("</ComicInfo>\n")
        }
    }
}
