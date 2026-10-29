package com.nuvio.app.features.discord

/**
 * Device-local storage. Deliberately not profile-scoped and not part of profile sync:
 * the Discord token must never leave this device.
 */
internal expect object DiscordPresenceStorage {
    fun loadSettings(): String?
    fun saveSettings(json: String)
    fun loadToken(): String?
    fun loadAccount(): String?
    fun saveSession(token: String, accountJson: String)
    fun clearSession()
}
