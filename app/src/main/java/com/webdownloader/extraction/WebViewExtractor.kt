package com.webdownloader.extraction

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.webdownloader.sites.NativeSites
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Runs extract.js against fully rendered pages, either in the visible browser
 * or in a hidden WebView for background downloads.
 */
class WebViewExtractor(
    private val context: Context,
    private val rules: SiteRuleRepository,
    private val json: Json,
    private val natives: NativeSites,
    private val configure: (WebView) -> Unit,
) {
    enum class Mode { SERIES, CHAPTER }

    private companion object {
        val MEDIA_EXTENSIONS = listOf(".webp", ".jpg", ".jpeg", ".png", ".gif", ".avif", ".mp4", ".webm", ".woff", ".woff2", ".ttf")
    }

    @Serializable
    private data class ScrollState(val y: Double = 0.0, val max: Double = 0.0)

    private val script: String by lazy {
        context.assets.open("extract.js").bufferedReader().use { it.readText() }
    }

    private fun scriptFor(url: String): String {
        val rule = rules.forUrl(url) ?: SiteRule(host = "")
        return script.replace("__RULE__", json.encodeToString(SiteRule.serializer(), rule))
    }

    private suspend fun eval(webView: WebView, code: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont -> webView.evaluateJavascript(code) { cont.resume(it) } }
    }

    /** Analyzes whatever page [webView] currently shows. */
    suspend fun evaluate(webView: WebView, url: String): ExtractResult {
        if (natives.forUrl(url) != null) return runCatching { natives.extract(url) }.getOrNull() ?: ExtractResult(url = url)
        val raw = eval(webView, scriptFor(url))
        if (raw.isNullOrBlank() || raw == "null") return ExtractResult(url = url)
        return runCatching { json.decodeFromString(ExtractResult.serializer(), raw) }.getOrElse { ExtractResult(url = url) }
    }

    private suspend fun scrollStep(webView: WebView): ScrollState {
        val raw = eval(
            webView,
            """(function(){window.scrollBy(0, Math.max(1200, window.innerHeight * 2.5));
               var d = document.documentElement;
               return {y: window.scrollY, max: Math.max(d.scrollHeight, document.body ? document.body.scrollHeight : 0) - window.innerHeight};})()""",
        )
        return runCatching { json.decodeFromString(ScrollState.serializer(), raw ?: "{}") }.getOrElse { ScrollState() }
    }

    /** Loads [url] in a hidden WebView and waits until the series list or chapter images settle. */
    suspend fun extract(url: String, mode: Mode, timeoutMs: Long = 90_000): ExtractResult {
        // API-driven sites are read directly; no page load needed.
        natives.extract(url)?.let { return it }
        return loadAndExtract(url, mode, timeoutMs)
    }

    private suspend fun loadAndExtract(url: String, mode: Mode, timeoutMs: Long): ExtractResult = withContext(Dispatchers.Main) {
        val host = Uri.parse(url).host.orEmpty()
        val webView = WebView(context)
        configure(webView)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, 1080, 2340)
        val loaded = CompletableDeferred<Unit>()
        // With a rule that identifies page images by URL, the hidden WebView never needs
        // the image bytes, so skip downloading them (and fonts/media) to load much faster.
        val blockMedia = mode == Mode.CHAPTER && rules.forUrl(url)?.imageFilter?.urlContains != null
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                loaded.complete(Unit)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!blockMedia || request.isForMainFrame) return null
                val path = request.url.path.orEmpty().lowercase()
                return if (MEDIA_EXTENSIONS.any { path.endsWith(it) }) {
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                } else null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }
        }
        var last: ExtractResult? = null
        try {
            withTimeout(timeoutMs) {
                webView.loadUrl(url)
                // Don't wait for every ad and tracker: the DOM is usually ready well before onPageFinished.
                withTimeoutOrNull(6_000) { loaded.await() }
                var result = evaluate(webView, url)
                repeat(20) {
                    if (!result.challenge) return@repeat
                    delay(1500)
                    result = evaluate(webView, url)
                }
                last = result
                if (result.challenge) throw ChallengeException(host)
                result = if (mode == Mode.SERIES) settleSeries(webView, url, result) { last = it }
                else settleChapter(webView, url, result) { last = it }
                result
            }
        } catch (e: TimeoutCancellationException) {
            val r = last
            when {
                r?.challenge == true -> throw ChallengeException(host)
                mode == Mode.SERIES && r != null && r.chapters.isNotEmpty() -> r
                mode == Mode.CHAPTER && r != null && r.images.isNotEmpty() -> r
                else -> throw IOException("Timed out loading $url")
            }
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private suspend fun settleSeries(
        webView: WebView,
        url: String,
        first: ExtractResult,
        onResult: (ExtractResult) -> Unit,
    ): ExtractResult {
        var prev = first
        repeat(10) {
            delay(1000)
            val next = evaluate(webView, url)
            onResult(next)
            if (prev.chapters.isNotEmpty() && next.chapters.size == prev.chapters.size) return next
            prev = next
        }
        return prev
    }

    private suspend fun settleChapter(
        webView: WebView,
        url: String,
        first: ExtractResult,
        onResult: (ExtractResult) -> Unit,
    ): ExtractResult {
        // Most readers put every page in the DOM up front: if two quick checks agree, we're done.
        if (first.images.isNotEmpty()) {
            delay(700)
            val again = evaluate(webView, url)
            onResult(again)
            if (again.images.size == first.images.size) return again
        }
        var best = first
        var lastCount = -1
        var stable = 0
        repeat(240) { i ->
            val scroll = scrollStep(webView)
            delay(150)
            val atBottom = scroll.y >= scroll.max - 10
            if (atBottom || i % 3 == 2) {
                val now = evaluate(webView, url)
                if (now.images.size >= best.images.size) {
                    best = now
                    onResult(now)
                }
                if (atBottom && now.images.isNotEmpty() && now.images.size == lastCount) {
                    if (++stable >= 2) return best
                } else {
                    stable = 0
                }
                lastCount = now.images.size
            }
        }
        return best
    }
}
