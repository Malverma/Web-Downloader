package com.webdownloader.search

import android.net.Uri
import com.webdownloader.net.Http
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap

data class SearchItem(
    val siteId: String,
    val siteName: String,
    val title: String,
    val url: String,
    val coverUrl: String?,
    /** 0–10, or null when the site doesn't show ratings. */
    val rating: Float?,
    val chapters: Int?,
    val status: String?,
    /** Genre keys, when known; needed to apply excluded genres. */
    val genres: Set<String>? = null,
)

/** What to search for; [excluded] and [completedOnly] are sent to sites that support them. */
data class SearchQuery(
    val text: String,
    val genres: Set<String>,
    val excluded: Set<String> = emptySet(),
    val completedOnly: Boolean = false,
)

private val COMPLETED = setOf("completed", "complete", "finished")

/** True when a site's status text means the work has ended. */
fun isCompleted(status: String?) = status?.trim()?.lowercase() in COMPLETED

data class SearchPage(val items: List<SearchItem>, val hasMore: Boolean, val note: String? = null)

/** Genres are matched across sites by lowercase name. */
fun genreKey(name: String) = name.trim().lowercase()

interface SearchSource {
    val id: String
    val name: String

    /** Genre display names this site supports. */
    suspend fun genres(): List<String>

    suspend fun search(query: SearchQuery, page: Int): SearchPage

    /** Genre keys for one series (read from its page), or null if unavailable. */
    suspend fun genresOf(url: String): Set<String>? = null
}

private const val ASURA = "https://asurascans.com"
private const val ARENA = "https://arenascan.com"
private const val FLAME = "https://flamecomics.xyz"

private fun parseCount(text: String): Int? = Regex("(\\d[\\d,]*)").find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

class AsuraSource(private val http: Http) : SearchSource {
    override val id = "asura"
    override val name = "Asura Scans"

    private val mutex = Mutex()
    private var genreSlugs: Map<String, Pair<String, String>>? = null // key -> (name, slug)

    private suspend fun genreMap(): Map<String, Pair<String, String>> = mutex.withLock {
        genreSlugs ?: run {
            val html = http.getText("$ASURA/browse")
            // Astro serializes props as HTML-escaped JSON: {"id":[0,1],"name":[0,"Action"],"slug":[0,"action"]}
            val re = Regex("\\{&quot;id&quot;:\\[0,\\d+],&quot;name&quot;:\\[0,&quot;(.*?)&quot;],&quot;slug&quot;:\\[0,&quot;([a-z0-9-]+)&quot;]\\}")
            re.findAll(html)
                .map { Parser.unescapeEntities(it.groupValues[1], false) to it.groupValues[2] }
                .associate { (n, slug) -> genreKey(n) to (n to slug) }
                .also { genreSlugs = it }
        }
    }

    override suspend fun genres() = genreMap().values.map { it.first }

    override suspend fun search(query: SearchQuery, page: Int): SearchPage {
        val text = query.text
        val genres = query.genres
        val map = if (genres.isEmpty()) emptyMap() else genreMap()
        val slugs = genres.map { map[it]?.second ?: return SearchPage(emptyList(), false, "No such genre") }
        val url = Uri.parse("$ASURA/browse").buildUpon().apply {
            if (text.isNotBlank()) appendQueryParameter("search", text.trim())
            if (slugs.isNotEmpty()) appendQueryParameter("genres", slugs.joinToString(","))
            if (query.completedOnly) appendQueryParameter("status", "completed")
            if (page > 1) appendQueryParameter("page", page.toString())
        }.build().toString()
        val doc = Jsoup.parse(http.getText(url), ASURA)
        val items = doc.select("#series-grid [data-series-id]").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/comics/]") ?: return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim().orEmpty().ifEmpty { link.selectFirst("img")?.attr("alt").orEmpty() }
            val spans = card.select("span").map { it.ownText().trim() }.filter { it.isNotEmpty() }
            SearchItem(
                siteId = id,
                siteName = name,
                title = title,
                url = link.absUrl("href"),
                coverUrl = link.selectFirst("img")?.absUrl("src"),
                rating = link.select("span").firstNotNullOfOrNull { it.text().trim().toFloatOrNull() },
                chapters = card.select("span").firstOrNull { it.text().contains("Chs") || it.text().contains("Chapters") }?.let { parseCount(it.text()) },
                status = spans.firstOrNull { it.lowercase() in STATUSES },
            )
        }
        return SearchPage(items, hasMore = items.size >= 20)
    }

    override suspend fun genresOf(url: String): Set<String>? {
        val bySlug = genreMap().values.associate { (n, slug) -> slug to genreKey(n) }
        val html = http.getText(url)
        return Regex("href=\"/browse\\?genres=([a-z0-9-]+)\"").findAll(html)
            .map { bySlug[it.groupValues[1]] ?: it.groupValues[1].replace('-', ' ') }
            .toSet()
    }

    companion object {
        private val STATUSES = setOf("ongoing", "completed", "hiatus", "dropped", "season end", "coming soon")
    }
}

