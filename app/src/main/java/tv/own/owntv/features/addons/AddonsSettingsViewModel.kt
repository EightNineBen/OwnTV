package tv.own.owntv.features.addons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.addons.AddonRepository
import tv.own.owntv.core.addons.InstalledAddon
import tv.own.owntv.core.companion.CompanionController
import tv.own.owntv.core.companion.CompanionServerState

/**
 * Settings › Addons: the installed addons in order, installing one (typed here, or sent from a phone
 * through the same companion server Playlists uses), moving and removing them.
 */
class AddonsSettingsViewModel(
    private val repo: AddonRepository,
    private val companion: CompanionController,
) : ViewModel() {

    val addons: StateFlow<List<InstalledAddon>> = repo.addons
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun remove(addon: InstalledAddon) { viewModelScope.launch { repo.remove(addon) } }

    fun move(addon: InstalledAddon, up: Boolean) { viewModelScope.launch { repo.move(addon, up) } }

    // ---- From your phone: the companion server in addon mode ------------------------------------

    val remoteState: StateFlow<CompanionServerState> get() = companion.state
    val remoteUrls: SharedFlow<String> get() = companion.addonUrls

    fun startRemote(port: Int) = companion.startForAddonInstall(port)
    fun stopRemote() = companion.stop()
}
