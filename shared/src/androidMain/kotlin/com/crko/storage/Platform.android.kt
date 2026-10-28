package com.crko.storage

import android.content.Context
import androidx.core.content.edit
import com.crko.AndroidAppContext
import java.util.Locale
import java.util.UUID

private class SharedPreferencesStore(private val name: String) : KeyValueStore {
    private val prefs by lazy {
        AndroidAppContext.get().getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }
}

actual fun appStore(): KeyValueStore = SharedPreferencesStore("crko.app")

actual fun settingsStore(): KeyValueStore = SharedPreferencesStore("crko.settings")

actual fun newId(): String = UUID.randomUUID().toString()

actual fun systemLocaleTag(): String = Locale.getDefault().toLanguageTag()

actual fun platformSupportsMonet(): Boolean = true
