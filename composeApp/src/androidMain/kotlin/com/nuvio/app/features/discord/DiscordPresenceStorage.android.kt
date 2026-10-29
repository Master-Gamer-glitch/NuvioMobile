package com.nuvio.app.features.discord

import android.content.Context
import android.content.SharedPreferences

internal actual object DiscordPresenceStorage {
    private const val preferencesName = "nuvio_discord_presence"
    private const val settingsKey = "settings"
    private const val tokenKey = "token"
    private const val accountKey = "account"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun loadSettings(): String? = preferences?.getString(settingsKey, null)

    actual fun saveSettings(json: String) {
        preferences?.edit()?.putString(settingsKey, json)?.apply()
    }

    actual fun loadToken(): String? = preferences?.getString(tokenKey, null)

    actual fun loadAccount(): String? = preferences?.getString(accountKey, null)

    actual fun saveSession(token: String, accountJson: String) {
        preferences?.edit()
            ?.putString(tokenKey, token)
            ?.putString(accountKey, accountJson)
            ?.apply()
    }

    actual fun clearSession() {
        preferences?.edit()?.remove(tokenKey)?.remove(accountKey)?.apply()
    }
}
