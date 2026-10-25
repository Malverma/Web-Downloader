package com.webdownloader.sites

import android.net.Uri
import androidx.core.text.HtmlCompat
import com.webdownloader.extraction.ExtractResult
import com.webdownloader.extraction.ExtractedChapter
import com.webdownloader.extraction.ExtractedImage
import com.webdownloader.net.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Sites whose pages are rendered entirely by JavaScript from an API. Their chapter
 * lists and page images come straight from that API instead of the page DOM.
 */
interface NativeSite {
    fun handles(url: String): Boolean

    /** A "series" result for a title URL or a "chapter" result for a chapter URL. */
    suspend fun extract(url: String): ExtractResult
}

/** Routes a URL to the [NativeSite] that handles it, caching results briefly (the browser asks often). */
class NativeSites(val sites: List<NativeSite>) {
    private data class Cached(val at: Long, val result: ExtractResult)
    private val cache = ConcurrentHashMap<String, Cached>()

    fun forUrl(url: String): NativeSite? = sites.firstOrNull { it.handles(url) }

    suspend fun extract(url: String): ExtractResult? {
        val site = forUrl(url) ?: return null
        cache[url]?.takeIf { System.currentTimeMillis() - it.at < 60_000 }?.let { return it.result }
        return site.extract(url).also { cache[url] = Cached(System.currentTimeMillis(), it) }
    }
}