class ArenaSource(private val http: Http) : SearchSource {
    override val id = "arena"
    override val name = "Arena Scans"

    private val mutex = Mutex()
    private var genreIds: Map<String, Pair<String, List<String>>>? = null // key -> (name, ids)

    private suspend fun genreMap(): Map<String, Pair<String, List<String>>> = mutex.withLock {
        genreIds ?: run {
            val doc = Jsoup.parse(http.getText("$ARENA/manga/"), ARENA)
            doc.select("input[name=genre[]]").mapNotNull { input ->
                val idValue = input.attr("value")
                val label = doc.selectFirst("label[for=${input.id()}]")?.text()?.trim()
                if (idValue.isBlank() || label.isNullOrBlank()) null else label to idValue
            }
                .groupBy({ genreKey(it.first) }, { it })
                .mapValues { (_, list) -> list.first().first to list.map { it.second } }
                .also { genreIds = it }
        }
    }

    override suspend fun genres() = genreMap().values.map { it.first }

    override suspend fun search(query: SearchQuery, page: Int): SearchPage = coroutineScope {
        val genres = query.genres
        val map = if (genres.isEmpty()) emptyMap() else genreMap()
        val idLists = genres.map { map[it]?.second ?: return@coroutineScope SearchPage(emptyList(), false, "No such genre") }
        // Arena ANDs genre[] params, and some genres exist twice under different ids,
        // so query each combination of duplicates separately and merge.
        var combos = listOf(emptyList<String>())
        for (ids in idLists) combos = combos.flatMap { c -> ids.map { c + it } }
        combos = combos.take(4)
        val pages = combos.map { ids -> async { fetch(query.text, ids, query.completedOnly, page) } }.awaitAll()
        SearchPage(pages.flatMap { it.items }.distinctBy { it.url }, pages.any { it.hasMore })
    }

    override suspend fun genresOf(url: String): Set<String>? {
        val doc = Jsoup.parse(http.getText(url), ARENA)
        val links = doc.select(".mgen a, .seriestugenre a").ifEmpty { doc.select("a[href*=/genres/]") }
        return links.map { genreKey(it.text()) }.filter { it.isNotEmpty() }.toSet()
    }

    private suspend fun fetch(text: String, genreIds: List<String>, completedOnly: Boolean, page: Int): SearchPage {
        // Site search (?s=) ranks by relevance but can't filter genres; the /manga/ list
        // can, but its title filter matches any word, so those results are narrowed below.
        val siteSearch = text.isNotBlank() && genreIds.isEmpty() && !completedOnly
        val url = if (siteSearch) {
            Uri.parse(if (page > 1) "$ARENA/page/$page/" else "$ARENA/").buildUpon()
                .appendQueryParameter("s", text.trim()).build().toString()
        } else {
            Uri.parse("$ARENA/manga/").buildUpon().apply {
                if (text.isNotBlank()) appendQueryParameter("title", text.trim())
                genreIds.forEach { appendQueryParameter("genre[]", it) }
                if (completedOnly) appendQueryParameter("status", "completed")
                appendQueryParameter("order", "popular")
                if (page > 1) appendQueryParameter("page", page.toString())
            }.build().toString()
        }
        val doc = Jsoup.parse(http.getText(url), ARENA)
        val cards = doc.select(".bsx")
        val items = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            if (card.selectFirst(".type")?.className()?.contains("novel", ignoreCase = true) == true) return@mapNotNull null
            val img = card.selectFirst("img")
            SearchItem(
                siteId = id,
                siteName = name,
                title = card.selectFirst(".tt")?.text()?.trim().orEmpty().ifEmpty { link.attr("title") },
                url = link.absUrl("href"),
                coverUrl = img?.absUrl("data-src")?.ifEmpty { null } ?: img?.absUrl("src"),
                rating = card.selectFirst(".numscore")?.text()?.trim()?.toFloatOrNull(),
                chapters = card.selectFirst(".epxs")?.text()?.let(::parseCount),
                // Cards only carry a status badge for completed works.
                status = card.selectFirst(".status")?.text()?.trim()?.ifEmpty { null } ?: if (completedOnly) "Completed" else null,
            )
        }
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val kept = if (siteSearch || words.isEmpty()) items else items.filter { item -> words.all { item.title.lowercase().contains(it) } }
        return SearchPage(kept, hasMore = cards.size >= if (siteSearch) 10 else 20)
    }
}

