package com.crko.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Bridges the in-app resolved brightness to SwiftUI, which owns the status bar style through
 * preferredColorScheme: the Compose content draws behind the status bar, so the icons must
 * follow the app theme even when it overrides the system setting.
 */
object StatusBarAppearance {
    private var listener: ((Boolean) -> Unit)? = null

    var isDark: Boolean = false
        private set

    /** Registers [listener] and reports the current value immediately. */
    fun observe(listener: (Boolean) -> Unit) {
        this.listener = listener
        listener(isDark)
    }

    internal fun update(isDark: Boolean) {
        this.isDark = isDark
        listener?.invoke(isDark)
    }
}

@Composable
actual fun ApplySystemBarsAppearance(isDark: Boolean) {
    LaunchedEffect(isDark) {
        StatusBarAppearance.update(isDark)
    }
}
