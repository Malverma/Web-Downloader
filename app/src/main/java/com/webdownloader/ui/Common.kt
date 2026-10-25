package com.webdownloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.webdownloader.AppContainer
import com.webdownloader.WebDownloaderApp

@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember { (context.applicationContext as WebDownloaderApp).container }
}

/** Scaffold that leaves bottom insets to the app-level navigation bar. */
@Composable
fun ScreenScaffold(
    topBar: @Composable () -> Unit,
    snackbarHost: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        snackbarHost = { snackbarHost?.let { SnackbarHost(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}

@Composable
fun EmptyState(title: String, message: String, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            action?.invoke()
        }
    }
}

@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LazyColumn {
                    items(options) { option ->
                        Row(
                            Modifier
                                .selectable(selected = option == selected, onClick = { onSelect(option) })
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(label(option), Modifier.padding(start = 12.dp))
                        }
                    }
                }
                footer?.invoke()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

fun qualityDescription(q: com.webdownloader.data.db.Quality): String = when (q) {
    com.webdownloader.data.db.Quality.ORIGINAL -> "Original (unchanged)"
    com.webdownloader.data.db.Quality.CUSTOM -> "Custom (see settings)"
    else -> "${q.label} (${q.maxWidth}px, WebP ${q.webpQuality}%)"
}