class FlameSource(private val http: Http, private val json: Json) : SearchSource {
    override val id = "flame"
    override val name = "Flame Comics"

    private data class Series(
        val id: Int,
        val title: String,
        val categories: List<String>,
        val status: String?,
        val cover: String?,
    )

    private val mutex = Mutex()
    private var catalog: List<Series>? = null
    private var catalogAt = 0L
    private val chapterCounts = ConcurrentHashMap<Int, Int>()
    private val countLimit = Semaphore(4)

    private fun nextData(html: String): JsonObject? {
        val raw = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun JsonElement.props(): JsonObject? =
        runCatching { jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject }.getOrNull()

    private suspend fun catalog(): List<Series> = mutex.withLock {
        val cached = catalog
        if (cached != null && System.currentTimeMillis() - catalogAt < 30 * 60_000) return cached
        val props = nextData(http.getText("$FLAME/browse"))?.props() ?: error("Couldn't read Flame Comics catalog")
        val list = (props["series"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type.contains("novel", ignoreCase = true)) return@mapNotNull null
            val sid = o["series_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val cover = o["cover"]?.jsonPrimitive?.contentOrNull
            val edit = o["last_edit"]?.jsonPrimitive?.contentOrNull
            Series(
                id = sid,
                title = o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                categories = (o["categories"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                status = o["status"]?.jsonPrimitive?.contentOrNull,
                cover = cover?.let { "https://cdn.flamecomics.xyz/uploads/images/series/$sid/$it" + (edit?.let { e -> "?$e" } ?: "") },
            )
        }
        catalog = list
        catalogAt = System.currentTimeMillis()
        list
    }

    override suspend fun genres() = catalog().flatMap { it.categories }.distinctBy(::genreKey)

    private suspend fun chapterCount(seriesId: Int): Int? {
        chapterCounts[seriesId]?.let { return it }
        return countLimit.withPermit {
            runCatching {
                val props = nextData(http.getText("$FLAME/series/$seriesId"))?.props()
                (props?.get("chapters") as? JsonArray)?.size
            }.getOrNull()?.also { chapterCounts[seriesId] = it }
        }
    }

    override suspend fun search(query: SearchQuery, page: Int): SearchPage = coroutineScope {
        val q = query.text.trim().lowercase()
        val genres = query.genres
        val matches = catalog().filter { s ->
            (q.isEmpty() || s.title.lowercase().contains(q)) &&
                (!query.completedOnly || isCompleted(s.status)) &&
                genres.all { g -> s.categories.any { genreKey(it) == g } }
        }
        val pageItems = matches.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        // The catalog has no chapter counts, so read them from each series page.
        val counts = pageItems.map { s -> async { chapterCount(s.id) } }.awaitAll()
        val items = pageItems.mapIndexed { i, s ->
            SearchItem(
                id, name, s.title, "$FLAME/series/${s.id}", s.cover, rating = null, chapters = counts[i], status = s.status,
                genres = s.categories.map(::genreKey).toSet(),
            )
        }
        SearchPage(items, hasMore = matches.size > page * PAGE_SIZE)
    }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
