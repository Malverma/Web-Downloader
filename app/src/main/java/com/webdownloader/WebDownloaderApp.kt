package com.webdownloader

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.room.Room
import com.webdownloader.data.db.AppDatabase
import com.webdownloader.data.prefs.SettingsRepository
import com.webdownloader.data.repo.LibraryRepository
import com.webdownloader.data.storage.LibraryStorage
import com.webdownloader.download.ChapterDownloader
import com.webdownloader.download.DownloadScheduler
import com.webdownloader.extraction.BrowserHolder
import com.webdownloader.extraction.SiteRuleRepository
import com.webdownloader.extraction.WebViewExtractor
import com.webdownloader.net.Http
import com.webdownloader.reader.ReaderCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class WebDownloaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.downloads.resumeIfPending() }
    }
}

/** Manual dependency wiring for the whole app. */
class AppContainer(val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "webdownloader.db").build()
    val bookDao get() = db.bookDao()
    val chapterDao get() = db.chapterDao()

    val settings = SettingsRepository(app, json, appScope)
    val siteRules = SiteRuleRepository(app, json)
    val storage = LibraryStorage(app, settings)
    val readerCache = ReaderCache(app, storage)

    private val defaultUserAgent: String by lazy {
        // Drop the WebView marker; some sites serve a degraded page to it.
        WebSettings.getDefaultUserAgent(app).replace("; wv", "")
    }

    fun userAgent(): String = if (settings.state.value.desktopUserAgent) DESKTOP_UA else defaultUserAgent

    val http = Http(::userAgent)
    val extractor = WebViewExtractor(app, siteRules, json, ::configureWebView)
    val browser = BrowserHolder(app, extractor, ::configureWebView)
    val downloads = DownloadScheduler(app, settings, chapterDao)
    val library = LibraryRepository(this)
    val chapterDownloader = ChapterDownloader(this)

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebView(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = userAgent()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