internal fun chapterLabel(number: Double?, name: String?): String {
    val n = number?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
    val base = if (n != null) "Chapter $n" else name?.takeIf { it.isNotBlank() } ?: "Oneshot"
    val extra = name?.trim()?.takeIf { it.isNotEmpty() && n != null }
    return if (extra != null) "$base: $extra" else base
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Turns an HTML or Markdown synopsis into plain text. */
internal fun plainDescription(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    // MangaDex appends a "---" separated block of links and credits.
    val main = raw.substringBefore("\n---")
    val text = HtmlCompat.fromHtml(main.replace("\n", "<br>"), HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
    return text
        .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_]{2,}"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .ifEmpty { null }
}

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

class MangaFireSite(private val bridge: MangaFireBridge, private val json: Json) : NativeSite {
    override fun handles(url: String) = Uri.parse(url).host?.removePrefix("www.") == "mangafire.to"

    override suspend fun extract(url: String): ExtractResult {
        val path = Uri.parse(url).pathSegments
        // /title/{hid}-{slug}[/chapter/{id}]
        if (path.size < 2 || path[0] != "title") return ExtractResult(url = url)
        val hid = path[1].substringBefore('-')
        val seriesUrl = "${MangaFireBridge.BASE}/title/${path[1]}"
        if (path.size >= 4 && path[2] == "chapter") {
            val data = json.parseToJsonElement(bridge.get("/chapters/${path[3]}")).jsonObject.obj("data")
                ?: throw IOException("MangaFire chapter not found")
            val images = data.arr("pages").mapIndexedNotNull { i, p ->
                (p as? JsonObject)?.get("url").str()?.let { ExtractedImage(it, i + 1) }
            }
            return ExtractResult(type = "chapter", title = data.obj("title")?.get("name").str(), url = url, seriesUrl = seriesUrl, images = images)
        }
        val title = json.parseToJsonElement(bridge.get("/titles/$hid")).jsonObject.obj("data")
            ?: throw IOException("MangaFire title not found")
        val raw = mutableListOf<JsonObject>()
        var page = 1
        while (page <= 50) {
            val params = buildJsonObject {
                put("language", "en"); put("sort", "number"); put("order", "asc"); put("page", page); put("limit", 100)
            }
            val body = json.parseToJsonElement(bridge.get("/titles/$hid/chapters", params)).jsonObject
            body.arr("items").forEach { (it as? JsonObject)?.let(raw::add) }
            if (body.obj("meta")?.get("hasNext")?.jsonPrimitive?.contentOrNull != "true") break
            page++
        }
        // The same chapter is often listed twice (official and fan translations); keep one, preferring official.
        val chapters = raw
            .groupBy { it["number"]?.jsonPrimitive?.doubleOrNull }
            .map { (_, list) -> list.firstOrNull { it["type"].str() == "official" } ?: list.first() }
            .sortedBy { it["number"]?.jsonPrimitive?.doubleOrNull ?: Double.MAX_VALUE }
            .map { c ->
                val number = c["number"]?.jsonPrimitive?.doubleOrNull
                ExtractedChapter("$seriesUrl/chapter/${c["id"].str()}", chapterLabel(number, c["name"].str()), number)
            }
        return ExtractResult(
            type = "series",
            title = title["title"].str(),
            cover = title.obj("poster")?.let { it["large"].str() ?: it["medium"].str() },
            description = plainDescription(title["description"].str() ?: title["synopsis"].str()),
            url = seriesUrl,
            chapters = chapters,
        )
    }
}

class MangaDexSite(private val http: Http, private val json: Json) : NativeSite {
    override fun handles(url: String) = Uri.parse(url).host?.removePrefix("www.") == "mangadex.org"

    private suspend fun api(pathAndQuery: String): JsonObject = json.parseToJsonElement(http.getText("$API$pathAndQuery")).jsonObject

    override suspend fun extract(url: String): ExtractResult {
        val path = Uri.parse(url).pathSegments
        if (path.size < 2) return ExtractResult(url = url)
        return when (path[0]) {
            "chapter" -> chapter(url, path[1])
            "title" -> series(path[1])
            else -> ExtractResult(url = url)
        }
    }

    private suspend fun chapter(url: String, id: String): ExtractResult {
        val home = api("/at-home/server/$id")
        val base = home["baseUrl"].str() ?: throw IOException("MangaDex image server unavailable")
        val ch = home.obj("chapter") ?: throw IOException("MangaDex chapter not found")
        val hash = ch["hash"].str()
        val images = ch.arr("data").mapIndexed { i, f -> ExtractedImage("$base/data/$hash/${f.str()}", i + 1) }
        return ExtractResult(type = "chapter", url = url, images = images)
    }

    private suspend fun series(id: String): ExtractResult {
        val manga = api("/manga/$id?includes[]=cover_art").obj("data") ?: throw IOException("MangaDex title not found")
        val raw = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < 5000) {
            val page = api(
                "/manga/$id/feed?translatedLanguage[]=en&order[chapter]=asc&limit=500&offset=$offset" +
                    "&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica",
            )
            page.arr("data").forEach { (it as? JsonObject)?.let(raw::add) }
            val total = page["total"]?.jsonPrimitive?.intOrNull ?: 0
            offset += 500
            if (offset >= total) break
        }
        // Several groups often translate the same chapter; keep the version with the most pages.
        // Chapters hosted elsewhere (official links) have no pages here and are skipped.
        val chapters = raw
            .filter { (it.obj("attributes")?.get("pages")?.jsonPrimitive?.intOrNull ?: 0) > 0 && it.obj("attributes")?.get("externalUrl").str() == null }
            .groupBy { it.obj("attributes")?.get("chapter").str() }
            .map { (_, list) -> list.maxBy { it.obj("attributes")?.get("pages")?.jsonPrimitive?.intOrNull ?: 0 } }
            .sortedBy { it.obj("attributes")?.get("chapter").str()?.toDoubleOrNull() ?: Double.MAX_VALUE }
            .map { c ->
                val a = c.obj("attributes")!!
                val number = a["chapter"].str()?.toDoubleOrNull()
                ExtractedChapter("$SITE/chapter/${c["id"].str()}", chapterLabel(number, a["title"].str()), number)
            }
        if (chapters.isEmpty() && raw.isNotEmpty()) {
            throw IOException("On MangaDex this title only links to official sites, so there's nothing to download here. Try it on another site.")
        }
        return ExtractResult(
            type = "series",
            title = titleOf(manga),
            cover = coverOf(manga),
            description = plainDescription(manga.obj("attributes")?.obj("description")?.let { it["en"].str() ?: it.values.firstOrNull().str() }),
            url = "$SITE/title/$id",
            chapters = chapters,
        )
    }

    companion object {
        const val API = "https://api.mangadex.org"
        const val SITE = "https://mangadex.org"

        fun titleOf(manga: JsonObject): String {
            val a = manga.obj("attributes") ?: return ""
            val titles = a.obj("title")
            titles?.get("en").str()?.let { return it }
            a.arr("altTitles").firstNotNullOfOrNull { (it as? JsonObject)?.get("en").str() }?.let { return it }
            return titles?.values?.firstOrNull().str().orEmpty()
        }

        fun coverOf(manga: JsonObject): String? {
            val file = manga.arr("relationships").firstOrNull { (it as? JsonObject)?.get("type").str() == "cover_art" }
                ?.jsonObject?.obj("attributes")?.get("fileName").str() ?: return null
            return "https://uploads.mangadex.org/covers/${manga["id"].str()}/$file.512.jpg"
        }
    }
}
