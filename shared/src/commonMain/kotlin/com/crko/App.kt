package com.crko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.crko.state.AppStateHolder
import com.crko.state.reminderScheduler
import com.crko.storage.defaultSubscriptionHttp
import com.crko.storage.systemLocaleTag
import com.crko.ui.common.CrkoApp
import com.crko.ui.theme.CrkoTheme

/**
 * Application root shared by Android and iOS.
 *
 * [systemLocale] and [systemDark] are supplied by the host so previews and tests can fix them;
 * otherwise they are read from the platform.
 */
@Composable
fun App(
    systemLocale: String = systemLocaleTag(),
    systemDark: Boolean? = null,
) {
    val scope = rememberCoroutineScope()
    val state = remember {
        AppStateHolder(
            scope,
            initialSystemLocale = systemLocale,
            reminders = reminderScheduler(),
            subscriptionHttp = defaultSubscriptionHttp(),
        )
    }

    LaunchedEffect(systemLocale) {
        state.systemLocale = systemLocale
    }

    CrkoTheme(
        darkMode = state.darkModeSetting,
        palette = state.paletteSetting,
        keyColor = state.settings.keyColor,
        systemDark = systemDark,
    ) {
        CrkoApp(state)
    }
}
