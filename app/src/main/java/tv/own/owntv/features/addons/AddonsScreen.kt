package tv.own.owntv.features.addons

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.addons.AddonRepository
import tv.own.owntv.core.addons.AddonStream
import tv.own.owntv.core.addons.InstalledAddon
import tv.own.owntv.core.addons.MetaPreview
import tv.own.owntv.core.addons.MetaVideo
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.OwnTVSpinner
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.stage.StageButton
import tv.own.owntv.ui.stage.StageEpisode
import tv.own.owntv.ui.stage.StagePoster
import tv.own.owntv.ui.stage.StageRow
import tv.own.owntv.ui.stage.StageTab
import tv.own.owntv.ui.stage.StageTool
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.mpxSp
import tv.own.owntv.ui.theme.stageAccent
import tv.own.owntv.ui.theme.stageText

/**
 * Addons: rows from every installed addon's catalogs, a title's page, and the streams for it — the
 * equivalent of Stremio's Board and Discover. Three layers, each Back one step out: streams → title →
 * rows. Installing and ordering addons is Settings › Addons, which [onManage] opens.
 */
@Composable
fun AddonsScreen(
    /** A stream started in the in-app player: the shell goes full screen. */
    onPlayStarted: () -> Unit,
    /** Settings › Addons, where addons are installed, ordered and removed. */
    onManage: () -> Unit,
    onChildFocused: () -> Unit,
    onEntryHook: (((() -> Boolean)?) -> Unit)? = null,
    /** The content's left edge: beside the resting capsule (150), or 64 inside a docked rail's reserve. */
    contentStart: Dp = 150.mpx,
    modifier: Modifier = Modifier,
    vm: AddonsViewModel = koinViewModel(),
) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val streams by vm.streams.collectAsStateWithLifecycle()
    val install by vm.install.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var askSearch by remember { mutableStateOf(false) }

    val headerFocus = remember { FocusRequester() }
    val firstItemFocus = remember { FocusRequester() }
    // The tile (or episode) that was opened, so Back lands on it again.
    val returnFocus = remember { FocusRequester() }
    var openedKey by remember { mutableStateOf<String?>(null) }
    var openedEpisodeId by remember { mutableStateOf<String?>(null) }

    val entry: () -> Boolean = {
        runCatching { firstItemFocus.requestFocus() }.getOrDefault(false) ||
            runCatching { headerFocus.requestFocus() }.getOrDefault(false)
    }
    DisposableEffect(onEntryHook) {
        onEntryHook?.invoke(entry)
        onDispose { onEntryHook?.invoke(null) }
    }

    BackHandler(enabled = streams != null) { vm.closeStreams() }
    BackHandler(enabled = streams == null && detail != null) { vm.closeDetail() }
    BackHandler(enabled = detail == null && search != null) { vm.clearSearch() }

    // Back out of a layer: focus returns to what opened it.
    val layer = when {
        streams != null -> 3
        detail != null -> 2
        else -> 1
    }
    var lastLayer by remember { mutableStateOf(layer) }
    // Back to the rows: focus the tile that was opened. The title page places its own focus.
    LaunchedEffect(layer) {
        val wentBack = layer < lastLayer
        lastLayer = layer
        if (!wentBack || layer != 1) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!runCatching { returnFocus.requestFocus() }.getOrDefault(false)) entry()
    }

    // Starting or clearing a search removes what had focus: start again at the top.
    val searching = search != null
    var lastSearching by remember { mutableStateOf(searching) }
    LaunchedEffect(searching) {
        val changed = searching != lastSearching
        lastSearching = searching
        if (!changed || layer != 1) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        entry()
    }

    Box(modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) onChildFocused() }) {
        val d = detail
        val s = streams
        when {
            s != null && d != null -> StreamsPage(
                streams = s,
                contentStart = contentStart,
                onPlay = { stream -> scope.launch { if (vm.play(stream)) onPlayStarted() } },
            )
            d != null -> DetailPage(
                detail = d,
                contentStart = contentStart,
                openedEpisodeId = openedEpisodeId,
                returnFocus = returnFocus,
                onSeason = vm::selectSeason,
                onFindStreams = { video, label ->
                    openedEpisodeId = video?.id
                    vm.findStreams(video, label)
                },
            )
            else -> BrowsePage(
                vm = vm,
                rows = rows,
                search = search,
                hasAddons = addons.isNotEmpty(),
                install = install,
                contentStart = contentStart,
                headerFocus = headerFocus,
                firstItemFocus = firstItemFocus,
                returnFocus = returnFocus,
                openedKey = openedKey,
                onSearch = { askSearch = true },
                onManage = onManage,
                onAddCinemeta = { vm.install(AddonsViewModel.CINEMETA_URL) },
                onAdd = onManage,
                onOpen = { key, preview, addon ->
                    openedKey = key
                    openedEpisodeId = null
                    vm.open(preview, addon)
                },
            )
        }
    }

    if (askSearch) {
        TextInputDialog(
            title = stringResource(R.string.addons_search_title),
            initial = search?.query.orEmpty(),
            label = stringResource(R.string.addons_search_label),
            confirmLabel = stringResource(R.string.addons_search),
            allowBlank = false,
            onConfirm = { q -> askSearch = false; vm.search(q) },
            onDismiss = { askSearch = false },
        )
    }
    // The install outcome stays on screen for a few seconds.
    LaunchedEffect(install) {
        if (install is AddonsViewModel.InstallState.Done) {
            kotlinx.coroutines.delay(INSTALL_MESSAGE_MS)
            vm.installShown()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Browse: catalog rows, or search results
// ---------------------------------------------------------------------------------------------

@Composable
private fun BrowsePage(
    vm: AddonsViewModel,
    rows: List<AddonsViewModel.Row>,
    search: AddonsViewModel.Search?,
    hasAddons: Boolean,
    install: AddonsViewModel.InstallState?,
    contentStart: Dp,
    headerFocus: FocusRequester,
    firstItemFocus: FocusRequester,
    returnFocus: FocusRequester,
    openedKey: String?,
    onSearch: () -> Unit,
    onManage: () -> Unit,
    onAddCinemeta: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (key: String, MetaPreview, InstalledAddon) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 52.mpx, bottom = 80.mpx),
        verticalArrangement = Arrangement.spacedBy(34.mpx),
    ) {
        item(key = "head") {
            Column(Modifier.padding(start = contentStart + 20.mpx, end = 64.mpx)) {
                Row(horizontalArrangement = Arrangement.spacedBy(22.mpx), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.addons_nav), style = stageText(46, 800, (-1).mpxSp), color = StageColors.Text, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (hasAddons) {
                        StageTool(stringResource(R.string.addons_search), onClick = onSearch, icon = OwnTVIcon.SEARCH, boxed = true, modifier = Modifier.focusRequester(headerFocus))
                    }
                    StageTool(
                        stringResource(R.string.addons_manage), onClick = onManage, icon = OwnTVIcon.GEAR, boxed = true,
                        modifier = if (hasAddons) Modifier else Modifier.focusRequester(headerFocus),
                    )
                }
                InstallMessage(install)
                if (search != null) {
                    Spacer(Modifier.height(18.mpx))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.mpx), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.addons_search_results, search.query), style = stageText(26, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StageTool(stringResource(R.string.addons_search_clear), onClick = vm::clearSearch, icon = OwnTVIcon.CLOSE)
                    }
                }
            }
        }

        when {
            !hasAddons -> item(key = "empty") {
                Column(Modifier.padding(start = contentStart + 20.mpx), verticalArrangement = Arrangement.spacedBy(22.mpx)) {
                    Text(stringResource(R.string.addons_empty), style = stageText(22, 500), color = StageColors.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.mpx)) {
                        StageButton(stringResource(R.string.addons_add_cinemeta), onClick = onAddCinemeta, icon = OwnTVIcon.ADD, modifier = Modifier.focusRequester(firstItemFocus))
                        StageButton(stringResource(R.string.addons_add), onClick = onAdd, icon = OwnTVIcon.ADD)
                    }
                }
            }
            search != null -> {
                if (search.loading) {
                    item(key = "searching") { Busy(stringResource(R.string.addons_searching), Modifier.padding(start = contentStart + 20.mpx)) }
                } else if (search.results.isEmpty()) {
                    item(key = "none") {
                        Text(stringResource(R.string.addons_search_empty, search.query), style = stageText(22, 500), color = StageColors.Muted, modifier = Modifier.padding(start = contentStart + 20.mpx))
                    }
                }
                itemsIndexed(search.results, key = { _, p -> "search\n" + p.addon.transportUrl + "\n" + p.catalog.type + "\n" + p.catalog.id }) { index, p ->
                    val rowKey = "search\n" + p.addon.transportUrl + "\n" + p.catalog.type + "\n" + p.catalog.id
                    PosterRow(
                        title = stringResource(R.string.addons_catalog_row, p.catalog.name ?: p.catalog.id, p.addon.manifest.name),
                        items = p.items,
                        loading = false,
                        contentStart = contentStart,
                        firstFocus = firstItemFocus.takeIf { index == 0 },
                        returnFocus = returnFocus,
                        keyOf = { rowKey + "\n" + it.id },
                        openedKey = openedKey,
                        onNearEnd = {},
                        onOpen = { key, item -> onOpen(key, item, p.addon) },
                    )
                }
            }
            rows.isEmpty() -> item(key = "nocatalogs") {
                Text(stringResource(R.string.addons_no_catalogs), style = stageText(22, 500), color = StageColors.Muted, modifier = Modifier.padding(start = contentStart + 20.mpx))
            }
            else -> itemsIndexed(rows, key = { _, r -> r.key }) { index, row ->
                LaunchedEffect(row.key) { vm.loadRow(row) }
                // Rows that came back empty are dropped rather than shown as a dead strip.
                if (row.loaded && row.items.isEmpty()) return@itemsIndexed
                PosterRow(
                    title = stringResource(R.string.addons_catalog_row, row.catalog.name ?: row.catalog.id, row.addon.manifest.name),
                    items = row.items,
                    loading = !row.loaded,
                    contentStart = contentStart,
                    firstFocus = firstItemFocus.takeIf { index == 0 },
                    returnFocus = returnFocus,
                    keyOf = { row.key + "\n" + it.id },
                    openedKey = openedKey,
                    onNearEnd = { vm.loadMore(row) },
                    onOpen = { key, item -> onOpen(key, item, row.addon) },
                )
            }
        }
    }
}

