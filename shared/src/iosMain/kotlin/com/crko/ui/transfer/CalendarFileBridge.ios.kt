@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.crko.ui.transfer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.crko.IosViewControllerHolder
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectGetMidX
import platform.CoreGraphics.CGRectGetMidY
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

private const val MaxImportBytes = 5 * 1024 * 1024

@Composable
actual fun rememberCalendarFileBridge(): CalendarFileBridge = remember { IosCalendarFileBridge() }

private class IosCalendarFileBridge : CalendarFileBridge {
    private var importDelegate: ImportDelegate? = null

    override fun openImport(onPicked: (String) -> Unit, onError: () -> Unit) {
        val presenter = IosViewControllerHolder.root ?: return onError()
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.calendar-event", "public.text", "public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        val delegate = ImportDelegate(
            onPicked = onPicked,
            onError = onError,
            onFinished = { importDelegate = null },
        )
        importDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    override fun saveExport(
        fileName: String,
        content: String,
        onExported: () -> Unit,
        onError: () -> Unit,
    ) {
        val presenter = IosViewControllerHolder.root ?: return onError()
        val path = NSTemporaryDirectory() + fileName
        if (!content.encodeToByteArray().toNSData().writeToFile(path, atomically = true)) {
            onError()
            return
        }
        val url = NSURL.fileURLWithPath(path)
        val controller = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        controller.completionWithItemsHandler = { _, completed, _, error ->
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            when {
                error != null -> onError()
                completed -> onExported()
            }
        }
        controller.popoverPresentationController?.let { popover ->
            val view = presenter.view
            popover.sourceView = view
            popover.sourceRect = CGRectMake(
                CGRectGetMidX(view.bounds),
                CGRectGetMidY(view.bounds),
                0.0,
                0.0,
            )
        }
        presenter.presentViewController(controller, animated = true, completion = null)
    }
}

private class ImportDelegate(
    private val onPicked: (String) -> Unit,
    private val onError: () -> Unit,
    private val onFinished: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onFinished()
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            onError()
            return
        }
        val scoped = url.startAccessingSecurityScopedResource()
        try {
            // The limit has to be applied before reading: loading the file first made the check
            // useless and let an oversized file exhaust memory instead of reporting an error.
            val declaredSize = fileSizeBytes(url)
            if (declaredSize != null && declaredSize > MaxImportBytes) {
                onError()
                return
            }
            val data = NSData.dataWithContentsOfURL(url)
                ?: error("Unable to read the selected file")
            check(data.length <= MaxImportBytes.toULong()) { "Calendar file is too large" }
            // An undecodable file is rejected rather than imported with replacement characters.
            onPicked(decodeCalendarBytes(data.toByteArray()) ?: error("Calendar file is not valid UTF-8"))
        } catch (_: Throwable) {
            onError()
        } finally {
            if (scoped) url.stopAccessingSecurityScopedResource()
        }
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onFinished()
    }
}

/**
 * Size of the file behind [url] in bytes, or null when the provider does not say. Asking first is
 * what keeps an oversized file from being loaded into memory only to be rejected afterwards.
 */
private fun fileSizeBytes(url: NSURL): Long? = runCatching {
    @Suppress("UNCHECKED_CAST")
    val values = url.resourceValuesForKeys(listOf(NSURLFileSizeKey), error = null) as? Map<Any?, Any?>
    (values?.get(NSURLFileSizeKey) as? NSNumber)?.longLongValue
}.getOrNull()

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData.create(bytes = null, length = 0u)
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> getBytes(pinned.addressOf(0), length = length) }
    return bytes
}
