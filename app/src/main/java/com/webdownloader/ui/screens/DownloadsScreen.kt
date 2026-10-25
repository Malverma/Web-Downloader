package com.webdownloader.ui.screens

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
import androidx.compose.ui.unit.dp
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpenBrowser: (String) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    val queue by remember { c.chapterDao.observeQueue() }.collectAsState(initial = emptyList())
    val settings by c.settings.state.collectAsState()
    var menu by remember { mutableStateOf(false) }

    ScreenScaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                actions = {
                    if (settings.downloadsPaused) {
                        IconButton(onClick = { scope.launch { c.downloads.resume() } }) { Icon(Icons.Default.PlayArrow, "Resume") }
                    } else {
                        IconButton(onClick = { scope.launch { c.downloads.pause() } }) { Icon(Icons.Default.Pause, "Pause") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                            val host = queue.firstOrNull()?.chapter?.url
                            if (settings.pauseReason != null && host != null) {
                                TextButton(onClick = { onOpenBrowser(host) }) { Text("Open site") }
                            }
                            TextButton(onClick = { scope.launch { c.downloads.resume() } }) { Text("Resume") }
                        }
                    }
                }
            }
            if (queue.isEmpty()) {
                EmptyState("Nothing downloading", "Queued chapters appear here.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(queue, key = { it.chapter.id }) { item ->
                        val ch = item.chapter
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.bookTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(ch.label, style = MaterialTheme.typography.bodyLarge)
                                when (ch.status) {
                                    ChapterStatus.DOWNLOADING -> {
                                        Text(
                                            if (ch.progressTotal > 0) "${ch.progressDone} / ${ch.progressTotal} pages" else "Finding pages…",
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
                                    ChapterStatus.FAILED -> Text(
                                        ch.error ?: "Failed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    else -> Text("Queued", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (ch.status == ChapterStatus.FAILED) {
                                IconButton(onClick = { scope.launch { c.library.queue(listOf(ch.id)) } }) { Icon(Icons.Default.Refresh, "Retry") }
                            }
                            IconButton(onClick = { scope.launch { c.library.cancel(ch) } }) { Icon(Icons.Default.Close, "Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
