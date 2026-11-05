package com.crko

import com.crko.i18n.AppLanguage
import com.crko.i18n.resolveLanguage
import com.crko.i18n.systemLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {

    @Test
    fun settingsSurviveReload() {
        val storage = MemoryStorage()
        Fixture(storage).let {
            it.holder.setLanguage(AppLanguage.Chinese)
            it.holder.setDarkMode(com.crko.state.DarkModeSetting.Dark)
            it.holder.setPalette(com.crko.state.PaletteSetting.Monochrome)
            it.holder.setKeyColor(0xFF00FF00)
        }
        val reloaded = Fixture(storage)
        assertEquals(AppLanguage.Chinese, reloaded.holder.languageSetting)
        assertEquals(com.crko.state.DarkModeSetting.Dark, reloaded.holder.darkModeSetting)
        assertEquals(com.crko.state.PaletteSetting.Monochrome, reloaded.holder.paletteSetting)
        assertEquals(0xFF00FF00, reloaded.holder.settings.keyColor)
    }

    @Test
    fun systemLanguageFallsBackToEnglishForOtherLocales() {
        assertEquals(AppLanguage.Chinese, systemLanguage("zh-Hans-CN"))
        assertEquals(AppLanguage.Chinese, systemLanguage("ZH-TW"))
        assertEquals(AppLanguage.English, systemLanguage("ja-JP"))
        assertEquals(AppLanguage.English, systemLanguage("fr-FR"))
        assertEquals(AppLanguage.Chinese, resolveLanguage(AppLanguage.System, "zh-CN"))
        assertEquals(AppLanguage.English, resolveLanguage(AppLanguage.English, "zh-CN"))
    }

    @Test
    fun defaultScrollHourIsInitializedOnce() {
        val storage = MemoryStorage()
        val holder = Fixture(storage).holder
        assertEquals(8, holder.settings.agendaScrollHour)
        holder.setDefaultHour(6)
        assertEquals(6, Fixture(storage).holder.settings.agendaScrollHour)
    }

    @Test
    fun gestureSwitchesDefaultOnAndSurviveReload() {
        val storage = MemoryStorage()
        val holder = Fixture(storage).holder
        assertEquals(true, holder.pinchOverviewEnabled)
        assertEquals(true, holder.swipeSwitchEnabled)

        holder.setPinchOverview(false)
        holder.setSwipeSwitch(false)
        val reloaded = Fixture(storage).holder
        assertEquals(false, reloaded.pinchOverviewEnabled)
        assertEquals(false, reloaded.swipeSwitchEnabled)

        reloaded.setPinchOverview(true)
        reloaded.setSwipeSwitch(true)
        assertEquals(true, Fixture(storage).holder.pinchOverviewEnabled)
        assertEquals(true, Fixture(storage).holder.swipeSwitchEnabled)
    }
}
