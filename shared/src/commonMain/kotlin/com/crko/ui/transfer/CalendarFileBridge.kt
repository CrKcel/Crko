package com.crko.ui.transfer

import androidx.compose.runtime.Composable
import com.crko.domain.IcsImportFailure
import com.crko.domain.calendarCharsetCandidates
import com.crko.domain.decodeBytesWithCharset
import com.crko.domain.replacementCharacter

/**
 * Host-provided access to calendar files. Implementations present the platform picker/saver and
 * report back on the main thread.
 */
interface CalendarFileBridge {
    /**
     * [onPicked] receives the picked file as chunks (so decoding never needs one giant array) and,
     * when the platform says so, the file's own name.
     */
    fun openImport(onPicked: (chunks: List<ByteArray>, fileName: String?) -> Unit, onError: (IcsImportFailure) -> Unit)

    fun saveExport(fileName: String, content: String, onExported: () -> Unit, onError: () -> Unit)

    /** Saves an embedded `ATTACH` payload; the platform picker/share sheet chooses the destination. */
    fun saveAttachment(
        fileName: String,
        bytes: ByteArray,
        mimeType: String,
        onSaved: () -> Unit,
        onError: () -> Unit,
    )
}

private val byteOrderMark = Char(0xFEFF)

/**
 * Decodes calendar bytes with the best evidence available: a `CHARSET=` the file declares itself,
 * then UTF-8, then the double-byte and single-byte encodings older exporters used. A candidate that
 * needed replacement characters is lossy and gets the next try, so a platform whose decoder is
 * forgiving does not silently import garbage in place of the charset that reads it cleanly.
 */
fun decodeCalendarBytes(bytes: ByteArray): String? {
    val text = calendarCharsetCandidates(bytes)
        .mapNotNull { charset -> decodeBytesWithCharset(bytes, charset) }
        .firstOrNull { !it.contains(replacementCharacter) }
        ?: return null
    // A byte order mark is produced by real exporters but is not part of the first content line.
    return if (text.isNotEmpty() && text[0] == byteOrderMark) text.substring(1) else text
}

@Composable
expect fun rememberCalendarFileBridge(): CalendarFileBridge
