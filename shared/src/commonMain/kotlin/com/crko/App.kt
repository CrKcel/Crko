package com.crko

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crko.dev.sampleState
import com.crko.domain.PersistedSettings
import com.crko.domain.today
import com.crko.isDebugBuild
import com.crko.state.AppStateHolder
import com.crko.state.DarkModeSetting
import com.crko.state.PaletteSetting
import com.crko.state.reminderScheduler
import com.crko.storage.AttachmentRepository
import com.crko.storage.AppStorage
import com.crko.storage.StateStorage
import com.crko.storage.defaultSubscriptionHttp
import com.crko.storage.loadPersistedSettings
import com.crko.storage.platformSupportsMonet
import com.crko.storage.systemLocaleTag
import com.crko.ui.common.CrkoApp
import com.crko.ui.theme.CrkoTheme
import com.crko.ui.theme.PlatformCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Duration.Companion.seconds

/** Storage wired by the composition root; only debug builds seed sample content on first launch. */
private fun appStorage(): StateStorage {
    if (isDebugBuild()) AppStorage.configureSeedProvider({ sampleState(today()) })
    return AppStorage
}

/** First subscription poll waits for the restored content instead of racing the first frame. */
private const val StartupSyncDelayMillis = 2_000L

/**
 * Application root shared by Android and iOS.
 *
 * [systemLocale] and [systemDark] are supplied by the host so previews and tests can fix them;
 * otherwise they are read from the platform.
 *
 * Opening the database and decoding the restored calendar blocks, so it runs after a loading frame
 * has been drawn: the stored theme comes from the small preference payload alone, and the state
 * holder is built once the storage probe and decode finished on a background dispatcher.
 */
@Composable
fun App(
    systemLocale: String = systemLocaleTag(),
    systemDark: Boolean? = null,
) {
    val scope = rememberCoroutineScope()
    val platform = remember { PlatformCapabilities(supportsMonet = platformSupportsMonet()) }
    val bootSettings = remember {
        runCatching { loadPersistedSettings() }.getOrDefault(PersistedSettings())
    }
    var holder by remember { mutableStateOf<AppStateHolder?>(null) }

    LaunchedEffect(Unit) {
        // Touching AppStorage opens, seeds and reads SQLite on first use; keep that work off the UI
        // thread so the loading frame can be presented immediately.
        withContext(Dispatchers.Default) { runCatching { appStorage().loadState() } }
        holder = AppStateHolder(
            scope,
            initialSystemLocale = systemLocale,
            reminders = reminderScheduler(),
            subscriptionHttp = defaultSubscriptionHttp(),
            attachmentRepository = AttachmentRepository(),
            storage = appStorage(),
        )
    }

    val state = holder
    if (state == null) {
        StartupPlaceholder(bootSettings, systemDark, platform)
        return
    }

    LaunchedEffect(systemLocale) {
        state.systemLocale = systemLocale
    }

    // Subscriptions publish their own refresh interval; one launch-time check only covers the
    // moment the app opens, so the due set is polled while the app stays open. The first poll waits
    // until the launch work settled, so a due feed does not compete with the first frames.
    LaunchedEffect(state) {
        delay(StartupSyncDelayMillis)
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

/** Themed background shown while the restored state is being read; no state database is touched. */
@Composable
private fun StartupPlaceholder(
    settings: PersistedSettings,
    systemDark: Boolean?,
    platform: PlatformCapabilities,
) {
    val darkMode = runCatching { DarkModeSetting.valueOf(settings.darkMode) }
        .getOrDefault(DarkModeSetting.System)
    val palette = runCatching { PaletteSetting.valueOf(settings.palette) }
        .getOrDefault(PaletteSetting.Monet)
    CrkoTheme(
        darkMode = darkMode,
        palette = palette,
        keyColor = settings.keyColor,
        systemDark = systemDark,
        platform = platform,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MiuixTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
    }
}
