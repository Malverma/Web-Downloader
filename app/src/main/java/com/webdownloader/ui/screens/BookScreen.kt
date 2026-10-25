package com.webdownloader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.webdownloader.data.db.Chapter
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.Quality
import com.webdownloader.extraction.WebViewExtractor
import com.webdownloader.ui.ChoiceDialog
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.formatBytes
import com.webdownloader.ui.qualityDescription
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private enum class BookDialog { QUALITY, RECOMPRESS, RENAME, DELETE_BOOK, DELETE_DOWNLOADS }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    onGetChapters: (String) -> Unit,
    onRead: (Long) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val book by remember(bookId) { c.bookDao.observe(bookId) }.collectAsState(initial = null)
    val chapters by remember(bookId) { c.chapterDao.observeForBook(bookId) }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<BookDialog?>(null) }
    var checking by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(false) }

    val b = book ?: run {
        ScreenScaffold(topBar = {
            TopAppBar(title = {}, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        }) {}
        return
    }

    val downloaded = chapters.filter { it.status == ChapterStatus.DOWNLOADED }
    val active = chapters.count { it.status == ChapterStatus.QUEUED || it.status == ChapterStatus.DOWNLOADING }
    val resumeTarget = run {
        chapters.lastOrNull { it.status == ChapterStatus.DOWNLOADED && !it.isRead && it.lastPageRead > 0 }
            ?: chapters.drop(chapters.indexOfLast { it.isRead } + 1).firstOrNull { it.status == ChapterStatus.DOWNLOADED }
            ?: downloaded.firstOrNull { !it.isRead }
            ?: downloaded.firstOrNull()
    }

    fun checkForUpdates() {
        if (checking) return
        checking = true
        scope.launch {
            try {
                val result = c.extractor.extract(b.sourceUrl, WebViewExtractor.Mode.SERIES)
                if (result.chapters.isEmpty()) {
                    snackbar.showSnackbar("No chapters found on the source page")
                } else {
                    val r = c.library.upsertSeries(b.sourceUrl, result)
                    snackbar.showSnackbar(if (r.newChapters == 0) "No new chapters" else "${r.newChapters} new chapter(s)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val res = snackbar.showSnackbar(e.message ?: "Update failed", actionLabel = "Open site")
                if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) onOpenUrl(b.sourceUrl)
            } finally {
                checking = false
            }
        }
    }

    ScreenScaffold(
        snackbarHost = snackbar,
        topBar = {
            TopAppBar(
                title = { Text(b.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Check for new chapters") }, onClick = { menu = false; checkForUpdates() })
                            DropdownMenuItem(text = { Text("Quality for this book") }, onClick = { menu = false; dialog = BookDialog.QUALITY })
                            DropdownMenuItem(text = { Text("Re-compress downloads") }, onClick = { menu = false; dialog = BookDialog.RECOMPRESS })
                            DropdownMenuItem(text = { Text("Open source page") }, onClick = { menu = false; onOpenUrl(b.sourceUrl) })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = BookDialog.RENAME })
                            DropdownMenuItem(text = { Text("Delete all downloads") }, onClick = { menu = false; dialog = BookDialog.DELETE_DOWNLOADS })
                            DropdownMenuItem(text = { Text("Delete book") }, onClick = { menu = false; dialog = BookDialog.DELETE_BOOK })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "header") {
                Column(Modifier.padding(16.dp)) {
                    Row {
                        Box(
                            Modifier.width(96.dp).height(144.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (b.coverPath != null) {
                                AsyncImage(File(b.coverPath), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Default.MenuBook, null, Modifier.size(36.dp))
                            }
                        }
                        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleLarge)
                            Text(b.sourceHost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${downloaded.size} downloaded" + if (active > 0) " · $active downloading" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Storage: ${formatBytes(downloaded.sumOf { it.sizeBytes ?: 0 })} · Quality: ${(b.qualityOverride ?: settings.defaultQuality).label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { resumeTarget?.let { onRead(it.id) } }, enabled = resumeTarget != null, modifier = Modifier.weight(1f)) {
                            Text(if (chapters.any { it.isRead || it.lastPageRead > 0 }) "Resume" else "Start reading")
                        }
                        OutlinedButton(onClick = { onGetChapters(b.sourceUrl) }, modifier = Modifier.weight(1f)) {
                            Text("Get more chapters")
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded chapters", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { newestFirst = !newestFirst }) {
                            Icon(Icons.Default.SwapVert, null)
                            Text(if (newestFirst) "Newest first" else "Oldest first")
                        }
                    }
                }
                HorizontalDivider()
            }
            if (downloaded.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (active > 0) "Downloading… chapters appear here when they finish." else "No downloaded chapters. Tap “Get more chapters”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(if (newestFirst) downloaded.reversed() else downloaded, key = { it.id }) { ch ->
                ChapterRow(
                    chapter = ch,
                    onClick = {
                        when (ch.status) {
                            ChapterStatus.DOWNLOADED -> onRead(ch.id)
                            ChapterStatus.NOT_DOWNLOADED, ChapterStatus.FAILED -> scope.launch { c.library.queue(listOf(ch.id)) }
                            else -> Unit
                        }
                    },
                    onLongClick = { scope.launch { c.chapterDao.setRead(ch.id, !ch.isRead) } },
                    onAction = {
                        scope.launch {
                            when (ch.status) {
                                ChapterStatus.DOWNLOADED -> c.library.deleteDownload(ch)
                                ChapterStatus.QUEUED, ChapterStatus.DOWNLOADING -> c.library.cancel(ch)
                                else -> c.library.queue(listOf(ch.id))
                            }
                        }
                    },
                )
            }
            item { Box(Modifier.navigationBarsPadding().height(24.dp)) }
        }
    }

    when (dialog) {
        BookDialog.QUALITY -> ChoiceDialog(
            title = "Quality for this book",
            options = listOf<Quality?>(null) + Quality.entries,
            selected = b.qualityOverride,
            label = { it?.let(::qualityDescription) ?: "Use default (${settings.defaultQuality.label})" },
            onDismiss = { dialog = null },
            onSelect = { q -> scope.launch { c.bookDao.update(b.copy(qualityOverride = q)) }; dialog = null },
        )
        BookDialog.RECOMPRESS -> ChoiceDialog(
            title = "Re-compress ${downloaded.size} chapter(s)",
            options = Quality.entries.filter { it != Quality.ORIGINAL },
            selected = null,
            label = ::qualityDescription,
            onDismiss = { dialog = null },
            onSelect = { q ->
                c.downloads.recompress(b.id, q)
                scope.launch { snackbar.showSnackbar("Re-compressing in the background") }
                dialog = null
            },
            footer = {
                Text(
                    "Pages are re-encoded in place. The originals can't be restored, except by downloading again.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            },
        )
        BookDialog.RENAME -> {
            var name by remember { mutableStateOf(b.title) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Rename") },
                text = { OutlinedTextField(name, { name = it }, singleLine = true) },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch { c.bookDao.update(b.copy(title = name.trim().ifEmpty { b.title })) }
                        dialog = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        BookDialog.DELETE_DOWNLOADS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete all downloads?") },
            text = { Text("${downloaded.size} chapter file(s) will be removed. The book and its chapter list stay.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { downloaded.forEach { c.library.deleteDownload(it) } }
                    dialog = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        BookDialog.DELETE_BOOK -> {
            var deleteFiles by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Delete “${b.title}”?") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFiles, { deleteFiles = it })
                        Text("Also delete downloaded files")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        scope.launch {
                            c.library.deleteBook(b, deleteFiles)
                            onBack()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        null -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(chapter: Chapter, onClick: () -> Unit, onLongClick: () -> Unit, onAction: () -> Unit) {
    // Chapters you've opened get a darker row so new ones stand out.
    val opened = chapter.openedAt != null || chapter.isRead || chapter.lastPageRead > 0
    val dim = opened
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (opened) Color.Black.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val status = when (chapter.status) {
                ChapterStatus.DOWNLOADED -> buildString {
                    append("Downloaded")
                    chapter.pageCount?.let { append(" · $it pages") }
                    chapter.sizeBytes?.let { append(" · ${formatBytes(it)}") }
                    chapter.quality?.let { append(" · ${it.label}") }
                    if (chapter.isRead) append(" · Read")
                    else if (chapter.lastPageRead > 0) append(" · Page ${chapter.lastPageRead + 1}")
                }
                ChapterStatus.QUEUED -> "Queued"
                ChapterStatus.DOWNLOADING -> if (chapter.progressTotal > 0) "Downloading ${chapter.progressDone} / ${chapter.progressTotal}" else "Finding pages…"
                ChapterStatus.FAILED -> "Failed: ${chapter.error ?: "unknown error"}"
                ChapterStatus.NOT_DOWNLOADED -> if (chapter.removedFromSource) "Removed from source" else "Not downloaded"
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (chapter.status == ChapterStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            if (chapter.status == ChapterStatus.DOWNLOADING && chapter.progressTotal > 0) {
                LinearProgressIndicator(
                    progress = { chapter.progressDone.toFloat() / chapter.progressTotal },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        IconButton(onClick = onAction) {
            when (chapter.status) {
                ChapterStatus.DOWNLOADED -> Icon(Icons.Default.Delete, "Delete download")
                ChapterStatus.QUEUED, ChapterStatus.DOWNLOADING -> Icon(Icons.Default.Close, "Cancel")
                else -> Icon(Icons.Default.Download, "Download")
            }
        }
    }
}
