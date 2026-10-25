package com.webdownloader.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.webdownloader.data.db.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ReaderBackground { BLACK, GRAY, WHITE }

@Serializable
data class AppSettings(
    val defaultQuality: Quality = Quality.MEDIUM,
    val customMaxWidth: Int = 1200,
    val customQuality: Int = 80,
    val customJpeg: Boolean = false,
    val rootTreeUri: String? = null,
    val wifiOnly: Boolean = false,
    val imageConcurrency: Int = 4,
    val chapterConcurrency: Int = 2,
    val requestDelayMs: Int = 150,
    val autoContinue: Boolean = false,
    val keepScreenOn: Boolean = true,
    val readerBackground: ReaderBackground = ReaderBackground.BLACK,
    val desktopUserAgent: Boolean = false,
    val downloadsPaused: Boolean = false,
    val pauseReason: String? = null,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context, private val json: Json, scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings_json")

    private fun decode(raw: String?): AppSettings =
        raw?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()

    val flow: Flow<AppSettings> = context.dataStore.data.map { decode(it[key]) }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[key] = json.encodeToString(AppSettings.serializer(), transform(decode(prefs[key])))
        }
    }
}
