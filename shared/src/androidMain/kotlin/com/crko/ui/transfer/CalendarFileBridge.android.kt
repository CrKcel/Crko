package com.crko.ui.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.crko.domain.IcsImportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MaxImportBytes = 5 * 1024 * 1024
private const val ChunkSize = 64 * 1024

private class CalendarImportException(val failure: IcsImportFailure) : Exception()

private val IcsMimeTypes = arrayOf(
    "text/calendar",
    "text/x-vcalendar",
    "application/ics",
    "text/plain",
    "application/octet-stream",
)

private class PendingTransfer {
    var importCallbacks: Pair<(List<ByteArray>, String?) -> Unit, (IcsImportFailure) -> Unit>? = null
    var exportContent: String? = null
    var exportCallbacks: Pair<() -> Unit, () -> Unit>? = null
    var attachmentBytes: ByteArray? = null
    var attachmentCallbacks: Pair<() -> Unit, () -> Unit>? = null
}

/**
 * `ACTION_CREATE_DOCUMENT` with the MIME type chosen per call. The stock [ActivityResultContracts.CreateDocument]
 * fixes the type when the launcher is built, long before the attachment whose `FMTTYPE` decides it
 * is known, which forced every download to `application/octet-stream`.
 */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.second
            putExtra(Intent.EXTRA_TITLE, input.first)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.takeIf { resultCode == android.app.Activity.RESULT_OK }?.data
}

@Composable
actual fun rememberCalendarFileBridge(): CalendarFileBridge {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val pending = remember { PendingTransfer() }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val callbacks = pending.importCallbacks
        pending.importCallbacks = null
        if (uri == null || callbacks == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    readChunks(context, uri) to displayName(context, uri)
                }
            }
            result.fold(
                onSuccess = { (chunks, name) -> callbacks.first(chunks, name) },
                onFailure = { error ->
                    callbacks.second((error as? CalendarImportException)?.failure ?: IcsImportFailure.Unreadable)
                },
            )
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val content = pending.exportContent
        val callbacks = pending.exportCallbacks
        pending.exportContent = null
        pending.exportCallbacks = null
        if (uri == null || content == null || callbacks == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) { writeText(context, uri, content) }
            }.isSuccess
            if (saved) callbacks.first() else callbacks.second()
        }
    }

    val createAttachment = rememberLauncherForActivityResult(CreateTypedDocument()) { uri ->
        val bytes = pending.attachmentBytes
        val callbacks = pending.attachmentCallbacks
        pending.attachmentBytes = null
        pending.attachmentCallbacks = null
        if (uri == null || bytes == null || callbacks == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) { writeBytes(context, uri, bytes) }
            }.isSuccess
            if (saved) callbacks.first() else callbacks.second()
        }
    }

    return remember(openDocument, createDocument, createAttachment) {
        object : CalendarFileBridge {
            override fun openImport(
                onPicked: (List<ByteArray>, String?) -> Unit,
                onError: (IcsImportFailure) -> Unit,
            ) {
                pending.importCallbacks = onPicked to onError
                openDocument.launch(IcsMimeTypes)
            }

            override fun saveExport(
                fileName: String,
                content: String,
                onExported: () -> Unit,
                onError: () -> Unit,
            ) {
                pending.exportContent = content
                pending.exportCallbacks = onExported to onError
                createDocument.launch(fileName)
            }

            override fun saveAttachment(
                fileName: String,
                bytes: ByteArray,
                mimeType: String,
                onSaved: () -> Unit,
                onError: () -> Unit,
            ) {
                pending.attachmentBytes = bytes
                pending.attachmentCallbacks = onSaved to onError
                createAttachment.launch(fileName to mimeType)
            }
        }
    }
}

/**
 * The file in chunks, so decoding never needs the whole byte array at once. The size limit applies
 * while reading: an oversized file is rejected before its content reaches memory.
 */
private fun readChunks(context: Context, uri: Uri): List<ByteArray> {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw CalendarImportException(IcsImportFailure.Unreadable)
    stream.use {
        val chunks = ArrayList<ByteArray>()
        val buffer = ByteArray(ChunkSize)
        var total = 0
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            if (total > MaxImportBytes) throw CalendarImportException(IcsImportFailure.TooLarge)
            if (read > 0) chunks += buffer.copyOf(read)
        }
        return chunks
    }
}

/** Display name the document provider reports, for provenance of the imported records. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
    }
}.getOrNull()

private fun writeText(context: Context, uri: Uri, content: String) {
    val stream = context.contentResolver.openOutputStream(uri)
        ?: error("Unable to open the destination file")
    stream.use { it.write(content.encodeToByteArray()) }
}

private fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    val stream = context.contentResolver.openOutputStream(uri)
        ?: error("Unable to open the destination file")
    stream.use { it.write(bytes) }
}
