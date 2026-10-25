package com.webdownloader.extraction

import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    private val configure: (WebView) -> Unit,
) {
    enum class Mode { SERIES, CHAPTER }

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
        val raw = eval(webView, scriptFor(url))
        if (raw.isNullOrBlank() || raw == "null") return ExtractResult(url = url)
        return runCatching { json.decodeFromString(ExtractResult.serializer(), raw) }.getOrElse { ExtractResult(url = url) }
    }

    private suspend fun scrollStep(webView: WebView): ScrollState {
        val raw = eval(
            webView,
            """(function(){window.scrollBy(0, Math.max(400, window.innerHeight * 0.9));
               var d = document.documentElement;
               return {y: window.scrollY, max: Math.max(d.scrollHeight, document.body ? document.body.scrollHeight : 0) - window.innerHeight};})()""",
        )
        return runCatching { json.decodeFromString(ScrollState.serializer(), raw ?: "{}") }.getOrElse { ScrollState() }
    }

    /** Loads [url] in a hidden WebView and waits until the series list or chapter images settle. */
    suspend fun extract(url: String, mode: Mode, timeoutMs: Long = 90_000): ExtractResult = withContext(Dispatchers.Main) {
        val host = Uri.parse(url).host.orEmpty()
        val webView = WebView(context)
        configure(webView)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, 1080, 2340)
        val loaded = CompletableDeferred<Unit>()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                loaded.complete(Unit)
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
                loaded.await()
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
        var best = first
        var lastCount = -1
        var stable = 0
        delay(800)
        repeat(240) { i ->
            val scroll = scrollStep(webView)
            delay(300)
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
