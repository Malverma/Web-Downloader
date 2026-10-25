package com.webdownloader.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.webdownloader.data.db.Chapter
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.db.Quality
import com.webdownloader.extraction.ChallengeException
import com.webdownloader.extraction.ExtractResult
import com.webdownloader.extraction.ExtractedChapter
import com.webdownloader.extraction.WebViewExtractor
import com.webdownloader.ui.ChoiceDialog
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.qualityDescription
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface PickerState {
    data object Loading : PickerState
    data class Error(val message: String) : PickerState
    data class Ready(
        val result: ExtractResult,
        val title: String,
        val chapters: List<ExtractedChapter>,
        val existing: Map<String, Chapter>,
    ) : PickerState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PickerScreen(seriesUrl: String, selectUrl: String?, onBack: () -> Unit, onDone: (Long) -> Unit) {
    val c = rememberContainer()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<PickerState>(PickerState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var anchor by remember { mutableStateOf<Int?>(null) }
    var newestFirst by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf<Quality?>(null) }
    var qualityDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(seriesUrl, reload) {
        state = PickerState.Loading
        state = try {
            val canon = c.library.canonical(seriesUrl)
            val cached = c.browser.lastSeries?.takeIf { c.library.canonical(it.url) == canon && it.chapters.isNotEmpty() }
            val result = cached ?: c.extractor.extract(seriesUrl, WebViewExtractor.Mode.SERIES)
            if (result.chapters.isEmpty()) throw IllegalStateException("No chapters found on this page.")
            val chapters = c.library.sortChapters(result.chapters)
            val book = c.bookDao.findByUrl(canon)
            val existing = book?.let { b -> c.chapterDao.forBook(b.id).associateBy { it.url } } ?: emptyMap()
            if (quality == null) quality = book?.qualityOverride ?: c.settings.current().defaultQuality
            selected = when {
                selectUrl != null -> setOf(c.library.canonical(selectUrl))
                book != null -> chapters.map { it.url }.filter { it !in existing }.toSet()
                else -> emptySet()
            }
            PickerState.Ready(result, book?.title ?: c.library.cleanTitle(result.title, canon), chapters, existing)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChallengeException) {
            PickerState.Error(e.message.orEmpty())
        } catch (e: Exception) {
            PickerState.Error(e.message ?: "Couldn't read the chapter list.")
        }
    }

    val ready = state as? PickerState.Ready
    val rows = ready?.chapters?.let { if (newestFirst) it.reversed() else it }.orEmpty()

    fun confirm(download: Boolean) {
        val r = ready ?: return
        saving = true
        scope.launch {
            val result = c.library.upsertSeries(seriesUrl, r.result)
            if (download) {
                val ids = c.chapterDao.forBook(result.bookId).filter { it.url in selected }.map { it.id }
                c.library.queue(ids, quality)
            }
            onDone(result.bookId)
        }
    }

    ScreenScaffold(
        topBar = {
            TopAppBar(
                title = { Text(ready?.title ?: "Choose chapters", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { newestFirst = !newestFirst; anchor = null }) {
                            Icon(Icons.Default.SwapVert, contentDescription = "Reverse order")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (ready != null) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(onClick = { confirm(false) }, enabled = !saving, modifier = Modifier.weight(1f)) {
                            Text(if (ready.existing.isEmpty()) "Add only" else "Update only")
                        }
                        Button(onClick = { confirm(true) }, enabled = !saving && selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("Download ${selected.size}")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                PickerState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Reading chapter list…", Modifier.padding(top = 12.dp))
                }
                is PickerState.Error -> EmptyState("Couldn't load chapters", s.message) {
                    Button(onClick = { reload++ }) { Text("Retry") }
                }
                is PickerState.Ready -> Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(onClick = { selected = s.chapters.map { it.url }.toSet() }, label = { Text("All") })
                        AssistChip(onClick = { selected = emptySet() }, label = { Text("None") })
                        AssistChip(
                            onClick = {
                                selected = s.chapters.map { it.url }
                                    .filter { s.existing[it]?.status != ChapterStatus.DOWNLOADED }.toSet()
                            },
                            label = { Text("Not downloaded") },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${s.chapters.size} chapters · long-press to select a range",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AssistChip(onClick = { qualityDialog = true }, label = { Text("Quality: ${quality?.label ?: "-"}") })
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, ch -> ch.url }) { index, ch ->
                            val existing = s.existing[ch.url]
                            val checked = ch.url in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            selected = if (checked) selected - ch.url else selected + ch.url
                                            anchor = index
                                        },
                                        onLongClick = {
                                            val a = anchor ?: index
                                            val range = rows.subList(minOf(a, index), maxOf(a, index) + 1).map { it.url }
                                            selected = selected + range
                                            anchor = index
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.library.labelFor(ch), style = MaterialTheme.typography.bodyLarge)
                                    val status = when (existing?.status) {
                                        null -> if (s.existing.isNotEmpty()) "New" else null
                                        ChapterStatus.DOWNLOADED -> "Downloaded"
                                        ChapterStatus.QUEUED, ChapterStatus.DOWNLOADING -> "In queue"
                                        ChapterStatus.FAILED -> "Failed"
                                        ChapterStatus.NOT_DOWNLOADED -> null
                                    }
                                    if (status != null) {
                                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (qualityDialog) {
        ChoiceDialog(
            title = "Download quality",
            options = Quality.entries,
            selected = quality,
            label = ::qualityDescription,
            onDismiss = { qualityDialog = false },
            onSelect = { quality = it; qualityDialog = false },
        )
    }
}
