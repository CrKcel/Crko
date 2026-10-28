package com.crko.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The home indicator is system chrome drawn over the app, so the bar only has to stay out of the
 * strip below it rather than clear the whole 34pt safe area.
 */
internal actual fun barBottomGap(systemInset: Dp): Dp = 10.dp
