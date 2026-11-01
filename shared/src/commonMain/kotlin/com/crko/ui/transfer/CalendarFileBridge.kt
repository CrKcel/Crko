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

private val byteOrderMark = Char(0xFEFF)

/**
 * Decodes calendar bytes as UTF-8, returning null when they are not valid UTF-8. Replacing the bad
 * bytes silently used to import mojibake titles as if the file had been perfectly readable.
 */
fun decodeCalendarBytes(bytes: ByteArray): String? {
    val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
    // A byte order mark is produced by real exporters but is not part of the first content line.
    return if (text.isNotEmpty() && text[0] == byteOrderMark) text.substring(1) else text
}

@Composable
expect fun rememberCalendarFileBridge(): CalendarFileBridge
