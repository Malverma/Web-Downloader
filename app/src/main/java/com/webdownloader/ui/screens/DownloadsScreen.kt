package com.webdownloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.QueueItem
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.formatBytes
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.launch

/** Downloads grouped into one folder per manga, mirroring the folders on disk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpenBrowser: (String) -> Unit, onRead: (Long) -> Unit, onOpenBook: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val all by remember { c.chapterDao.observeDownloads() }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Folders with active downloads start open; finished ones start closed.
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    val activeCount = all.count { it.chapter.status == ChapterStatus.QUEUED || it.chapter.status == ChapterStatus.DOWNLOADING }
    val downloadingNow = all.filter { it.chapter.status == ChapterStatus.DOWNLOADING }
    // 1-based place in line for each queued chapter, in the order the worker takes them.
    val queuePosition = all.filter { it.chapter.status == ChapterStatus.QUEUED }
        .sortedWith(compareBy({ it.chapter.queuedAt ?: Long.MAX_VALUE }, { it.chapter.sortIndex }))
        .mapIndexed { i, q -> q.chapter.id to i + 1 }.toMap()
    // Inside each folder: downloading first, then the queue in order, then failed, then finished.
    val folders = all.groupBy { it.chapter.bookId }.values.map { items ->
        items.sortedWith(
            compareBy<QueueItem>(
                {
                    when (it.chapter.status) {
                        ChapterStatus.DOWNLOADING -> 0
                        ChapterStatus.QUEUED -> 1
                        ChapterStatus.FAILED -> 2
                        else -> 3
                    }
                },
                { queuePosition[it.chapter.id] ?: 0 },
                { it.chapter.sortIndex },
            ),
        )
    }

    ScreenScaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Downloads")
                        if (activeCount > 0) {
                            Text("$activeCount in progress", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (settings.downloadsPaused) {
                        IconButton(onClick = { scope.launch { c.downloads.resume() } }) { Icon(Icons.Default.PlayArrow, "Resume") }
                    } else {
                        IconButton(onClick = { scope.launch { c.downloads.pause() } }) { Icon(Icons.Default.Pause, "Pause") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Restart downloads") },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        c.downloads.forceRestart()
                                        snackbar.showSnackbar("Downloads restarted")
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text("Retry failed") }, onClick = {
                                menu = false
                                scope.launch {
                                    c.chapterDao.retryFailed(System.currentTimeMillis())
                                    c.downloads.enqueue()
                                }
                            })
                            DropdownMenuItem(text = { Text("Clear queue") }, onClick = {
                                menu = false
                                scope.launch {
                                    c.downloads.stop()
                                    c.chapterDao.clearQueue()
                                }
                            })
                        }
                    }
                },
            )
        },
        snackbarHost = snackbar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (settings.downloadsPaused) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Downloads paused", style = MaterialTheme.typography.titleSmall)
                        settings.pauseReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row {
                            val url = all.firstOrNull { it.chapter.status == ChapterStatus.QUEUED }?.chapter?.url
                            if (settings.pauseReason != null && url != null) {
                                TextButton(onClick = { onOpenBrowser(url) }) { Text("Open site") }
                            }
                            TextButton(onClick = { scope.launch { c.downloads.resume() } }) { Text("Resume") }
                        }
                    }
                }
            }
            if (folders.isEmpty()) {
                EmptyState("No downloads yet", "Downloaded chapters appear here, in a folder for each manga.")
            } else {
                // Pinned above the list so it stays in view however far the folders are scrolled.
                if (downloadingNow.isNotEmpty()) NowDownloading(downloadingNow)
                LazyColumn(Modifier.fillMaxSize()) {
                    folders.forEach { items ->
                        val bookId = items.first().chapter.bookId
                        val active = items.any { it.chapter.status == ChapterStatus.QUEUED || it.chapter.status == ChapterStatus.DOWNLOADING }
                        val open = expanded[bookId] ?: active
                        item(key = "folder-$bookId") {
                            FolderHeader(items, open, onToggle = { expanded[bookId] = !open }, onOpenBook = { onOpenBook(bookId) })
                        }
                        if (open) {
                            items(items, key = { it.chapter.id }) { item ->
                                DownloadRow(
                                    item,
                                    queuePosition = queuePosition[item.chapter.id],
                                    onOpen = {
                                        when (item.chapter.status) {
                                            ChapterStatus.DOWNLOADED -> onRead(item.chapter.id)
                                            ChapterStatus.QUEUED -> scope.launch {
                                                c.chapterDao.moveToFront(item.chapter.id)
                                                snackbar.showSnackbar("${item.chapter.label} will download next")
                                            }
                                            else -> Unit
                                        }
                                    },
                                    onRetry = { scope.launch { c.library.queue(listOf(item.chapter.id)) } },
                                    onRemove = {
                                        scope.launch {
                                            if (item.chapter.status == ChapterStatus.DOWNLOADED) c.library.deleteDownload(item.chapter)
                                            else c.library.cancel(item.chapter)
                                        }
                                    },
                                )
                            }
                        }
                        item(key = "divider-$bookId") { HorizontalDivider() }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderHeader(items: List<QueueItem>, open: Boolean, onToggle: () -> Unit, onOpenBook: () -> Unit) {
    val downloaded = items.filter { it.chapter.status == ChapterStatus.DOWNLOADED }
    val downloading = items.count { it.chapter.status == ChapterStatus.DOWNLOADING }
    val queued = items.count { it.chapter.status == ChapterStatus.QUEUED }
    val failed = items.count { it.chapter.status == ChapterStatus.FAILED }
    val summary = buildList {
        if (downloaded.isNotEmpty()) add("${downloaded.size} downloaded · ${formatBytes(downloaded.sumOf { it.chapter.sizeBytes ?: 0 })}")
        if (downloading > 0) add("$downloading downloading")
        if (queued > 0) add("$queued queued")
        if (failed > 0) add("$failed failed")
    }.joinToString(" · ")
    Surface(tonalElevation = if (open) 2.dp else 0.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(items.first().bookTitle, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpenBook) { Text("Book") }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (open) "Collapse" else "Expand")
        }
    }
}

/** Everything downloading right now, across all manga, pinned above the folders. */
@Composable
private fun NowDownloading(items: List<QueueItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Downloading, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Now downloading",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            items.forEach { item ->
                val ch = item.chapter
                Column {
                    Text(
                        "${item.bookTitle} · ${ch.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (ch.progressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { ch.progressDone.toFloat() / ch.progressTotal },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(item: QueueItem, queuePosition: Int?, onOpen: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val ch = item.chapter
    val downloading = ch.status == ChapterStatus.DOWNLOADING
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (downloading) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status marker: spinning while downloading, place in line while queued.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when (ch.status) {
                ChapterStatus.DOWNLOADING -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                ChapterStatus.QUEUED -> Text(
                    "#${queuePosition ?: "–"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                ch.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (downloading) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            when (ch.status) {
                ChapterStatus.DOWNLOADING -> {
                    Text(
                        if (ch.progressTotal > 0) "Downloading · ${ch.progressDone} / ${ch.progressTotal} pages" else "Downloading · finding pages…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ch.progressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { ch.progressDone.toFloat() / ch.progressTotal },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
                ChapterStatus.FAILED -> Text(ch.error ?: "Failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                ChapterStatus.QUEUED -> Text(
                    if (queuePosition == 1) "Queued · next up" else "Queued · tap to download next",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    buildString {
                        ch.pageCount?.let { append("$it pages") }
                        ch.sizeBytes?.let { append(" · ${formatBytes(it)}") }
                        if (ch.isRead) append(" · Read")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (ch.status == ChapterStatus.FAILED) {
            IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry") }
        }
        IconButton(onClick = onRemove) {
            if (ch.status == ChapterStatus.DOWNLOADED) Icon(Icons.Default.Delete, "Delete download")
            else Icon(Icons.Default.Close, "Cancel")
        }
    }
}
