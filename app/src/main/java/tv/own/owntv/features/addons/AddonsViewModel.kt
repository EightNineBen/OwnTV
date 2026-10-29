package tv.own.owntv.features.addons

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.own.owntv.core.addons.AddonCatalog
import tv.own.owntv.core.addons.AddonRepository
import tv.own.owntv.core.addons.AddonStream
import tv.own.owntv.core.addons.AddonStreamsResult
import tv.own.owntv.core.addons.CatalogPage
import tv.own.owntv.core.addons.InstalledAddon
import tv.own.owntv.core.addons.MetaDetail
import tv.own.owntv.core.addons.MetaPreview
import tv.own.owntv.core.addons.MetaVideo
import tv.own.owntv.core.player.ExternalPlayerLauncher
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.player.OwnTVPlayer

/**
 * The Addons section: catalog rows from every installed addon, a title's details, and the streams the
 * addons offer for it. Playback goes through the same [OwnTVPlayer] as Movies and Series.
 */
class AddonsViewModel(
    private val repo: AddonRepository,
    private val player: OwnTVPlayer,
    private val settings: SettingsRepository,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
) : ViewModel() {

    val addons: StateFlow<List<InstalledAddon>> = repo.addons
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Catalog rows -------------------------------------------------------------------------

    data class Row(
        val key: String,
        val addon: InstalledAddon,
        val catalog: AddonCatalog,
        val items: List<MetaPreview> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val endReached: Boolean = false,
    )

    private data class RowContent(val items: List<MetaPreview>, val loading: Boolean, val loaded: Boolean, val endReached: Boolean)

    private val rowContent = MutableStateFlow<Map<String, RowContent>>(emptyMap())

    val rows: StateFlow<List<Row>> = combine(addons, rowContent) { list, content ->
        repo.browseCatalogs(list).map { (addon, catalog) ->
            val key = rowKey(addon, catalog)
            val c = content[key]
            Row(key, addon, catalog, c?.items.orEmpty(), c?.loading == true, c?.loaded == true, c?.endReached == true)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // New catalogs and versions since the last visit; failures keep what was stored.
        viewModelScope.launch { runCatching { repo.refreshManifests() } }
    }

    /** First page of a row, once. Called as the row comes on screen. */
    fun loadRow(row: Row) {
        if (row.loaded || row.loading) return
        fetchPage(row, skip = 0)
    }

    /** The next page, for a catalog that pages. Called when focus nears the end of the row. */
    fun loadMore(row: Row) {
        if (!row.loaded || row.loading || row.endReached || !row.catalog.pageable) return
        fetchPage(row, skip = row.items.size)
    }

    private fun fetchPage(row: Row, skip: Int) {
        rowContent.update { it + (row.key to RowContent(row.items, loading = true, loaded = row.loaded, endReached = row.endReached)) }
        viewModelScope.launch {
            val page = repo.catalogPage(row.addon, row.catalog, skip = skip)
            // Addons page by skip but some repeat their last page forever; drop what's already shown.
            val seen = row.items.mapTo(HashSet()) { it.id }
            val fresh = page.filter { it.id !in seen }
            rowContent.update {
                it + (row.key to RowContent(row.items + fresh, loading = false, loaded = true, endReached = fresh.isEmpty()))
            }
        }
    }

    private fun rowKey(addon: InstalledAddon, catalog: AddonCatalog) = addon.transportUrl + "\n" + catalog.type + "\n" + catalog.id

    // ---- Search -------------------------------------------------------------------------------

    data class Search(val query: String, val loading: Boolean, val results: List<CatalogPage>)

    private val _search = MutableStateFlow<Search?>(null)
    val search: StateFlow<Search?> = _search.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) { clearSearch(); return }
        searchJob?.cancel()
        _search.value = Search(q, loading = true, results = emptyList())
        searchJob = viewModelScope.launch {
            val results = repo.search(addons.value, q)
            _search.value = Search(q, loading = false, results = results)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    // ---- Details ------------------------------------------------------------------------------

    data class Detail(
        val preview: MetaPreview,
        val from: InstalledAddon?,
        val meta: MetaDetail? = null,
        val loading: Boolean = true,
        val season: Int? = null,
    ) {
        val isSeries: Boolean get() = meta?.videos?.isNotEmpty() == true
    }

    private val _detail = MutableStateFlow<Detail?>(null)
    val detail: StateFlow<Detail?> = _detail.asStateFlow()
    private var detailJob: Job? = null

    fun open(preview: MetaPreview, from: InstalledAddon?) {
        detailJob?.cancel()
        _detail.value = Detail(preview, from)
        detailJob = viewModelScope.launch {
            val meta = repo.meta(addons.value, preview.type, preview.id, from)
            _detail.value = Detail(preview, from, meta, loading = false, season = meta?.seasons?.firstOrNull { it > 0 } ?: meta?.seasons?.firstOrNull())
        }
    }

    fun selectSeason(season: Int) { _detail.update { it?.copy(season = season) } }

    fun closeDetail() {
        detailJob?.cancel()
        closeStreams()
        _detail.value = null
    }

    // ---- Streams ------------------------------------------------------------------------------

    data class Streams(
        val title: String,
        /** "S1 · E2 · Title" for an episode; null for a movie. */
        val episodeLabel: String?,
        val video: MetaVideo?,
        val results: List<AddonStreamsResult>,
    ) {
        val searching: Boolean get() = results.any { it.pending }
    }

    private val _streams = MutableStateFlow<Streams?>(null)
    val streams: StateFlow<Streams?> = _streams.asStateFlow()
    private var streamsJob: Job? = null

    /** Streams for the open title: the movie itself, or [video] (an episode) of a series. */
    fun findStreams(video: MetaVideo? = null, episodeLabel: String? = null) {
        val d = _detail.value ?: return
        val type = d.meta?.type ?: d.preview.type
        val videoId = video?.id ?: d.meta?.id ?: d.preview.id
        streamsJob?.cancel()
        _streams.value = Streams(d.meta?.preview?.name ?: d.preview.name, episodeLabel, video, emptyList())
        streamsJob = viewModelScope.launch {
            repo.streams(addons.value, type, videoId).collect { results ->
                _streams.update { it?.copy(results = results) }
            }
        }
    }

    fun closeStreams() {
        streamsJob?.cancel()
        _streams.value = null
    }

    /**
     * Plays [stream]. True when it started in the in-app player (the screen then goes full screen);
     * false when it went to an external player instead.
     */
    suspend fun play(stream: AddonStream): Boolean {
        val s = _streams.value
        val d = _detail.value
        val title = s?.title ?: d?.preview?.name
        val episode = s?.video
        // Headers the addon asked for. User-Agent travels on its own; the rest in M3U's "Key: Value" lines.
        val userAgent = stream.headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val headers = stream.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .entries.joinToString("\n") { (k, v) -> "$k: $v" }.ifEmpty { null }
        val external = if (episode != null) settings.externalPlayerSeries.first() else settings.externalPlayerMovies.first()
        if (external) {
            externalPlayerLauncher.launch(url = stream.url, title = title, subtitle = s?.episodeLabel, userAgent = userAgent, httpHeaders = headers)
            return false
        }
        Log.d(TAG, "play addon stream episode=${episode != null}")
        player.play(
            stream.url,
            title = title,
            subtitle = s?.episodeLabel,
            year = d?.meta?.preview?.releaseInfo ?: d?.preview?.releaseInfo,
            isLive = false,
            userAgent = userAgent,
            httpHeaders = headers,
            seasonNumber = episode?.season,
            episodeNumber = episode?.episode,
        )
        return true
    }

    // ---- Install: the browse page's one-press Cinemeta (everything else is Settings › Addons) ----------

    sealed interface InstallState {
        data object Working : InstallState
        data class Done(val result: AddonRepository.InstallResult) : InstallState
    }

    private val _install = MutableStateFlow<InstallState?>(null)
    val install: StateFlow<InstallState?> = _install.asStateFlow()

    fun install(url: String) {
        _install.value = InstallState.Working
        viewModelScope.launch { _install.value = InstallState.Done(repo.install(url)) }
    }

    fun installShown() { _install.value = null }

    companion object {
        private const val TAG = "AddonsViewModel"

        /** Stremio's own metadata addon: catalogs, details and episode lists for IMDb ids. Streams come from other addons. */
        const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"
    }
}
