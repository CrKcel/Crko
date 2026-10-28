package com.crko.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private fun icon(name: String, path: String, evenOdd: Boolean = false): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black),
            pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
        )
        .build()

/**
 * The handful of glyphs the app needs, drawn as 24dp outlines so they inherit the theme content
 * color like every other icon.
 */
object AppIcons {
    val Add: ImageVector by lazy { icon("Add", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z") }

    val Check: ImageVector by lazy {
        icon("Check", "M9.55 17.6 4.4 12.45l1.4-1.4 3.75 3.75 8.65-8.65 1.4 1.4z")
    }

    val ExpandMore: ImageVector by lazy {
        icon("ExpandMore", "M12 15.375 5.625 9l1.4-1.4 4.975 4.975L16.975 7.6l1.4 1.4z")
    }

    val ExpandLess: ImageVector by lazy {
        icon("ExpandLess", "M12 10.275 5.625 16.65l-1.4-1.4L12 7.475l7.775 7.775-1.4 1.4z")
    }

    val ListView: ImageVector by lazy {
        icon(
            "ListView",
            "M4 7h2v2H4zM8 7h12v2H8zM4 11h2v2H4zM8 11h12v2H8zM4 15h2v2H4zM8 15h12v2H8z",
        )
    }

    val Timeline: ImageVector by lazy {
        icon("Timeline", "M5 3h2v18H5zM9 6h11v3H9zM9 11h7v3H9zM9 16h9v3H9z")
    }

    val Task: ImageVector by lazy {
        icon(
            "Task",
            "M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm-1.14 6.64-6.53 6.53-3.3-3.3 1.27-1.27 2.03 2.03 5.26-5.26z",
            evenOdd = true,
        )
    }

    val Tune: ImageVector by lazy {
        icon(
            "Tune",
            "M3 17v2h6v-2zM3 5v2h10V5zm10 16v-2h8v-2h-8v-2h-2v6zM7 9v2H3v2h4v2h2V9zm14 4v-2H11v2zm-6-4h2V7h4V5h-4V3h-2z",
        )
    }

    val Delete: ImageVector by lazy {
        icon(
            "Delete",
            "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM8 9h8v10H8zm7.5-5-1-1h-5l-1 1H5v2h14V4z",
        )
    }

    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84a.47.47 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87a.47.47 0 0 0 .12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58zM12 15.6a3.6 3.6 0 1 1 0-7.2 3.6 3.6 0 0 1 0 7.2z",
        )
    }
}
