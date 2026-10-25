package com.webdownloader.net

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("HTTP $code")

/** Shares cookies with the WebView so browser checks passed in Browse apply to downloads. */
class WebViewCookieJar : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

class Http(private val userAgent: () -> String) {
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun get(url: String, referer: String?): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code)
            resp.body?.bytes() ?: throw IOException("Empty response")
        }
    }
}
