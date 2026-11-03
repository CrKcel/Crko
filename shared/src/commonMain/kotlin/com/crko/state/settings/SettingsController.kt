package com.crko.state.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.PersistedSettings
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.i18n.languageFromTag
import com.crko.i18n.languageTag
import com.crko.i18n.resolveLanguage
import com.crko.i18n.stringsFor
import com.crko.state.DarkModeSetting
import com.crko.state.PaletteSetting
import com.crko.state.persistence.StateRepository

/**
 * User preferences: language, theme, palette, timeline defaults and their persistence.
 *
 * The facade keeps same-named members as delegates, so UI call sites do not change. This controller
 * is the only place that writes [PersistedSettings]. `state -> i18n` is allowed here solely for
 * language resolution and user-visible strings; settings never drive business rules.
 */
@Stable
internal class SettingsController(
    private val repository: StateRepository,
    initialSystemLocale: String,
) {
    var settings: PersistedSettings by mutableStateOf(repository.loadSettings())
        private set

    var systemLocale: String by mutableStateOf(initialSystemLocale)

    init {
        // Older payloads stored -1 to mean "not chosen yet"; the timeline then starts at 8:00.
        if (settings.agendaScrollHour < 0) updateSettings { it.copy(agendaScrollHour = 8) }
    }

    val languageSetting: AppLanguage get() = languageFromTag(settings.language)

    val resolvedLanguage: AppLanguage get() = resolveLanguage(languageSetting, systemLocale)

    val strings: Strings get() = stringsFor(resolvedLanguage)

    val darkModeSetting: DarkModeSetting
        get() = runCatching { DarkModeSetting.valueOf(settings.darkMode) }
            .getOrDefault(DarkModeSetting.System)

    val paletteSetting: PaletteSetting
        get() = runCatching { PaletteSetting.valueOf(settings.palette) }
            .getOrDefault(PaletteSetting.Monet)

    private fun updateSettings(transform: (PersistedSettings) -> PersistedSettings) {
        val next = transform(settings)
        settings = next
        repository.saveSettings(next)
    }

    fun setLanguage(language: AppLanguage) = updateSettings { it.copy(language = languageTag(language)) }

    fun setDarkMode(mode: DarkModeSetting) = updateSettings { it.copy(darkMode = mode.name) }

    fun setPalette(palette: PaletteSetting) = updateSettings { it.copy(palette = palette.name) }

    fun setKeyColor(argb: Long) = updateSettings { it.copy(keyColor = argb) }

    fun setDefaultHour(hour: Int) = updateSettings { it.copy(agendaScrollHour = hour.coerceIn(0, 23)) }

    fun setAgendaView(view: String) = updateSettings { it.copy(agendaView = view) }
}
