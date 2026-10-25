package com.webdownloader.extraction

import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Keeps one browser WebView alive across tab switches and runs page detection on it. */
class BrowserHolder(
    private val app: Context,
    private val extractor: WebViewExtractor,
    private val configure: (WebView) -> Unit,
) {
    val currentUrl = MutableStateFlow("")
    val progress = MutableStateFlow(0)
    val canGoBack = MutableStateFlow(false)
    val detection = MutableStateFlow<ExtractResult?>(null)

    /** Series data detected in the browser, handed to the chapter picker to skip a reload. */
    var lastSeries: ExtractResult? = null

    private val scope = MainScope()
    private var detectJob: Job? = null
    private var webView: WebView? = null
    private var pendingUrl: String? = null

    fun attach(activity: Context): WebView {
        val wv = webView ?: create().also { webView = it }
        (wv.context as MutableContextWrapper).baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        pendingUrl?.let {
            pendingUrl = null
            wv.loadUrl(it)
        }
        return wv
    }

    fun detach() {
        webView?.let { (it.context as MutableContextWrapper).baseContext = app }
    }

    private fun create(): WebView = WebView(MutableContextWrapper(app)).apply {
        configure(this)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                currentUrl.value = u.orEmpty()
                detection.value = null
                detectJob?.cancel()
            }

            override fun onPageFinished(view: WebView, u: String?) {
                currentUrl.value = u.orEmpty()
                canGoBack.value = view.canGoBack()
                scheduleDetect()
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                canGoBack.value = view.canGoBack()
                if (u != null && u != currentUrl.value) {
                    // Client-side navigation (single-page apps) doesn't fire onPageFinished.
                    currentUrl.value = u
                    detection.value = null
                    scheduleDetect()
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                this@BrowserHolder.progress.value = newProgress
            }
        }
    }

    private fun scheduleDetect() {
        detectJob?.cancel()
        val wv = webView ?: return
        detectJob = scope.launch {
            for (wait in listOf(800L, 1500L, 2500L, 4000L)) {
                delay(wait)
                val u = wv.url ?: return@launch
                val result = runCatching { extractor.evaluate(wv, u) }.getOrNull() ?: continue
                detection.value = result
            }
        }
    }

    fun load(input: String) {
        val u = normalize(input)
        val wv = webView
        if (wv == null) pendingUrl = u else wv.loadUrl(u)
    }

    fun goBack() { webView?.goBack() }
    fun goForward() { webView?.goForward() }
    fun reload() { webView?.reload() }
    fun applySettings() { webView?.let(configure) }

    companion object {
        fun normalize(input: String): String {
            val t = input.trim()
            if (t.startsWith("http://") || t.startsWith("https://")) return t
            if (!t.contains(' ') && t.contains('.')) return "https://$t"
            return "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
    }
}
