package com.crko.ui.transfer

import androidx.compose.runtime.Composable
import com.crko.domain.IcsImportFailure
import com.crko.domain.decodeCalendarBytes

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

@Composable
expect fun rememberCalendarFileBridge(): CalendarFileBridge
