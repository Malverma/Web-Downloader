package com.webdownloader.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.webdownloader.search.SearchItem
import com.webdownloader.search.SearchSort
import com.webdownloader.search.readKey
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.rememberContainer

private val ratingOptions = listOf(0f to "Any rating", 7f to "7+", 8f to "8+", 9f to "9+")
private val lengthOptions = listOf(
    SearchSort.DEFAULT to "Any length",
    SearchSort.MOST_CHAPTERS to "Most chapters",
    SearchSort.FEWEST_CHAPTERS to "Fewest chapters",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpenSeries: (String) -> Unit) {
    val c = rememberContainer()
    val search = c.search
    val s by search.state.collectAsState()
    val focus = LocalFocusManager.current
    var genreDialog by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val visible = s.visible

    LaunchedEffect(Unit) {
        search.loadGenres()
        if (!s.searched) search.search()
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= grid.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, s.results.size) {
        if (nearEnd && s.hasMore && !s.readOnly) search.loadMore()
    }

    // The header (title, search box, site and filter chips) slides away while scrolling down
    // through results and comes back on any upward scroll or at the top.
    var showHeader by remember { mutableStateOf(true) }
    val headerScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) showHeader = false else if (available.y > 6f) showHeader = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 }
            .collect { atTop -> if (atTop) showHeader = true }
    }

    ScreenScaffold(topBar = {}) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().statusBarsPadding().nestedScroll(headerScroll)) {
            AnimatedVisibility(visible = showHeader, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    TopAppBar(title = { Text("Search") }, windowInsets = WindowInsets(0, 0, 0, 0))
                    OutlinedTextField(
                        value = s.text,
                        onValueChange = search::setText,
                        singleLine = true,
                        placeholder = { Text("Search all sites") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (s.text.isNotEmpty()) {
                                IconButton(onClick = { search.setText(""); search.search() }) { Icon(Icons.Default.Close, "Clear") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); search.search() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )

                    // Sites
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        search.sources.forEach { src ->
                            FilterChip(selected = src.id in s.sites, onClick = { search.toggleSite(src.id) }, label = { Text(src.name) })
                        }
                    }

                    // Filters
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val genreCount = s.genres.size + s.excludedGenres.size
                        FilterChip(
                            selected = genreCount > 0,
                            onClick = { genreDialog = true },
                            label = { Text(if (genreCount == 0) "Genres" else "Genres ($genreCount)") },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                        )
                        FilterChip(
                            selected = s.completedOnly,
                            onClick = { search.setCompletedOnly(!s.completedOnly) },
                            label = { Text("Completed") },
                            leadingIcon = if (s.completedOnly) ({ Icon(Icons.Default.Check, null) }) else null,
                        )
                        FilterChip(
                            selected = s.readOnly,
                            onClick = { search.setReadOnly(!s.readOnly) },
                            label = { Text(if (s.read.isEmpty()) "Read" else "Read (${s.read.size})") },
                            leadingIcon = if (s.readOnly) ({ Icon(Icons.Default.Check, null) }) else null,
                        )
                        ChoiceChip(ratingOptions, s.minRating, search::setMinRating)
                        ChoiceChip(
                            lengthOptions,
                            s.sort.takeIf { sort -> lengthOptions.any { it.first == sort } } ?: SearchSort.DEFAULT,
                            search::setSort,
                        )
                        FilterChip(
                            selected = s.sort == SearchSort.TITLE,
                            onClick = { search.setSort(if (s.sort == SearchSort.TITLE) SearchSort.DEFAULT else SearchSort.TITLE) },
                            label = { Text("A–Z") },
                            leadingIcon = if (s.sort == SearchSort.TITLE) ({ Icon(Icons.Default.Check, null) }) else null,
                        )
                    }

                    if (s.genres.isNotEmpty() || s.excludedGenres.isNotEmpty()) {
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            (s.genres.sorted() + s.excludedGenres.sorted()).forEach { key ->
                                val name = s.genreOptions.firstOrNull { it.key == key }?.name ?: key
                                // Tapping cycles the genre on (included → excluded → off), same as in the dialog.
                                GenreChip(name, genreState(key, s.genres, s.excludedGenres)) { search.toggleGenre(key) }
                            }
                        }
                    }
                }
            }
            if (s.checkingGenres) {
                Text(
                    "Checking genres to hide excluded ones…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (s.loading && !s.readOnly) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (s.errors.isNotEmpty() && !s.readOnly) {
                Text(
                    s.errors.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (s.minRating > 0f && "flame" in s.sites && !s.readOnly) {
                Text(
                    "Flame Comics doesn't show ratings, so its results are hidden by the rating filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val readUrls = s.readUrls
            when {
                s.readOnly && visible.isEmpty() -> EmptyState(
                    "Nothing marked read",
                    "Long-press a manga here or in your library to mark it read.",
                )
                s.loading && visible.isEmpty() && !s.readOnly -> Unit
                visible.isEmpty() && !s.hasMore -> EmptyState("No results", "Try a different title, genre or filter.")
                else -> LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.url }) { item ->
                        val isRead = readKey(item.url) in readUrls
                        ResultCard(
                            item,
                            isRead = isRead,
                            onToggleRead = { if (isRead) search.unmarkRead(item.url) else search.markRead(item) },
                        ) { onOpenSeries(item.url) }
                    }
                    if (!s.readOnly && (s.loadingMore || s.hasMore)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                if (s.loadingMore) CircularProgressIndicator(Modifier.size(28.dp))
                                else TextButton(onClick = search::loadMore) { Text("Load more") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (genreDialog) {
        AlertDialog(
            onDismissRequest = { genreDialog = false },
            title = { Text("Genres") },
            text = {
                if (s.genreOptions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            "Tap once to include a genre, twice to exclude it, and a third time to clear it. " +
                                "Sites without an included genre are skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.genreOptions.forEach { g ->
                                GenreChip(g.name, genreState(g.key, s.genres, s.excludedGenres)) { search.toggleGenre(g.key) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { genreDialog = false }) { Text("Done") } },
            dismissButton = {
                if (s.genres.isNotEmpty() || s.excludedGenres.isNotEmpty()) TextButton(onClick = { search.clearGenres() }) { Text("Clear") }
            },
        )
    }
}

private enum class GenreState { OFF, INCLUDED, EXCLUDED }

private fun genreState(key: String, included: Set<String>, excluded: Set<String>) = when (key) {
    in included -> GenreState.INCLUDED
    in excluded -> GenreState.EXCLUDED
    else -> GenreState.OFF
}

@Composable
private fun GenreChip(name: String, state: GenreState, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    FilterChip(
        selected = state != GenreState.OFF,
        onClick = onClick,
        label = {
            Text(
                name,
                textDecoration = if (state == GenreState.EXCLUDED) TextDecoration.LineThrough else null,
            )
        },
        leadingIcon = when (state) {
            GenreState.INCLUDED -> { { Icon(Icons.Default.Check, "Included", Modifier.size(16.dp)) } }
            GenreState.EXCLUDED -> { { Icon(Icons.Default.Block, "Excluded", Modifier.size(16.dp)) } }
            GenreState.OFF -> null
        },
        colors = if (state == GenreState.EXCLUDED) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedLeadingIconColor = error,
            )
        } else FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun <T> ChoiceChip(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != options.first().first,
            onClick = { open = true },
            label = { Text(options.firstOrNull { it.first == selected }?.second ?: options.first().second) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onSelect(value); open = false })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(item: SearchItem, isRead: Boolean, onToggleRead: () -> Unit, onClick: () -> Unit) {
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
            if (item.coverUrl != null) {
                AsyncImage(item.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MenuBook, null, Modifier.size(36.dp))
            }
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(bottomEnd = 8.dp),
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Text(
                    item.siteName.substringBefore(' '),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (isRead) ReadBadge(Modifier.align(Alignment.TopEnd))
            ReadMenu(menu, isRead, onDismiss = { menu = false }, onToggle = { menu = false; onToggleRead() })
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.rating != null) {
                Icon(Icons.Default.Star, null, Modifier.size(12.dp), tint = Color(0xFFFFB300))
                Text(
                    "%.1f".format(item.rating) + "  ",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                item.chapters?.let { "$it ch" } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Corner marker on covers of manga marked read. */
@Composable
fun ReadBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(bottomStart = 8.dp),
        modifier = modifier,
    ) {
        Icon(
            Icons.Default.DoneAll,
            contentDescription = "Read",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).size(14.dp),
        )
    }
}

/** Long-press menu on a manga cover for marking it read or unread. */
@Composable
fun ReadMenu(expanded: Boolean, isRead: Boolean, onDismiss: () -> Unit, onToggle: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(if (isRead) "Mark as unread" else "Mark as read") }, onClick = onToggle)
    }
}
