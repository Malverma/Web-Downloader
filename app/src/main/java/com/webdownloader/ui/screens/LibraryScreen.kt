package com.webdownloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.webdownloader.data.db.BookWithCounts
import com.webdownloader.data.db.ReadSeries
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.formatBytes
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.launch
import java.io.File

private enum class LibrarySort(val label: String) {
    RECENT_READ("Recently read"),
    RECENT_ADDED("Recently added"),
    UPDATED("Recently updated"),
    TITLE("Title"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenBook: (Long) -> Unit, onSearch: () -> Unit) {
    val c = rememberContainer()
    // The library lists manga you've downloaded (or are downloading); finding new ones is Search's job.
    val books by remember { c.bookDao.observeLibrary() }.collectAsState(initial = null)
    val read by remember { c.readDao.observeAll() }.collectAsState(initial = emptyList())
    val readUrls = remember(read) { read.mapTo(HashSet()) { it.url } }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT_READ) }
    var sortMenu by remember { mutableStateOf(false) }

    ScreenScaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(if (s == sort) "✓ ${s.label}" else s.label) },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search titles") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            val list = books
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.none { it.downloaded > 0 || it.active > 0 } -> EmptyState(
                    title = "No downloads yet",
                    message = "Find a manga in Search, pick chapters, and they'll show up here once downloaded.",
                ) { Button(onClick = onSearch) { Text("Search") } }
                else -> {
                    val shown = list
                        .filter { it.downloaded > 0 || it.active > 0 }
                        .filter { query.isBlank() || it.book.title.contains(query, ignoreCase = true) }
                        .let { l ->
                            when (sort) {
                                LibrarySort.RECENT_READ -> l.sortedByDescending { maxOf(it.book.lastReadAt, it.book.addedAt) }
                                LibrarySort.RECENT_ADDED -> l.sortedByDescending { it.book.addedAt }
                                LibrarySort.UPDATED -> l.sortedByDescending { it.book.updatedAt }
                                LibrarySort.TITLE -> l.sortedBy { it.book.title.lowercase() }
                            }
                        }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.book.id }) { item ->
                            val isRead = item.book.sourceUrl in readUrls
                            BookCard(
                                item,
                                isRead = isRead,
                                onToggleRead = {
                                    scope.launch {
                                        val b = item.book
                                        if (isRead) c.readDao.unmark(b.sourceUrl)
                                        else c.readDao.mark(
                                            ReadSeries(
                                                url = b.sourceUrl, title = b.title, coverUrl = b.coverUrl, siteId = b.sourceHost,
                                                siteName = b.sourceHost, chapters = item.total, rating = null,
                                                markedAt = System.currentTimeMillis(),
                                            )
                                        )
                                    }
                                },
                            ) { onOpenBook(item.book.id) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(item: BookWithCounts, isRead: Boolean, onToggleRead: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (item.book.coverPath != null) {
                AsyncImage(
                    model = File(item.book.coverPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(40.dp))
            }
            if (item.unread > 0) {
                Badge(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Text("${item.unread}") }
            }
            if (isRead) ReadBadge(Modifier.align(Alignment.TopStart))
            ReadMenu(menu, isRead, onDismiss = { menu = false }, onToggle = { menu = false; onToggleRead() })
        }
        Text(
            item.book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row {
            Text(
                buildString {
                    append(if (item.downloaded == 1) "1 chapter" else "${item.downloaded} chapters")
                    if (item.active > 0) append(" · ${item.active} downloading")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.sizeBytes > 0) {
                Text(
                    formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
