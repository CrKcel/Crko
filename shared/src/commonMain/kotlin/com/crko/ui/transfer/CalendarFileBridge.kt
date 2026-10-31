package com.crko.ui.transfer

import androidx.compose.runtime.Composable

/**
 * Host-provided access to calendar files. Implementations present the platform picker/saver and
 * report back on the main thread.
 */
interface CalendarFileBridge {
    fun openImport(onPicked: (String) -> Unit, onError: () -> Unit)

    fun saveExport(fileName: String, content: String, onExported: () -> Unit, onError: () -> Unit)
}

@Composable
expect fun rememberCalendarFileBridge(): CalendarFileBridge
