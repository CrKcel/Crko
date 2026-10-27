package com.crko.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.crko.ActivityHolder

/**
 * Keeps status and navigation bar icons readable when the in-app appearance overrides the system
 * setting; the window is transparent because the app draws edge to edge.
 */
@Composable
actual fun ApplySystemBarsAppearance(isDark: Boolean) {
    val view = LocalView.current
    DisposableEffect(isDark) {
        ActivityHolder.current()?.window?.let { window ->
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
        onDispose { }
    }
}
