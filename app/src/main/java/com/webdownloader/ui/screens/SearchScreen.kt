package com.webdownloader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.webdownloader.search.SearchItem
import com.webdownloader.search.SearchSort
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.rememberContainer

private val ratingOptions = listOf(0f to "Any rating", 7f to "7+", 8f to "8+", 9f to "9+")
private val chapterOptions = listOf(0 to "Any length", 20 to "20+ ch", 50 to "50+ ch", 100 to "100+ ch")

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
        if (nearEnd && s.hasMore) search.loadMore()
    }

    ScreenScaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                FilterChip(
                    selected = s.genres.isNotEmpty(),
                    onClick = { genreDialog = true },
                    label = { Text(if (s.genres.isEmpty()) "Genres" else "Genres (${s.genres.size})") },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
                )
                ChoiceChip(ratingOptions, s.minRating, search::setMinRating)
                ChoiceChip(chapterOptions, s.minChapters, search::setMinChapters)
                ChoiceChip(SearchSort.entries.map { it to "Sort: ${it.label}" }, s.sort, search::setSort)
            }

            if (s.genres.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    s.genres.sorted().forEach { key ->
                        val name = s.genreOptions.firstOrNull { it.key == key }?.name ?: key
                        InputChip(
                            selected = true,
                            onClick = { search.toggleGenre(key) },
                            label = { Text(name) },
                            trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            if (s.errors.isNotEmpty()) {
                Text(
                    s.errors.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (s.minRating > 0f && "flame" in s.sites) {
                Text(
                    "Flame Comics doesn't show ratings, so its results are hidden by the rating filter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                s.loading && visible.isEmpty() -> Unit
                visible.isEmpty() && !s.hasMore -> EmptyState("No results", "Try a different title, genre or filter.")
                else -> LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.url }) { item -> ResultCard(item) { onOpenSeries(item.url) } }
                    if (s.loadingMore || s.hasMore) {
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
                            "Pick several to narrow results. Sites without a selected genre are skipped.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.genreOptions.forEach { g ->
                                FilterChip(
                                    selected = g.key in s.genres,
                                    onClick = { search.toggleGenre(g.key) },
                                    label = { Text(g.name) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { genreDialog = false }) { Text("Done") } },
            dismissButton = {
                if (s.genres.isNotEmpty()) TextButton(onClick = { search.clearGenres() }) { Text("Clear") }
            },
        )
    }
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

@Composable
private fun ResultCard(item: SearchItem, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
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