@Composable
private fun PosterRow(
    title: String,
    items: List<MetaPreview>,
    loading: Boolean,
    contentStart: Dp,
    firstFocus: FocusRequester?,
    returnFocus: FocusRequester,
    keyOf: (MetaPreview) -> String,
    openedKey: String?,
    onNearEnd: () -> Unit,
    onOpen: (key: String, MetaPreview) -> Unit,
) {
    Column {
        Text(
            title, style = stageText(24, 700), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = contentStart + 20.mpx, bottom = 16.mpx),
        )
        if (loading) {
            Busy(null, Modifier.padding(start = contentStart + 20.mpx).height(POSTER_H))
            return@Column
        }
        // A catalog's shape is set per item, but a row of mixed shapes reads badly: the first item decides.
        val landscape = items.firstOrNull()?.posterShape == "landscape"
        val w = if (landscape) 348.mpx else POSTER_W
        val h = if (landscape) 196.mpx else POSTER_H
        LazyRow(
            Modifier.focusGroup(),
            contentPadding = PaddingValues(start = contentStart + 20.mpx, end = 64.mpx, top = 14.mpx, bottom = 14.mpx),
            horizontalArrangement = Arrangement.spacedBy(28.mpx),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                val key = keyOf(item)
                StagePoster(
                    title = item.name,
                    onClick = { onOpen(key, item) },
                    width = w,
                    height = h,
                    rating = item.imdbRating,
                    modifier = Modifier
                        .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                        .then(if (key == openedKey) Modifier.focusRequester(returnFocus) else Modifier)
                        .onFocusChanged { if (it.hasFocus && index >= items.size - NEAR_END) onNearEnd() },
                ) {
                    Artwork(item.poster ?: item.background)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A title: details, then the movie's Find streams or the series' seasons and episodes
// ---------------------------------------------------------------------------------------------

@Composable
private fun DetailPage(
    detail: AddonsViewModel.Detail,
    contentStart: Dp,
    openedEpisodeId: String?,
    returnFocus: FocusRequester,
    onSeason: (Int) -> Unit,
    onFindStreams: (MetaVideo?, String?) -> Unit,
) {
    val preview = detail.meta?.preview ?: detail.preview
    val primaryFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(preview.background ?: preview.poster)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 72.mpx, bottom = 80.mpx),
            verticalArrangement = Arrangement.spacedBy(26.mpx),
        ) {
            item(key = "info") {
                Column(Modifier.padding(start = contentStart + 20.mpx).widthIn(max = 1100.mpx), verticalArrangement = Arrangement.spacedBy(16.mpx)) {
                    Text(preview.name, style = stageText(54, 800, (-1).mpxSp), color = StageColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sep = stringResource(R.string.content_epg_bits_separator)
                    val bits = listOfNotNull(preview.releaseInfo, detail.meta?.runtime, preview.genres.take(3).joinToString(", ").ifEmpty { null })
                    if (bits.isNotEmpty() || preview.imdbRating != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
                            preview.imdbRating?.let { tv.own.owntv.ui.stage.StageRatingChip(it) }
                            if (bits.isNotEmpty()) Text(bits.joinToString(sep), style = stageText(20, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    preview.description?.let {
                        Text(it, style = stageText(20, 400), color = StageColors.Body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    detail.meta?.cast?.takeIf { it.isNotEmpty() }?.let { cast ->
                        Text(cast.take(5).joinToString(", "), style = stageText(17, 500), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when {
                detail.loading -> item(key = "loading") {
                    Busy(stringResource(R.string.addons_detail_loading), Modifier.padding(start = contentStart + 20.mpx))
                }
                detail.isSeries -> {
                    val meta = detail.meta!!
                    val seasons = meta.seasons
                    item(key = "seasons") {
                        LazyRow(
                            Modifier.focusGroup(),
                            contentPadding = PaddingValues(start = contentStart + 8.mpx, end = 64.mpx),
                            horizontalArrangement = Arrangement.spacedBy(14.mpx),
                        ) {
                            items(seasons, key = { it }) { season ->
                                StageTab(
                                    label = if (season == 0) stringResource(R.string.addons_specials) else stringResource(R.string.addons_season, season),
                                    count = meta.episodes(season).size.toString(),
                                    selected = season == detail.season,
                                    onClick = { onSeason(season) },
                                )
                            }
                        }
                    }
                    val episodes = detail.season?.let(meta::episodes).orEmpty()
                    item(key = "episodes") {
                        if (episodes.isEmpty()) {
                            Text(stringResource(R.string.addons_no_episodes), style = stageText(20, 500), color = StageColors.Muted, modifier = Modifier.padding(start = contentStart + 20.mpx))
                        } else {
                            LazyRow(
                                Modifier.focusGroup(),
                                contentPadding = PaddingValues(start = contentStart + 20.mpx, end = 64.mpx, top = 14.mpx, bottom = 14.mpx),
                                horizontalArrangement = Arrangement.spacedBy(28.mpx),
                            ) {
                                itemsIndexed(episodes, key = { _, e -> e.id }) { index, ep ->
                                    val label = episodeLabel(ep)
                                    StageEpisode(
                                        title = ep.title.ifBlank { label },
                                        line = ep.overview,
                                        number = (ep.episode ?: (index + 1)).toString(),
                                        watched = false,
                                        progress = null,
                                        onClick = { onFindStreams(ep, label) },
                                        width = 384.mpx,
                                        height = 216.mpx,
                                        modifier = Modifier
                                            .then(if (index == 0) Modifier.focusRequester(primaryFocus) else Modifier)
                                            .then(if (ep.id == openedEpisodeId) Modifier.focusRequester(returnFocus) else Modifier),
                                    ) { filter ->
                                        AsyncImage(
                                            model = ep.thumbnail ?: preview.background, contentDescription = null, contentScale = ContentScale.Crop,
                                            colorFilter = filter, modifier = Modifier.fillMaxSize().background(StageColors.Ink.copy(alpha = 0.06f)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> item(key = "play") {
                    Column(Modifier.padding(start = contentStart + 20.mpx), verticalArrangement = Arrangement.spacedBy(16.mpx)) {
                        if (detail.meta == null && detail.preview.type != "movie") {
                            Text(stringResource(R.string.addons_detail_unavailable), style = stageText(20, 500), color = StageColors.Muted)
                        }
                        StageButton(
                            stringResource(R.string.addons_find_streams), onClick = { onFindStreams(null, null) }, icon = OwnTVIcon.PLAY, iconFilled = true,
                            modifier = Modifier.focusRequester(primaryFocus),
                        )
                    }
                }
            }
        }
    }
    // Opening a title (or its details arriving) puts focus on its first action; coming back from the
    // streams of an episode puts it on that episode.
    LaunchedEffect(detail.preview.id, detail.loading, detail.season) {
        if (detail.loading) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val back = openedEpisodeId != null && runCatching { returnFocus.requestFocus() }.getOrDefault(false)
        if (!back) runCatching { primaryFocus.requestFocus() }
    }
}

@Composable
private fun episodeLabel(ep: MetaVideo): String {
    val se = if (ep.season != null && ep.episode != null) stringResource(R.string.content_season_episode, ep.season!!, ep.episode!!) else null
    return listOfNotNull(se, ep.title.takeIf { it.isNotBlank() }).joinToString(stringResource(R.string.content_epg_bits_separator))
}

// ---------------------------------------------------------------------------------------------
// Streams: every addon's links, grouped by addon, filling in as each one answers
// ---------------------------------------------------------------------------------------------

@Composable
private fun StreamsPage(
    streams: AddonsViewModel.Streams,
    contentStart: Dp,
    onPlay: (AddonStream) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().focusGroup(),
        contentPadding = PaddingValues(start = contentStart, end = 64.mpx, top = 52.mpx, bottom = 80.mpx),
        verticalArrangement = Arrangement.spacedBy(8.mpx),
    ) {
        item(key = "head") {
            Column(Modifier.padding(start = 20.mpx, bottom = 16.mpx)) {
                Text(streams.title, style = stageText(42, 800, (-1).mpxSp), color = StageColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    streams.episodeLabel ?: stringResource(R.string.addons_streams_title),
                    style = stageText(20, 600), color = StageColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (streams.results.isEmpty() && !streams.searching) {
            item(key = "nosource") {
                Text(stringResource(R.string.addons_streams_no_sources), style = stageText(20, 500), color = StageColors.Muted, modifier = Modifier.padding(start = 20.mpx))
            }
        }
        var first = true
        streams.results.forEach { result ->
            val name = result.addon.manifest.name
            item(key = "addon\n" + result.addon.transportUrl) {
                Text(
                    name.uppercase(), style = stageText(15, 800), color = StageColors.Dim, maxLines = 1,
                    modifier = Modifier.padding(start = 20.mpx, top = 18.mpx, bottom = 6.mpx),
                )
                when {
                    result.pending -> Busy(stringResource(R.string.addons_streams_searching, name), Modifier.padding(start = 20.mpx))
                    result.failed -> Text(stringResource(R.string.addons_streams_failed, name), style = stageText(18, 500), color = StageColors.Muted, modifier = Modifier.padding(start = 20.mpx))
                    result.streams.isEmpty() -> Text(stringResource(R.string.addons_streams_none, name), style = stageText(18, 500), color = StageColors.Muted, modifier = Modifier.padding(start = 20.mpx))
                }
            }
            val isFirst = first && result.streams.isNotEmpty()
            if (isFirst) first = false
            itemsIndexed(result.streams, key = { i, st -> "stream\n" + result.addon.transportUrl + "\n" + i + "\n" + st.url }) { i, stream ->
                StreamRow(
                    stream = stream,
                    size = stream.sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) },
                    onClick = { onPlay(stream) },
                    modifier = if (isFirst && i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
    // Focus the first link as soon as one exists; until then the rail-side ▶ has nothing to land on.
    val hasAny = streams.results.any { it.streams.isNotEmpty() }
    LaunchedEffect(hasAny) {
        if (!hasAny || focusedOnce) return@LaunchedEffect
        withFrameNanos { }
        focusedOnce = runCatching { firstFocus.requestFocus() }.getOrDefault(false)
    }
}

@Composable
private fun StreamRow(stream: AddonStream, size: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val a = stageAccent
    StageRow(onClick = onClick, height = 112.mpx, modifier = modifier.fillMaxWidth().widthIn(max = 1500.mpx)) { focused ->
        // The addon's name field is its quality badge ("Torrentio\n4K"): shown as two short lines on the left.
        Text(
            stream.name.orEmpty().replace('\n', ' ').trim(),
            style = stageText(18, 800), color = if (focused) a.onAccent else a.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(220.mpx),
        )
        Column(Modifier.weight(1f)) {
            Text(
                (stream.title ?: stream.filename ?: stream.url).trim(),
                style = stageText(17, 500), color = if (focused) a.onAccent else StageColors.Text, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        if (size != null) Text(size, style = stageText(17, 700), color = if (focused) a.onAccent else StageColors.Muted, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Small parts
// ---------------------------------------------------------------------------------------------

@Composable
private fun InstallMessage(install: AddonsViewModel.InstallState?) {
    val text = when (install) {
        null -> return
        AddonsViewModel.InstallState.Working -> stringResource(R.string.addons_installing)
        is AddonsViewModel.InstallState.Done -> when (val r = install.result) {
            is AddonRepository.InstallResult.Installed -> stringResource(R.string.addons_installed, r.addon.manifest.name)
            AddonRepository.InstallResult.InvalidUrl -> stringResource(R.string.addons_invalid_url)
            AddonRepository.InstallResult.Unreachable -> stringResource(R.string.addons_unreachable)
            is AddonRepository.InstallResult.NeedsConfiguration -> stringResource(R.string.addons_needs_config, r.manifest.name)
        }
    }
    val ok = (install as? AddonsViewModel.InstallState.Done)?.result is AddonRepository.InstallResult.Installed
    Text(
        text, style = stageText(18, 600), color = if (ok || install is AddonsViewModel.InstallState.Working) stageAccent.accent else StageColors.Warn,
        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.mpx),
    )
}

@Composable
private fun Busy(text: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.mpx), verticalAlignment = Alignment.CenterVertically) {
        OwnTVSpinner(sizeDp = 28)
        if (text != null) Text(text, style = stageText(18, 500), color = StageColors.Muted, maxLines = 1)
    }
}

@Composable
private fun Artwork(url: String?) {
    AsyncImage(
        model = url, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().background(StageColors.Ink.copy(alpha = 0.06f)),
    )
}

/** The title's background art, faded into the page from the right so the text on the left stays readable. */
@Composable
private fun Backdrop(url: String?) {
    if (url == null) return
    Box(Modifier.fillMaxSize()) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.45f, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to StageColors.Wash, 0.55f to StageColors.Wash.copy(alpha = 0.6f), 1f to Color.Transparent),
            ),
        )
    }
}

private val POSTER_W = 196.mpx
private val POSTER_H = 294.mpx

/** Focus this close to a row's end asks for its next page. */
private const val NEAR_END = 6

private const val INSTALL_MESSAGE_MS = 5_000L
