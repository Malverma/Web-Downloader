package com.webdownloader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen(initialUrl: String?, onAddSeries: (seriesUrl: String, selectUrl: String?) -> Unit) {
    val c = rememberContainer()
    val holder = c.browser
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val url by holder.currentUrl.collectAsState()
    val progress by holder.progress.collectAsState()
    val canGoBack by holder.canGoBack.collectAsState()
    val detection by holder.detection.collectAsState()
    val settings by c.settings.state.collectAsState()
    var address by remember(url) { mutableStateOf(url) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) holder.load(initialUrl)
    }
    DisposableEffect(Unit) { onDispose { holder.detach() } }
    BackHandler(enabled = canGoBack) { holder.goBack() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("Search or enter address") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    holder.load(address)
                    focus.clearFocus()
                }),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { holder.reload() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Back") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) },
                        onClick = { holder.goBack(); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Forward") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                        onClick = { holder.goForward(); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(if (settings.desktopUserAgent) "✓ Desktop site" else "Desktop site") },
                        onClick = {
                            menu = false
                            scope.launch {
                                c.settings.update { it.copy(desktopUserAgent = !it.desktopUserAgent) }
                                holder.applySettings()
                                holder.reload()
                            }
                        },
                    )
                }
            }
        }
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
            AndroidView(factory = { holder.attach(context) }, modifier = Modifier.fillMaxSize())
            if (url.isBlank() || url == "about:blank") {
                StartPage(hosts = remember { c.siteRules.all().keys.sorted() }, onOpen = { holder.load(it) })
            }
            val d = detection
            if (d != null && (d.type == "series" || d.type == "chapter")) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (d.type == "series") {
                            holder.lastSeries = d
                            onAddSeries(d.url, null)
                        } else {
                            onAddSeries(d.seriesUrl ?: d.url, d.url)
                        }
                    },
                    icon = { Icon(if (d.type == "series") Icons.Default.LibraryAdd else Icons.Default.Download, null) },
                    text = {
                        Text(if (d.type == "series") "Add as Book (${d.chapters.size})" else "Download chapter (${d.images.size} pages)")
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun StartPage(hosts: List<String>, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Browse", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Open a manga's page, then tap “Add as Book” to pick chapters to download. " +
                "On a chapter page, tap “Download chapter”. You can also share links here from Firefox.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Supported sites", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        hosts.forEach { host ->
            Card(onClick = { onOpen("https://$host") }, modifier = Modifier.fillMaxWidth()) {
                Text(host, Modifier.padding(16.dp))
            }
        }
        Text(
            "Other sites usually work too, using automatic detection.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
