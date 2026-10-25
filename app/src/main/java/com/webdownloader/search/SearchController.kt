package com.webdownloader.search

import com.webdownloader.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

enum class SearchSort(val label: String) {
    RELEVANCE("Relevance"),
    RATING("Rating"),
    CHAPTERS("Most chapters"),
    TITLE("Title"),
}

data class GenreOption(val key: String, val name: String, val siteIds: Set<String>)

data class SearchState(
    val text: String = "",
    /** Genres results must have (sent to the sites). */
    val genres: Set<String> = emptySet(),
    /** Genres results must not have (filtered in the app, since no site supports it). */
    val excludedGenres: Set<String> = emptySet(),
    /** True while genres of results are being looked up for the exclusion filter. */
    val checkingGenres: Boolean = false,
    val sites: Set<String> = emptySet(),
    val minRating: Float = 0f,
    val minChapters: Int = 0,
    val sort: SearchSort = SearchSort.RELEVANCE,
    val results: List<SearchItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
    val genreOptions: List<GenreOption> = emptyList(),
    val searched: Boolean = false,
) {
    /** Results after the rating/chapter filters and sort, applied locally. */
    val visible: List<SearchItem>
        get() = results
            .filter { item -> excludedGenres.isEmpty() || item.genres.orEmpty().none { it in excludedGenres } }
            .filter { minRating <= 0f || (it.rating ?: -1f) >= minRating }
            .filter { minChapters <= 0 || (it.chapters ?: -1) >= minChapters }
            .let { list ->
                when (sort) {
                    SearchSort.RELEVANCE -> relevanceOrder(list, text)
                    SearchSort.RATING -> list.sortedByDescending { it.rating ?: -1f }
                    SearchSort.CHAPTERS -> list.sortedByDescending { it.chapters ?: -1 }
                    SearchSort.TITLE -> list.sortedBy { it.title.lowercase() }
                }
            }
}

/** Whole-phrase title matches first, then titles containing every word, then the rest (stable). */
private fun relevanceOrder(list: List<SearchItem>, text: String): List<SearchItem> {
    val q = text.trim().lowercase()
    if (q.isEmpty()) return list
    val words = q.split(Regex("\\s+"))
    return list.sortedBy { item ->
        val t = item.title.lowercase()
        when {
            t == q -> 0
            t.contains(q) -> 1
            words.all { t.contains(it) } -> 2
            else -> 3
        }
    }
}

/** Searches Asura, Arena and Flame together; lives in the app container so results survive tab switches. */
class SearchController(http: Http, json: Json, private val scope: CoroutineScope) {
    val sources: List<SearchSource> = listOf(AsuraSource(http), ArenaSource(http), FlameSource(http, json))

    private val _state = MutableStateFlow(SearchState(sites = sources.map { it.id }.toSet()))
    val state: StateFlow<SearchState> = _state

    private val nextPage = mutableMapOf<String, Int>()
    private val more = mutableMapOf<String, Boolean>()
    private var job: Job? = null
    private var genresJob: Job? = null

    fun loadGenres() {
        if (_state.value.genreOptions.isNotEmpty() || genresJob?.isActive == true) return
        genresJob = scope.launch {
            val perSite = sources.map { s -> async { s.id to runCatching { s.genres() }.getOrDefault(emptyList()) } }.awaitAll()
            val options = mutableMapOf<String, GenreOption>()
            for ((siteId, names) in perSite) {
                for (n in names) {
                    val key = genreKey(n)
                    val existing = options[key]
                    options[key] = existing?.copy(siteIds = existing.siteIds + siteId) ?: GenreOption(key, n, setOf(siteId))
                }
            }
            _state.update { it.copy(genreOptions = options.values.sortedBy { o -> o.key }) }
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text) }
    fun setMinRating(v: Float) = _state.update { it.copy(minRating = v) }
    fun setMinChapters(v: Int) = _state.update { it.copy(minChapters = v) }
    fun setSort(v: SearchSort) = _state.update { it.copy(sort = v) }

