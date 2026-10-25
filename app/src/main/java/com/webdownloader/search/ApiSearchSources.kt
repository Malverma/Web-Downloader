package com.webdownloader.search

import com.webdownloader.net.Http
import com.webdownloader.sites.MangaDexSite
import com.webdownloader.sites.MangaFireBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

class MangaDexSource(private val http: Http, private val json: Json) : SearchSource {
    override val id = "mangadex"
    override val name = "MangaDex"

    private val mutex = Mutex()
    private var tags: Map<String, Pair<String, String>>? = null // key -> (name, uuid)
    private val chapterCounts = ConcurrentHashMap<String, Int>()
    // MangaDex allows about 5 requests a second.
    private val countLimit = Semaphore(2)

    private suspend fun api(pathAndQuery: String): JsonObject =
        json.parseToJsonElement(http.getText(MangaDexSite.API + pathAndQuery)).jsonObject

    private suspend fun tagMap(): Map<String, Pair<String, String>> = mutex.withLock {
        tags ?: api("/manga/tag").arr("data").mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val n = o.obj("attributes")?.obj("name")?.get("en").str() ?: return@mapNotNull null
            genreKey(n) to (n to o["id"].str().orEmpty())
        }.toMap().also { tags = it }
    }

    override suspend fun genres() = tagMap().values.map { it.first }

    private suspend fun chapterCount(mangaId: String): Int? {
        chapterCounts[mangaId]?.let { return it }
        return countLimit.withPermit {
            runCatching {
                api("/manga/$mangaId/aggregate?translatedLanguage[]=en").obj("volumes")?.values
                    ?.sumOf { v -> (v as? JsonObject)?.obj("chapters")?.size ?: 0 }
            }.getOrNull()?.also { chapterCounts[mangaId] = it }
        }
    }

    override suspend fun search(query: SearchQuery, page: Int): SearchPage = coroutineScope {
        val map = if (query.genres.isEmpty() && query.excluded.isEmpty()) emptyMap() else tagMap()
        val included = query.genres.map { map[it]?.second ?: return@coroutineScope SearchPage(emptyList(), false, "No such genre") }
        val excluded = query.excluded.mapNotNull { map[it]?.second }
        val q = buildString {
            append("/manga?limit=$PAGE_SIZE&offset=${(page - 1) * PAGE_SIZE}&includes[]=cover_art")
            append("&availableTranslatedLanguage[]=en&hasAvailableChapters=true")
            append("&contentRating[]=safe&contentRating[]=suggestive")
            if (query.text.isNotBlank()) append("&title=${enc(query.text.trim())}&order[relevance]=desc")
            else append("&order[followedCount]=desc")
            included.forEach { append("&includedTags[]=$it") }
            if (included.size > 1) append("&includedTagsMode=AND")
            excluded.forEach { append("&excludedTags[]=$it") }
            if (query.completedOnly) append("&status[]=completed")
        }
        val body = api(q)
        val list = body.arr("data").mapNotNull { it as? JsonObject }
        val ids = list.mapNotNull { it["id"].str() }
        val ratings = async {
            if (ids.isEmpty()) emptyMap() else runCatching {
                api("/statistics/manga?" + ids.joinToString("&") { "manga[]=$it" }).obj("statistics").orEmpty()
                    .mapValues { (_, v) -> (v as? JsonObject)?.obj("rating")?.get("bayesian")?.let { (it as? JsonPrimitive)?.floatOrNull } }
            }.getOrDefault(emptyMap())
        }
        val counts = list.map { m ->
            async {
                val last = m.obj("attributes")?.get("lastChapter").str()?.toDoubleOrNull()?.toInt()
                last ?: m["id"].str()?.let { chapterCount(it) }
            }
        }.awaitAll()
        val r = ratings.await()
        val items = list.mapIndexed { i, m ->
            val mid = m["id"].str().orEmpty()
            val a = m.obj("attributes")
            SearchItem(
                siteId = id,
                siteName = name,
                title = MangaDexSite.titleOf(m),
                url = "${MangaDexSite.SITE}/title/$mid",
                coverUrl = MangaDexSite.coverOf(m),
                rating = r[mid],
                chapters = counts[i],
                status = a?.get("status").str(),
                genres = a?.arr("tags")?.mapNotNull { t -> (t as? JsonObject)?.obj("attributes")?.obj("name")?.get("en").str()?.let(::genreKey) }?.toSet(),
            )
        }
        val total = body["total"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        SearchPage(items, hasMore = page * PAGE_SIZE < total)
    }

    companion object {
        private const val PAGE_SIZE = 24
    }
}

