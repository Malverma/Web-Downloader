package com.webdownloader.data.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.webdownloader.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Library files live in the folder the user picked (Storage Access Framework),
 * or in the app's external files dir until one is picked.
 */
class LibraryStorage(private val context: Context, private val settings: SettingsRepository) {

    fun defaultRootDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Library").apply { mkdirs() }

    suspend fun root(): DocumentFile {
        val tree = settings.current().rootTreeUri
        if (tree != null) {
            val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            if (doc != null && doc.canWrite()) return doc
        }
        return DocumentFile.fromFile(defaultRootDir())
    }

    suspend fun describeRoot(): String {
        val tree = settings.current().rootTreeUri ?: return defaultRootDir().absolutePath
        return Uri.parse(tree).lastPathSegment?.replace("primary:", "Internal storage/") ?: tree
    }

    private suspend fun bookDir(folderName: String): DocumentFile {
        val root = root()
        return root.findFile(folderName)?.takeIf { it.isDirectory }
            ?: root.createDirectory(folderName)
            ?: throw IOException("Cannot create folder \"$folderName\"")
    }

    private fun createFile(dir: DocumentFile, name: String): DocumentFile {
        dir.findFile(name)?.delete()
        val uri = dir.uri
        if (uri.scheme == "file") {
            val f = File(uri.path!!, name)
            f.createNewFile()
            return DocumentFile.fromFile(f)
        }
        return dir.createFile("application/octet-stream", name)
            ?: throw IOException("Cannot create \"$name\"")
    }

    /** Copies [source] into the book folder as [name], replacing any existing file. */
    suspend fun saveFile(folderName: String, name: String, source: File): Uri = withContext(Dispatchers.IO) {
        val dir = bookDir(folderName)
        val tmp = createFile(dir, "$name.part")
        context.contentResolver.openOutputStream(tmp.uri, "wt")?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Cannot write \"$name\"")
        dir.findFile(name)?.delete()
        if (!tmp.renameTo(name)) throw IOException("Cannot rename \"$name\"")
        tmp.uri
    }

    fun openInput(uri: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Cannot open $uri")

    fun delete(uri: String) {
        val u = Uri.parse(uri)
        runCatching {
            if (u.scheme == "file") File(u.path!!).delete()
            else DocumentsContract.deleteDocument(context.contentResolver, u)
        }
    }

    suspend fun deleteBookFolder(folderName: String) = withContext(Dispatchers.IO) {
        root().findFile(folderName)?.delete()
    }
}
