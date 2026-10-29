package com.nuvio.app.features.discord

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

data class DiscordPresenceUiState(
    val isSupported: Boolean = false,
    val account: DiscordAccount? = null,
    val settings: DiscordPresenceSettings = DiscordPresenceSettings(),
    val connection: DiscordConnectionState = DiscordConnectionState.DISCONNECTED,
    val isSigningIn: Boolean = false,
    val errorMessage: String? = null,
)

object DiscordPresenceRepository {
    private val log = Logger.withTag("DiscordPresence")
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(DiscordPresenceUiState())
    val uiState: StateFlow<DiscordPresenceUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    internal val token: String?
        get() {
            ensureLoaded()
            return DiscordPresenceStorage.loadToken()?.takeIf { _uiState.value.account != null }
        }

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val settings = DiscordPresenceStorage.loadSettings()
            ?.let { runCatching { json.decodeFromString<DiscordPresenceSettings>(it) }.getOrNull() }
            ?: DiscordPresenceSettings()
        val account = DiscordPresenceStorage.loadAccount()
            ?.takeIf { DiscordPresenceStorage.loadToken() != null }
            ?.let { runCatching { json.decodeFromString<DiscordAccount>(it) }.getOrNull() }
        _uiState.value = DiscordPresenceUiState(
            isSupported = DiscordPlatform.isSupported,
            account = account,
            settings = settings,
        )
    }

    suspend fun signIn(token: String) {
        ensureLoaded()
        _uiState.update { it.copy(isSigningIn = true, errorMessage = null) }
        val account = runCatching { DiscordPlatform.fetchAccount(token) }
            .onFailure { log.w(it) { "Discord account lookup failed" } }
            .getOrNull()
        if (account == null) {
            _uiState.update { it.copy(isSigningIn = false, errorMessage = "Couldn't verify the Discord login. Try again.") }
            return
        }
        DiscordPresenceStorage.saveSession(token, json.encodeToString(DiscordAccount.serializer(), account))
        _uiState.update { it.copy(account = account, isSigningIn = false, errorMessage = null) }
    }

    fun signInCancelled() {
        _uiState.update { it.copy(isSigningIn = false) }
    }

    fun disconnect() {
        DiscordPresence.clear()
        DiscordPlatform.disconnect()
        DiscordPresenceStorage.clearSession()
        _uiState.update {
            it.copy(account = null, connection = DiscordConnectionState.DISCONNECTED, errorMessage = null)
        }
    }

    fun updateSettings(transform: (DiscordPresenceSettings) -> DiscordPresenceSettings) {
        ensureLoaded()
        val next = transform(_uiState.value.settings)
        if (next == _uiState.value.settings) return
        _uiState.update { it.copy(settings = next) }
        DiscordPresenceStorage.saveSettings(json.encodeToString(DiscordPresenceSettings.serializer(), next))
        DiscordPresence.republish()
    }

    internal fun onConnectionStateChanged(state: DiscordConnectionState, error: String? = null) {
        _uiState.update { it.copy(connection = state, errorMessage = error ?: it.errorMessage.takeIf { state != DiscordConnectionState.CONNECTED }) }
    }

    /** Discord rejected the token (password change, logged out everywhere, ...). */
    internal fun onTokenRejected() {
        log.w { "Discord rejected the stored token; signing out" }
        DiscordPresenceStorage.clearSession()
        _uiState.update {
            it.copy(
                account = null,
                connection = DiscordConnectionState.DISCONNECTED,
                errorMessage = "Discord signed this device out. Connect again to keep sharing.",
            )
        }
    }
}
