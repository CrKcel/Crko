package com.crko

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Compose host, used to present document pickers and share sheets. */
object IosViewControllerHolder {
    var root: UIViewController? = null
        internal set
}

// Exported to Swift as MainViewControllerKt.MainViewController(); the name is part of the API.
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    val controller = ComposeUIViewController { App() }
    IosViewControllerHolder.root = controller
    return controller
}
