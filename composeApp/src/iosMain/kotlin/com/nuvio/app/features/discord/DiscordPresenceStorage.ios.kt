package com.nuvio.app.features.discord

import platform.Foundation.NSUserDefaults

internal actual object DiscordPresenceStorage {
    private const val settingsKey = "discord_presence_settings"
    private const val tokenKey = "discord_presence_token"
    private const val accountKey = "discord_presence_account"

    private val defaults get() = NSUserDefaults.standardUserDefaults

    actual fun loadSettings(): String? = defaults.stringForKey(settingsKey)

    actual fun saveSettings(json: String) {
        defaults.setObject(json, forKey = settingsKey)
    }

    actual fun loadToken(): String? = defaults.stringForKey(tokenKey)

    actual fun loadAccount(): String? = defaults.stringForKey(accountKey)

    actual fun saveSession(token: String, accountJson: String) {
        defaults.setObject(token, forKey = tokenKey)
        defaults.setObject(accountJson, forKey = accountKey)
    }

    actual fun clearSession() {
        defaults.removeObjectForKey(tokenKey)
        defaults.removeObjectForKey(accountKey)
    }
}
