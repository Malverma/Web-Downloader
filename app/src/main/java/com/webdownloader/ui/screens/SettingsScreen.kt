package com.webdownloader.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.webdownloader.BuildConfig
import com.webdownloader.data.db.Quality
import com.webdownloader.data.prefs.AppSettings
import com.webdownloader.data.prefs.ReaderBackground
import com.webdownloader.ui.ChoiceDialog
import com.webdownloader.ui.ScreenScaffold
import com.webdownloader.ui.qualityDescription
import com.webdownloader.ui.rememberContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class SettingsDialog { QUALITY, BACKGROUND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val s by c.settings.state.collectAsState()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var editingRule by remember { mutableStateOf<String?>(null) }
    var rulesVersion by remember { mutableIntStateOf(0) }
    var rootLabel by remember { mutableStateOf("") }
    LaunchedEffect(s.rootTreeUri) { rootLabel = c.storage.describeRoot() }

    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            update { it.copy(rootTreeUri = uri.toString()) }
        }
    }

    ScreenScaffold(topBar = { TopAppBar(title = { Text("Settings") }) }, snackbarHost = snackbar) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { Section("Download quality") }
            item {
                ListItem(
                    headlineContent = { Text("Default quality") },
                    supportingContent = { Text(qualityDescription(s.defaultQuality)) },
                    modifier = Modifier.clickable { dialog = SettingsDialog.QUALITY },
                )
            }
            if (s.defaultQuality == Quality.CUSTOM) {
                item {
                    SliderSetting("Custom max width", s.customMaxWidth, 400..3000, 100, { "${it}px" }) { v -> update { it.copy(customMaxWidth = v) } }
                }
                item {
                    SliderSetting("Custom quality", s.customQuality, 40..100, 5, { "$it%" }) { v -> update { it.copy(customQuality = v) } }
                }
                item { SwitchSetting("Save as JPEG instead of WebP", null, s.customJpeg) { v -> update { it.copy(customJpeg = v) } } }
            }

            item { Section("Downloads") }
            item { SwitchSetting("Wi-Fi only", "Wait for an unmetered connection", s.wifiOnly) { v -> update { it.copy(wifiOnly = v) } } }
            item {
                SliderSetting("Images at once", s.imageConcurrency, 1..6, 1, { "$it" }) { v -> update { it.copy(imageConcurrency = v) } }
            }
            item {
                SliderSetting("Delay between images", s.requestDelayMs, 0..2000, 100, { "$it ms" }) { v -> update { it.copy(requestDelayMs = v) } }
            }

            item { Section("Reader") }
            item {
                SwitchSetting("Auto-continue", "Open the next downloaded chapter when you reach the end", s.autoContinue) { v ->
                    update { it.copy(autoContinue = v) }
                }
            }
            item { SwitchSetting("Keep screen on", null, s.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } } }
            item {
                ListItem(
                    headlineContent = { Text("Background") },
                    supportingContent = { Text(s.readerBackground.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.clickable { dialog = SettingsDialog.BACKGROUND },
                )
            }

            item { Section("Browser") }
            item {
                SwitchSetting("Desktop site", "Send a desktop user agent", s.desktopUserAgent) { v ->
                    scope.launch {
                        c.settings.update { it.copy(desktopUserAgent = v) }
                        c.browser.applySettings()
                    }
                }
            }

            item { Section("Storage") }
            item {
                ListItem(
                    headlineContent = { Text("Library folder") },
                    supportingContent = { Text(rootLabel) },
                    modifier = Modifier.clickable { folderPicker.launch(null) },
                )
            }
            if (s.rootTreeUri != null) {
                item {
                    ListItem(
                        headlineContent = { Text("Use app storage instead") },
                        supportingContent = { Text("Existing downloads stay where they are") },
                        modifier = Modifier.clickable { update { it.copy(rootTreeUri = null) } },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Clear reader cache") },
                    modifier = Modifier.clickable {
                        c.readerCache.clear()
                        scope.launch { snackbar.showSnackbar("Reader cache cleared") }
                    },
                )
            }

            item { Section("Sites") }
            val rules = c.siteRules.all().keys.sorted().also { rulesVersion.hashCode() }
            items(rules) { host ->
                ListItem(
                    headlineContent = { Text(host) },
                    supportingContent = { Text(if (c.siteRules.isUserOverride(host)) "Custom rule" else "Built-in rule") },
                    modifier = Modifier.clickable { editingRule = host },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Add site rule") },
                    supportingContent = { Text("For sites where automatic detection doesn't work") },
                    modifier = Modifier.clickable { editingRule = "" },
                )
            }

            item { Section("About") }
            item { ListItem(headlineContent = { Text("Web Downloader") }, supportingContent = { Text("Version ${BuildConfig.VERSION_NAME}") }) }
        }
    }

    when (dialog) {
        SettingsDialog.QUALITY -> ChoiceDialog(
            title = "Default quality",
            options = Quality.entries,
            selected = s.defaultQuality,
            label = ::qualityDescription,
            onDismiss = { dialog = null },
            onSelect = { q -> update { it.copy(defaultQuality = q) }; dialog = null },
        )
        SettingsDialog.BACKGROUND -> ChoiceDialog(
            title = "Reader background",
            options = ReaderBackground.entries,
            selected = s.readerBackground,
            label = { it.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } },
            onDismiss = { dialog = null },
            onSelect = { b -> update { it.copy(readerBackground = b) }; dialog = null },
        )
        null -> Unit
    }

    editingRule?.let { host ->
        RuleEditor(
            initial = if (host.isEmpty()) c.siteRules.template() else c.siteRules.text(host),
            canReset = host.isNotEmpty() && c.siteRules.isUserOverride(host),
            resetLabel = if (host.isNotEmpty() && c.siteRules.hasBundled(host)) "Restore built-in" else "Delete",
            onDismiss = { editingRule = null },
            onSave = { text ->
                runCatching { c.siteRules.save(text) }
                    .onSuccess { editingRule = null; rulesVersion++ }
                    .exceptionOrNull()?.message
            },
            onReset = {
                c.siteRules.reset(host)
                editingRule = null
                rulesVersion++
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SliderSetting(title: String, value: Int, range: IntRange, step: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local,
            onValueChange = { local = (it / step).roundToInt() * step.toFloat() },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun RuleEditor(
    initial: String,
    canReset: Boolean,
    resetLabel: String,
    onDismiss: () -> Unit,
    onSave: (String) -> String?,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Site rule") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    minLines = 10,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (canReset) TextButton(onClick = onReset) { Text(resetLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
