package com.crko.ui.theme

import androidx.compose.runtime.Composable

/** Applies the resolved appearance to the platform status and navigation bars. */
@Composable
expect fun ApplySystemBarsAppearance(isDark: Boolean)