    /** Cycles a genre: off → included → excluded → off. */
    fun toggleGenre(key: String) {
        val s = _state.value
        when (key) {
            in s.genres -> {
                _state.update { it.copy(genres = it.genres - key, excludedGenres = it.excludedGenres + key) }
                search()
            }
            in s.excludedGenres -> {
                // Exclusion is applied locally, so no new search is needed.
                _state.update { it.copy(excludedGenres = it.excludedGenres - key) }
            }
            else -> {
                _state.update { it.copy(genres = it.genres + key) }
                search()
            }
        }
    }

    fun clearGenres() {
        _state.update { it.copy(genres = emptySet(), excludedGenres = emptySet()) }
        search()
    }

    private val genreCache = ConcurrentHashMap<String, Set<String>>()
    private val genreLimit = Semaphore(6)
    private var enrichJob: Job? = null

    /** Looks up genres for results that lack them, so excluded genres can be filtered out. */
    private fun enrichGenres() {
        if (_state.value.excludedGenres.isEmpty() || enrichJob?.isActive == true) return
        enrichJob = scope.launch {
            while (true) {
                val missing = _state.value.results.filter { it.genres == null }
                if (missing.isEmpty() || _state.value.excludedGenres.isEmpty()) break
                _state.update { it.copy(checkingGenres = true) }
                val found = missing.map { item ->
                    async {
                        genreLimit.withPermit {
                            val src = sources.firstOrNull { it.id == item.siteId }
                            item.url to (genreCache[item.url] ?: runCatching { src?.genresOf(item.url) }.getOrNull() ?: emptySet())
                                .also { genreCache[item.url] = it }
                        }
                    }
                }.awaitAll().toMap()
                _state.update { st -> st.copy(results = st.results.map { r -> found[r.url]?.let { r.copy(genres = it) } ?: r }) }
            }
            _state.update { it.copy(checkingGenres = false) }
        }
    }

    fun toggleSite(id: String) {
        _state.update {
            val sites = if (id in it.sites) it.sites - id else it.sites + id
            it.copy(sites = sites.ifEmpty { it.sites })
        }
        search()
    }

    fun search() {
        job?.cancel()
        val s = _state.value
        _state.update { it.copy(loading = true, loadingMore = false, results = emptyList(), errors = emptyMap(), hasMore = false, searched = true) }
        nextPage.clear()
        more.clear()
        val active = sources.filter { it.id in s.sites }
        active.forEach { nextPage[it.id] = 1; more[it.id] = true }
        job = scope.launch { fetch(active, s.text, s.genres) }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        val active = sources.filter { it.id in s.sites && more[it.id] == true }
        _state.update { it.copy(loadingMore = true) }
        job = scope.launch { fetch(active, s.text, s.genres) }
    }

    private suspend fun fetch(active: List<SearchSource>, text: String, genres: Set<String>) {
        val pages = active.map { src ->
            scope.async {
                try {
                    src to Result.success(src.search(text, genres, nextPage[src.id] ?: 1))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    src to Result.failure(e)
                }
            }
        }.awaitAll()

        val errors = mutableMapOf<String, String>()
        val lists = mutableListOf<List<SearchItem>>()
        for ((src, result) in pages) {
            result.onSuccess { page ->
                nextPage[src.id] = (nextPage[src.id] ?: 1) + 1
                more[src.id] = page.hasMore
                lists += page.items
            }.onFailure { e ->
                more[src.id] = false
                errors[src.name] = e.message ?: e.javaClass.simpleName
            }
        }
        // Interleave sites so "Relevance" shows each site's top results first.
        val merged = buildList {
            val max = lists.maxOfOrNull { it.size } ?: 0
            for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(::add) }
        }
        _state.update { st ->
            st.copy(
                results = (st.results + merged.map { m -> genreCache[m.url]?.let { g -> m.copy(genres = m.genres ?: g) } ?: m })
                    .distinctBy { it.url },
                loading = false,
                loadingMore = false,
                hasMore = more.values.any { it },
                errors = st.errors + errors,
            )
        }
        enrichGenres()
    }
}
