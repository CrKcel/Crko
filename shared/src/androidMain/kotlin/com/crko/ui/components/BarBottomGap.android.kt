package com.crko.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** An inset this tall is the on-screen navigation buttons, which the bar must not sit over. */
private val OnScreenButtonsInset = 32.dp

internal actual fun barBottomGap(systemInset: Dp): Dp =
    if (systemInset > OnScreenButtonsInset) systemInset else 18.dp
