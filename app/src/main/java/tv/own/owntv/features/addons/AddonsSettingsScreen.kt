package tv.own.owntv.features.addons

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import org.koin.androidx.compose.koinViewModel
import tv.own.owntv.R
import tv.own.owntv.core.addons.AddonRepository
import tv.own.owntv.core.addons.InstalledAddon
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.core.companion.CompanionServerState
import tv.own.owntv.features.settings.ConfirmDialog
import tv.own.owntv.features.settings.SettingHelp
import tv.own.owntv.features.settings.SettingValue
import tv.own.owntv.features.settings.StageAction
import tv.own.owntv.features.settings.StageActionColumn
import tv.own.owntv.features.settings.StageFullPage
import tv.own.owntv.features.settings.StageSettingRow
import tv.own.owntv.features.settings.StageSettingsNote
import tv.own.owntv.features.setup.RemotePanel
import tv.own.owntv.ui.components.InAppToast
import tv.own.owntv.ui.components.OwnTVIcon
import tv.own.owntv.ui.components.TextInputDialog
import tv.own.owntv.ui.components.companionLockedText
import tv.own.owntv.ui.components.displayText
import tv.own.owntv.ui.components.rememberInAppToast
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

private enum class AddMode { CHOOSE, REMOTE, MANUAL }

/**
 * Settings › Addons, laid out like EPG Sources: the addons as rows in the order they are asked, the
 * focused one's Move / Remove in the panel, and Add addon offering the same two ways in as a playlist:
 * **Remote** (paste the link on a phone, over the companion server) or **Type it here**.
 */
