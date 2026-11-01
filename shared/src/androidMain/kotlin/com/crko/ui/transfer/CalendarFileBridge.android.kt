package com.crko.ui.transfer

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.crko.domain.IcsImportFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MaxImportBytes = 5 * 1024 * 1024

private class CalendarImportException(val failure: IcsImportFailure) : Exception()

private val IcsMimeTypes = arrayOf(
    "text/calendar",
    "text/x-vcalendar",
    "application/ics",
    "text/plain",
    "application/octet-stream",
)

private class PendingTransfer {
    var importCallbacks: Pair<(String) -> Unit, (IcsImportFailure) -> Unit>? = null
    var exportContent: String? = null
    var exportCallbacks: Pair<() -> Unit, () -> Unit>? = null
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
                withContext(Dispatchers.IO) { readText(context, uri) }
            }
            result.fold(
                onSuccess = callbacks.first,
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

    return remember(openDocument, createDocument) {
        object : CalendarFileBridge {
            override fun openImport(onPicked: (String) -> Unit, onError: (IcsImportFailure) -> Unit) {
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
        }
    }
}

private fun readText(context: Context, uri: android.net.Uri): String {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw CalendarImportException(IcsImportFailure.Unreadable)
    stream.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = it.read(buffer)
            if (read < 0) break
            total += read
            if (total > MaxImportBytes) throw CalendarImportException(IcsImportFailure.TooLarge)
            output.write(buffer, 0, read)
        }
        // Rejecting an undecodable file beats importing replacement characters as if it were fine.
        return decodeCalendarBytes(output.toByteArray())
            ?: throw CalendarImportException(IcsImportFailure.InvalidEncoding)
    }
}

private fun writeText(context: Context, uri: android.net.Uri, content: String) {
    val stream = context.contentResolver.openOutputStream(uri)
        ?: error("Unable to open the destination file")
    stream.use { it.write(content.encodeToByteArray()) }
}