class MangaFireSource(private val bridge: MangaFireBridge, private val json: Json) : SearchSource {
    override val id = "mangafire"
    override val name = "MangaFire"

    private data class Details(val rating: Float?, val genres: Set<String>)

    private val mutex = Mutex()
    private var genreIds: Map<String, Pair<String, Int>>? = null // key -> (name, id)
    private val details = ConcurrentHashMap<String, Details>()
    private val detailLimit = Semaphore(6)

    private suspend fun genreMap(): Map<String, Pair<String, Int>> = mutex.withLock {
        genreIds ?: json.parseToJsonElement(bridge.get("/filter-options")).jsonObject.obj("data")?.arr("genres").orEmpty()
            .mapNotNull { g ->
                val o = g as? JsonObject ?: return@mapNotNull null
                val n = o["name"].str() ?: return@mapNotNull null
                val gid = (o["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                genreKey(n) to (n to gid)
            }.toMap().also { genreIds = it }
    }

    override suspend fun genres() = genreMap().values.map { it.first }

    /** Ratings and genres aren't in search results, so they're read from each title. */
    private suspend fun details(hid: String): Details? {
        details[hid]?.let { return it }
        return detailLimit.withPermit {
            runCatching {
                val d = json.parseToJsonElement(bridge.get("/titles/$hid")).jsonObject.obj("data")!!
                Details(
                    rating = (d["rating"] as? JsonPrimitive)?.doubleOrNull?.toFloat()?.takeIf { it > 0f },
                    genres = (d.arr("genres") + d.arr("themes")).mapNotNull { (it as? JsonObject)?.get("title").str()?.let(::genreKey) }.toSet(),
                )
            }.getOrNull()?.also { details[hid] = it }
        }
    }

    override suspend fun genresOf(url: String): Set<String>? =
        Regex("/title/([a-z0-9]+)-").find(url)?.groupValues?.get(1)?.let { details(it)?.genres }

    override suspend fun search(query: SearchQuery, page: Int): SearchPage = coroutineScope {
        val map = if (query.genres.isEmpty() && query.excluded.isEmpty()) emptyMap() else genreMap()
        val included = query.genres.map { map[it]?.second ?: return@coroutineScope SearchPage(emptyList(), false, "No such genre") }
        val excluded = query.excluded.mapNotNull { map[it]?.second }
        val params = buildJsonObject {
            if (query.text.isNotBlank()) put("keyword", query.text.trim())
            if (included.isNotEmpty()) putJsonArray("genres_in") { included.forEach { add(it) } }
            if (excluded.isNotEmpty()) putJsonArray("genres_ex") { excluded.forEach { add(it) } }
            if (included.size > 1) put("genres_mode", "and")
            if (query.completedOnly) putJsonArray("statuses") { add("finished") }
            putJsonArray("content_rating") { add("safe"); add("suggestive") }
            putJsonObject("order") { put(if (query.text.isNotBlank()) "relevance" else "views_30d", "desc") }
            put("page", page)
            put("limit", PAGE_SIZE)
        }
        val body = json.parseToJsonElement(bridge.get("/titles", params)).jsonObject
        val list = body.arr("items").mapNotNull { it as? JsonObject }
        val extra = list.map { m -> async { m["hid"].str()?.let { details(it) } } }.awaitAll()
        val items = list.mapIndexed { i, m ->
            SearchItem(
                siteId = id,
                siteName = name,
                title = m["title"].str().orEmpty(),
                url = MangaFireBridge.BASE + m["url"].str().orEmpty(),
                coverUrl = m.obj("poster")?.let { it["medium"].str() ?: it["large"].str() },
                rating = extra[i]?.rating,
                chapters = (m["latestChapter"] as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it > 0 },
                status = m["status"].str(),
                genres = extra[i]?.genres,
            )
        }
        val hasNext = body.obj("meta")?.get("hasNext").str() == "true"
        SearchPage(items, hasMore = hasNext)
    }

    companion object {
        private const val PAGE_SIZE = 30
    }
}
