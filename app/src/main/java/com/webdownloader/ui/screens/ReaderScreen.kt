package com.webdownloader.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.webdownloader.data.db.Book
import com.webdownloader.data.db.Chapter
import com.webdownloader.data.db.ChapterStatus
import com.webdownloader.data.prefs.ReaderBackground
import com.webdownloader.reader.ReaderCache
import com.webdownloader.ui.EmptyState
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val message: String) : ReaderState
    data class Ready(
        val chapter: Chapter,
        val book: Book,
        val pages: List<ReaderCache.Page>,
        val previous: Chapter?,
        val next: Chapter?,
    ) : ReaderState
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ReaderScreen(
    chapterId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onReadOnline: (String) -> Unit,
) {
    val c = rememberContainer()
    val settings by c.settings.state.collectAsState()
    var state by remember(chapterId) { mutableStateOf<ReaderState>(ReaderState.Loading) }

    LaunchedEffect(chapterId) {
        state = try {
            val chapter = c.chapterDao.get(chapterId) ?: throw IllegalStateException("Chapter not found")
            val book = c.bookDao.get(chapter.bookId) ?: throw IllegalStateException("Book not found")
            if (chapter.status != ChapterStatus.DOWNLOADED || chapter.fileUri == null) {
                throw IllegalStateException("This chapter isn't downloaded yet.")
            }
            val pages = c.readerCache.open(chapter)
            if (pages.isEmpty()) throw IllegalStateException("The chapter file has no pages.")
            c.bookDao.touchRead(book.id, System.currentTimeMillis())
            ReaderState.Ready(
                chapter, book, pages,
                c.chapterDao.previous(book.id, chapter.sortIndex),
                c.chapterDao.next(book.id, chapter.sortIndex),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReaderState.Error(e.message ?: "Couldn't open chapter")
        }
    }

    val background = when (settings.readerBackground) {
        ReaderBackground.BLACK -> Color.Black
        ReaderBackground.GRAY -> Color(0xFF3A3A3A)
        ReaderBackground.WHITE -> Color.White
    }
    val onBackground = if (settings.readerBackground == ReaderBackground.WHITE) Color.Black else Color.White

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        when (val s = state) {
            ReaderState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderState.Error -> Box(Modifier.statusBarsPadding()) {
                EmptyState("Can't open chapter", s.message) { Button(onClick = onBack) { Text("Back") } }
            }
            is ReaderState.Ready -> ReaderContent(
                s, onBackground, settings.autoContinue,
                onBack = onBack, onOpenChapter = onOpenChapter, onReadOnline = onReadOnline,
            )
        }
    }
}

@Composable
private fun ReaderContent(
    s: ReaderState.Ready,
    textColor: Color,
    autoContinue: Boolean,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit,
    onReadOnline: (String) -> Unit,
) {
    val c = rememberContainer()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val pageCount = s.pages.size
    // Item 0 is the "previous chapter" header, pages follow, then the end card.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = s.chapter.lastPageRead.coerceIn(0, pageCount - 1) + 1)
    var showUi by remember { mutableStateOf(false) }
    val currentPage by remember { derivedStateOf { (listState.firstVisibleItemIndex - 1).coerceIn(0, pageCount - 1) } }
    val endVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "end" } } }
    val next by remember(s.next?.id) { s.next?.let { c.chapterDao.observe(it.id) } ?: flowOf(null) }.collectAsState(initial = s.next)

    DisposableEffect(showUi) {
        val window = context.findActivity()?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (showUi) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            context.findActivity()?.window?.let {
                WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(s.chapter.id) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { currentPage }.debounce(400).collect { c.chapterDao.saveProgress(s.chapter.id, it) }
    }
    LaunchedEffect(endVisible) {
        if (!endVisible) return@LaunchedEffect
        c.chapterDao.setRead(s.chapter.id, true)
        val n = next
        if (autoContinue && n != null && n.status == ChapterStatus.DOWNLOADED) {
            delay(1200)
            onOpenChapter(n.id)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = size.height
                    when {
                        offset.y < h * 0.25f -> scope.launch { listState.animateScrollBy(-h * 0.8f) }
                        offset.y > h * 0.75f -> scope.launch { listState.animateScrollBy(h * 0.8f) }
                        else -> showUi = !showUi
                    }
                }
            },
        ) {
            item(key = "start") {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val prev = s.previous
                    if (prev != null) {
                        TextButton(onClick = {
                            if (prev.status == ChapterStatus.DOWNLOADED) onOpenChapter(prev.id) else onReadOnline(prev.url)
                        }) {
                            Text("← Previous: ${prev.label}" + if (prev.status != ChapterStatus.DOWNLOADED) " (online)" else "", color = textColor)
                        }
                    }
                    Text(s.chapter.label, color = textColor, style = MaterialTheme.typography.titleMedium)
                }
            }
            items(pageCount, key = { "p$it" }) { i ->
                val page = s.pages[i]
                AsyncImage(
                    model = ImageRequest.Builder(context).data(page.file).build(),
                    contentDescription = "Page ${i + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().aspectRatio(page.width.toFloat() / page.height),
                )
            }
            item(key = "end") {
                EndCard(s, next, textColor, onOpenChapter, onReadOnline, onBack) {
                    scope.launch { next?.let { c.library.queue(listOf(it.id)) } }
                }
            }
        }

        AnimatedVisibility(visible = showUi, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(s.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(s.chapter.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                }
            }
        }
        AnimatedVisibility(visible = showUi, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { s.previous?.let { if (it.status == ChapterStatus.DOWNLOADED) onOpenChapter(it.id) } }, enabled = s.previous?.status == ChapterStatus.DOWNLOADED) {
                        Icon(Icons.Default.SkipPrevious, "Previous chapter")
                    }
                    Text("${currentPage + 1} / $pageCount", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = currentPage.toFloat(),
                        onValueChange = { scope.launch { listState.scrollToItem(it.toInt() + 1) } },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = { next?.let { if (it.status == ChapterStatus.DOWNLOADED) onOpenChapter(it.id) } }, enabled = next?.status == ChapterStatus.DOWNLOADED) {
                        Icon(Icons.Default.SkipNext, "Next chapter")
                    }
                }
            }
        }
    }
}

@Composable
private fun EndCard(
    s: ReaderState.Ready,
    next: Chapter?,
    textColor: Color,
    onOpenChapter: (Long) -> Unit,
    onReadOnline: (String) -> Unit,
    onBack: () -> Unit,
    onDownloadNext: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Finished ${s.chapter.label}", color = textColor, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        when {
            next == null -> {
                Text("You're all caught up.", color = textColor.copy(alpha = 0.7f))
                OutlinedButton(onClick = onBack) { Text("Back to book") }
            }
            next.status == ChapterStatus.DOWNLOADED -> {
                Button(onClick = { onOpenChapter(next.id) }, modifier = Modifier.fillMaxWidth()) { Text("Next: ${next.label} →") }
            }
            next.status == ChapterStatus.QUEUED || next.status == ChapterStatus.DOWNLOADING -> {
                Text("Downloading ${next.label}…", color = textColor)
                if (next.progressTotal > 0) {
                    LinearProgressIndicator(progress = { next.progressDone.toFloat() / next.progressTotal }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            else -> {
                Text("Next: ${next.label} isn't downloaded", color = textColor)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDownloadNext) { Text("Download") }
                    OutlinedButton(onClick = { onReadOnline(next.url) }) { Text("Read online") }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}
