@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.crko.domain

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFStringConvertEncodingToNSStringEncoding
import platform.CoreFoundation.kCFStringEncodingGB_18030_2000
import platform.CoreFoundation.kCFStringEncodingUTF16BE
import platform.CoreFoundation.kCFStringEncodingUTF16LE
import platform.Foundation.NSData
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSWindowsCP1252StringEncoding
import platform.Foundation.create

internal actual fun decodeBytesWithCharset(bytes: ByteArray, charsetName: String): String? {
    val encoding = when (charsetName.trim().uppercase()) {
        "UTF-8", "UTF8" -> NSUTF8StringEncoding
        "UTF-16LE", "UTF16LE" -> CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingUTF16LE.toUInt())
        "UTF-16BE", "UTF16BE" -> CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingUTF16BE.toUInt())
        "GB18030", "GBK", "GB2312" ->
            CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000.toUInt())
        "WINDOWS-1252", "CP1252" -> NSWindowsCP1252StringEncoding
        "ISO-8859-1", "LATIN1" -> NSISOLatin1StringEncoding
        else -> return null
    }
    val data = if (bytes.isEmpty()) {
        NSData.create(bytes = null, length = 0u)
    } else {
        bytes.usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()) }
    }
    return NSString.create(data = data, encoding = encoding)?.toString()
}
