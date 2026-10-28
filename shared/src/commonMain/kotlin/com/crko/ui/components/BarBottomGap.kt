package com.crko.ui.components

import androidx.compose.ui.unit.Dp

/**
 * How far the floating bar keeps itself above the window bottom, given the platform's bottom inset.
 * Each platform protects something different down there, so the distance is chosen per platform
 * instead of derived from one shared fraction of the inset.
 */
internal expect fun barBottomGap(systemInset: Dp): Dp
