package com.webdownloader.sites

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.webdownloader.net.HttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MangaFire's API only answers requests signed by the site's own (obfuscated) script, so a
 * hidden WebView stays parked on mangafire.to and makes the calls from inside the page.
 */
class MangaFireBridge(private val context: Context, private val configure: (WebView) -> Unit) {
    private var webView: WebView? = null
    private val ready = Mutex()
    private val nextId = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    private inner class Callback {
        @JavascriptInterface
        fun result(id: String, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun page(): WebView = ready.withLock {
        webView?.let { wv ->
            if (eval(wv, "typeof getProtectionToken === 'function'") == "true") return@withLock wv
            wv.destroy()
            webView = null
        }
        withContext(Dispatchers.Main) {
            val wv = WebView(context)
            configure(wv)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, 1080, 2340)
            wv.addJavascriptInterface(Callback(), "WDBridge")
            wv.webViewClient = WebViewClient()
            wv.loadUrl("$BASE/filter")
            webView = wv
            withTimeout(30_000) {
                while (eval(wv, "typeof getProtectionToken === 'function' && typeof canonicalQuery === 'function'") != "true") {
                    if (eval(wv, "/just a moment|attention required/i.test(document.title)") == "true") {
                        throw IOException("mangafire.to is showing a browser check. Open it once in the in-app browser, then try again.")
                    }
                    delay(300)
                }
            }
            wv
        }
    }

    private suspend fun eval(wv: WebView, code: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        wv.evaluateJavascript(code) { result.complete(it) }
        result.await()
    }

    /** GETs `/api[path]` with [params] (sent the way the site's own client sends them) and returns the JSON text. */
    suspend fun get(path: String, params: JsonObject = JsonObject(emptyMap())): String {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            val wv = page()
            val id = "r${nextId.incrementAndGet()}"
            val deferred = CompletableDeferred<Pair<Int, String>>()
            pending[id] = deferred
            val p = params.toString()
            eval(
                wv,
                """(async () => {
                  try {
                    const p = $p, path = ${quote(path)};
                    const q = canonicalQuery(p);
                    const v = getProtectionToken(path, p);
                    const r = await fetch('/api' + path + '?' + (q ? q + '&' : '') + 'vrf=' + encodeURIComponent(v),
                      { headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' } });
                    WDBridge.result('$id', r.status, await r.text());
                  } catch (e) { WDBridge.result('$id', -1, String(e)); }
                })(); 0""",
            )
            val (status, body) = try {
                withTimeout(20_000) { deferred.await() }
            } finally {
                pending.remove(id)
            }
            when {
                status in 200..299 -> return body
                status == -1 || status == 403 -> {
                    // The page lost its state (or its token expired); reload it once and retry.
                    lastError = IOException("MangaFire request failed: $body")
                    if (attempt == 0) withContext(Dispatchers.Main) { webView?.destroy(); webView = null }
                }
                else -> throw HttpException(status)
            }
        }
        throw lastError ?: IOException("MangaFire request failed")
    }

    private fun quote(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        const val BASE = "https://mangafire.to"
    }
}
