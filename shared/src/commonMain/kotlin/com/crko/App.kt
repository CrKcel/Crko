package com.crko

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.crko.dev.sampleState
import com.crko.domain.today
import com.crko.isDebugBuild
import com.crko.state.AppStateHolder
import com.crko.state.reminderScheduler
import com.crko.storage.AttachmentRepository
import com.crko.storage.FileStateStorage
import com.crko.storage.StateStorage
import com.crko.storage.appStore
import com.crko.storage.defaultSubscriptionHttp
import com.crko.storage.platformSupportsMonet
import com.crko.storage.settingsStore
import com.crko.storage.systemLocaleTag
import com.crko.ui.common.CrkoApp
import com.crko.ui.theme.CrkoTheme
import com.crko.ui.theme.PlatformCapabilities
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/** Storage wired by the composition root; only debug builds seed sample content on first launch. */
private fun appStorage(): StateStorage = FileStateStorage(
    stateStore = ::appStore,
    settingsStore = ::settingsStore,
    seedProvider = if (isDebugBuild()) ({ sampleState(today()) }) else null,
)

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
    val platform = remember { PlatformCapabilities(supportsMonet = platformSupportsMonet()) }
    val state = remember {
        AppStateHolder(
            scope,
            initialSystemLocale = systemLocale,
            reminders = reminderScheduler(),
            subscriptionHttp = defaultSubscriptionHttp(),
            attachmentRepository = AttachmentRepository(),
            storage = appStorage(),
        )
    }

    LaunchedEffect(systemLocale) {
        state.systemLocale = systemLocale
    }

    // Subscriptions publish their own refresh interval; one launch-time check only covers the
    // moment the app opens, so the due set is polled while the app stays open. The policy lives
    // here in the composition root instead of the UI shell.
    LaunchedEffect(state) {
        while (true) {
            state.syncDueSubscriptions()
            delay(60.seconds)
        }
    }

    CrkoTheme(
        darkMode = state.darkModeSetting,
        palette = state.paletteSetting,
        keyColor = state.settings.keyColor,
        systemDark = systemDark,
        platform = platform,
    ) {
        CrkoApp(state)
    }
}