@Composable
fun AddonsSettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: AddonsSettingsViewModel = koinViewModel()) {
    val addons by vm.addons.collectAsStateWithLifecycle()
    val install by vm.install.collectAsStateWithLifecycle()
    val toast = rememberInAppToast()

    var addMode by remember { mutableStateOf<AddMode?>(null) }
    var confirmRemove by remember { mutableStateOf<InstalledAddon?>(null) }
    val addFocus = remember { FocusRequester() }
    val actionsFocus = remember { FocusRequester() }
    // The row acted on, so a move or a removal keeps focus in the list (as EPG Sources does).
    var contextUrl by remember { mutableStateOf<String?>(null) }
    var contextIndex by remember { mutableIntStateOf(-1) }
    val contextFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }

    // Every install, wherever it came from, reports on the toast.
    val message = installMessage(install)
    LaunchedEffect(install) {
        if (install is AddonsSettingsViewModel.InstallState.Done && message != null) {
            toast.show(message, INSTALL_TOAST_MS)
            vm.installShown()
        }
    }

    when (addMode) {
        AddMode.CHOOSE -> {
            AddChooser(onRemote = { addMode = AddMode.REMOTE }, onManual = { addMode = AddMode.MANUAL }, onBack = { addMode = null }, modifier = modifier)
            InAppToast(toast)
            return
        }
        AddMode.REMOTE -> {
            RemoteInstall(vm = vm, working = install is AddonsSettingsViewModel.InstallState.Working, onBack = { addMode = AddMode.CHOOSE }, modifier = modifier)
            InAppToast(toast)
            return
        }
        else -> Unit
    }

    LaunchedEffect(addMode, confirmRemove) {
        if (addMode != null || confirmRemove != null) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val target = contextUrl
        when {
            target != null && addons.any { it.transportUrl == target } -> runCatching { contextFocus.requestFocus() }
            addons.isNotEmpty() -> runCatching { firstRowFocus.requestFocus() }
            else -> runCatching { addFocus.requestFocus() }
        }
    }
    // A removed row takes its focus with it: move to the one now in its place.
    LaunchedEffect(addons) {
        val target = contextUrl ?: return@LaunchedEffect
        if (addons.any { it.transportUrl == target }) return@LaunchedEffect
        withFrameNanos { }
        val neighbour = addons.getOrNull(contextIndex.coerceAtLeast(0)) ?: addons.lastOrNull()
        if (neighbour == null) {
            contextUrl = null; contextIndex = -1
            runCatching { addFocus.requestFocus() }
            return@LaunchedEffect
        }
        contextUrl = neighbour.transportUrl
        contextIndex = addons.indexOf(neighbour)
        withFrameNanos { }
        runCatching { contextFocus.requestFocus() }
    }

    StageFullPage(
        parents = listOf(stringResource(R.string.settings_group_sources)),
        title = stringResource(R.string.addons_nav),
        count = addons.size.takeIf { it > 0 }?.toString().orEmpty(),
        onBack = onBack,
        modifier = modifier,
        toolbar = {
            tv.own.owntv.ui.stage.StageTool(
                stringResource(R.string.addons_add), onClick = { addMode = AddMode.CHOOSE },
                icon = OwnTVIcon.ADD, boxed = true, modifier = Modifier.focusRequester(addFocus),
            )
        },
    ) {
        if (addons.isEmpty()) StageSettingsNote(stringResource(R.string.addons_empty), stringResource(R.string.addons_settings_empty_body))
        addons.forEachIndexed { index, addon ->
            key(addon.transportUrl) {
                AddonRow(
                    addon = addon,
                    index = index,
                    count = addons.size,
                    rowFocus = when {
                        addon.transportUrl == contextUrl -> contextFocus
                        index == 0 -> firstRowFocus
                        else -> null
                    },
                    actionsFocus = actionsFocus,
                    keepPanel = addon.transportUrl == contextUrl,
                    onFocused = { contextUrl = addon.transportUrl; contextIndex = index },
                    onMove = { up -> contextUrl = addon.transportUrl; contextIndex = index; vm.move(addon, up) },
                    onRemove = { contextUrl = addon.transportUrl; contextIndex = index; confirmRemove = addon },
                )
            }
        }
    }

    if (addMode == AddMode.MANUAL) {
        TextInputDialog(
            title = stringResource(R.string.addons_add_title),
            label = stringResource(R.string.addons_add_label),
            hint = stringResource(R.string.addons_add_hint),
            confirmLabel = stringResource(R.string.addons_add_confirm),
            allowBlank = false,
            onConfirm = { url -> addMode = null; vm.install(url) },
            onDismiss = { addMode = AddMode.CHOOSE },
        )
    }
    confirmRemove?.let { addon ->
        ConfirmDialog(
            title = stringResource(R.string.addons_remove_title, addon.manifest.name),
            message = stringResource(R.string.addons_remove_message),
            onConfirm = { vm.remove(addon); confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    InAppToast(toast)
}

/** One addon: its name, what it says it is, its version, and Move up / Move down / Remove in the panel. */
@Composable
private fun AddonRow(
    addon: InstalledAddon,
    index: Int,
    count: Int,
    rowFocus: FocusRequester?,
    actionsFocus: FocusRequester,
    keepPanel: Boolean,
    onFocused: () -> Unit,
    onMove: (up: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val m = addon.manifest
    val actions = buildList {
        if (count > 1) {
            add(StageAction(OwnTVIcon.CHEVRON_UP, stringResource(R.string.addons_move_up), { onMove(true) }))
            add(StageAction(OwnTVIcon.CHEVRON_DOWN, stringResource(R.string.addons_move_down), { onMove(false) }))
        }
        add(StageAction(OwnTVIcon.TRASH, stringResource(R.string.addons_remove), onRemove, danger = true))
    }
    val back = rowFocus ?: remember { FocusRequester() }
    val version = m.version.takeIf { it.isNotBlank() }?.let { stringResource(R.string.addons_version, it) }
    // Never the link itself: a configured addon's link carries the debrid key.
    val host = remember(addon.transportUrl) { addon.transportUrl.substringAfter("://").substringBefore('/') }
    StageSettingRow(
        icon = OwnTVIcon.LAYERS,
        title = m.name,
        desc = m.description ?: host,
        value = version?.let { SettingValue.Action(it) },
        onClick = { runCatching { actionsFocus.requestFocus() } },
        keepPanel = keepPanel,
        help = SettingHelp(
            title = m.name,
            text = listOfNotNull(m.description, host).joinToString("\n"),
            hints = listOf(
                "▶" to stringResource(R.string.settings_key_actions),
                stringResource(R.string.common_back) to stringResource(R.string.common_nav_settings),
            ),
            extra = {
                if (count > 1) {
                    Text(stringResource(R.string.addons_order_line, index + 1, count), style = stageText(17, 500), color = StageColors.Muted)
                }
                StageActionColumn(actions, back, actionsFocus)
            },
        ),
        modifier = Modifier
            .focusRequester(back)
            .focusProperties { right = actionsFocus }
            .onFocusChanged { if (it.isFocused) onFocused() },
    )
}

/** Add addon: **Remote** or **Type it here**, as Playlists › Add a source asks. */
@Composable
private fun AddChooser(onRemote: () -> Unit, onManual: () -> Unit, onBack: () -> Unit, modifier: Modifier) {
    val rowsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rowsFocus.requestFocus() } }
    val addons = stringResource(R.string.addons_nav)
    val hints = listOf(
        stringResource(R.string.common_ok) to stringResource(R.string.settings_key_open),
        stringResource(R.string.common_back) to addons,
    )
    StageFullPage(
        parents = listOf(addons),
        title = stringResource(R.string.addons_add),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
        settingsRoot = false,
    ) {
        val phone = stringResource(R.string.setup_from_phone)
        StageSettingRow(
            icon = OwnTVIcon.PHONE,
            title = phone,
            desc = stringResource(R.string.addons_line_add_from_phone),
            value = SettingValue.Opens(null),
            onClick = onRemote,
            help = SettingHelp(phone, stringResource(R.string.addons_help_add_from_phone), hints = hints),
        )
        val here = stringResource(R.string.settings_add_type_here)
        StageSettingRow(
            icon = OwnTVIcon.PENCIL,
            title = here,
            desc = stringResource(R.string.addons_line_add_type_here),
            value = SettingValue.Opens(null),
            onClick = onManual,
            help = SettingHelp(here, stringResource(R.string.addons_help_add_type_here), hints = hints),
        )
    }
}

/**
 * Add addon › Remote: the companion server in addon mode, with the same QR, address and PIN panel as
 * Playlists. Each link sent from the phone installs straight away; the page stays open for the next.
 */
@Composable
private fun RemoteInstall(vm: AddonsSettingsViewModel, working: Boolean, onBack: () -> Unit, modifier: Modifier) {
    val state by vm.remoteState.collectAsStateWithLifecycle()
    val rowsFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.startRemote(CompanionLink.DEFAULT_PORT)
        runCatching { rowsFocus.requestFocus() }
    }
    LaunchedEffect(Unit) { vm.remoteUrls.collect { vm.install(it) } }
    DisposableEffect(Unit) { onDispose { vm.stopRemote() } }

    val help = SettingHelp(
        title = "",
        text = stringResource(R.string.addons_help_add_from_phone),
        hints = listOf(stringResource(R.string.common_back) to stringResource(R.string.settings_key_stop_back)),
    )
    StageFullPage(
        parents = listOf(stringResource(R.string.addons_add)),
        title = stringResource(R.string.setup_from_phone),
        count = "",
        onBack = onBack,
        modifier = modifier,
        rowsFocus = rowsFocus,
        settingsRoot = false,
        panelTop = { RemotePanel(state) },
    ) {
        val (line, problem) = when (val s = state) {
            CompanionServerState.Idle, CompanionServerState.Starting -> stringResource(R.string.setup_opening) to false
            is CompanionServerState.Listening -> stringResource(if (working) R.string.addons_installing else R.string.settings_remote_nothing_yet) to false
            is CompanionServerState.Failed -> s.failure.displayText() to true
            CompanionServerState.Locked -> companionLockedText() to true
        }
        StageSettingRow(
            icon = OwnTVIcon.PHONE,
            title = stringResource(R.string.settings_remote_waiting),
            desc = line,
            value = if (problem) null else SettingValue.Custom {
                Text("…", style = stageText(18, 700), color = StageColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 60.mpx))
            },
            onClick = {},
            help = help,
        )
        StageSettingRow(
            icon = OwnTVIcon.REFRESH,
            title = stringResource(R.string.settings_remote_new_pin),
            desc = stringResource(R.string.settings_line_remote_new_pin),
            value = null,
            onClick = { vm.stopRemote(); vm.startRemote(CompanionLink.DEFAULT_PORT) },
            help = help,
        )
    }
}

@Composable
private fun installMessage(install: AddonsSettingsViewModel.InstallState?): String? = when (install) {
    null, AddonsSettingsViewModel.InstallState.Working -> null
    is AddonsSettingsViewModel.InstallState.Done -> when (val r = install.result) {
        is AddonRepository.InstallResult.Installed -> stringResource(R.string.addons_installed, r.addon.manifest.name)
        AddonRepository.InstallResult.InvalidUrl -> stringResource(R.string.addons_invalid_url)
        AddonRepository.InstallResult.Unreachable -> stringResource(R.string.addons_unreachable)
        is AddonRepository.InstallResult.NeedsConfiguration -> stringResource(R.string.addons_needs_config, r.manifest.name)
    }
}

private const val INSTALL_TOAST_MS = 4_000L
