package com.crko.domain

/**
 * Decodes [bytes] with an explicit charset, returning null when the charset is unknown or the
 * sequence is malformed. Calendar files in the wild are not always UTF-8, so the platform decoders
 * are used instead of assuming the only valid encoding is the RFC one.
 */
internal expect fun decodeBytesWithCharset(bytes: ByteArray, charsetName: String): String?

/** Charsets tried for a whole calendar file, in order, when no BOM or header declares one. */
internal val CalendarCharsets = listOf("UTF-8", "GB18030", "windows-1252")

/** Written by a lossy decoder; a calendar that needed one is not readable in that charset. */
internal val replacementCharacter = '\uFFFD'

/** Names that stand for the same double-byte Chinese family. */
private val doubleByteChineseNames = setOf("GB18030", "GBK", "GB2312", "EUC-CN")

/** Normalised name of a charset we can actually decode, or null when the platform cannot. */
private fun knownCharsetName(value: String): String? = when (val name = value.trim().uppercase()) {
    "UTF-8", "UTF8" -> "UTF-8"
    "GB18030", "GBK", "GB2312", "EUC-CN" -> "GB18030"
    "WINDOWS-1252", "CP1252", "WIN1252" -> "windows-1252"
    "ISO-8859-1", "ISO8859-1", "LATIN1" -> "ISO-8859-1"
    else -> null
}

private val charsetParameter = Regex("CHARSET\\s*=\\s*\"?([A-Za-z0-9][A-Za-z0-9_-]*)", RegexOption.IGNORE_CASE)

/**
 * Charset the file declares for itself through a `CHARSET=` parameter. Reading it from a Latin-1
 * view of the bytes works before the real encoding is known, and a declaration beats guessing.
 */
internal fun declaredCalendarCharset(bytes: ByteArray): String? =
    decodeBytesWithCharset(bytes, "ISO-8859-1")
        ?.let { text ->
            charsetParameter.find(text)?.groupValues?.getOrNull(1)?.let(::knownCharsetName)
        }

/**
 * Whether [bytes] hold a run of two or more high bytes. GBK/GB18030 characters always come in such
 * runs, while a Windows-1252 file has accented letters one at a time. Without this test a French
 * title like "Noël" is silently read as one Chinese character.
 */
internal fun looksLikeDoubleByteText(bytes: ByteArray): Boolean {
    var run = 0
    bytes.forEach { byte ->
        run = if ((byte.toInt() and 0xFF) >= 0x80) run + 1 else 0
        if (run >= 2) return true
    }
    return false
}

/**
 * Charsets to try for [bytes], best evidence first: UTF-8 (the only encoding RFC 5545 allows, and a
 * strict decoder that fails on anything else), then what the file declares for itself, then the
 * remaining fallbacks. A declaration is trusted after UTF-8 rather than before it because prose can
 * mention `CHARSET=` inside a description without meaning the whole file.
 */
internal fun calendarCharsetCandidates(bytes: ByteArray): List<String> {
    val declared = declaredCalendarCharset(bytes)
    val doubleByte = looksLikeDoubleByteText(bytes)
    return buildList {
        add("UTF-8")
        declared?.let(::add)
        CalendarCharsets.forEach { name ->
            if (declared == null && !doubleByte && name.uppercase() in doubleByteChineseNames) return@forEach
            if (name !in this) add(name)
        }
    }
}

/**
 * Decodes vCalendar 1.0 `ENCODING=QUOTED-PRINTABLE` values. Escaped octets are collected as bytes
 * and decoded with the declared charset, so a GBK export does not turn into replacement characters.
 */
internal fun decodeQuotedPrintable(value: String, charset: String?): String {
    val bytes = mutableListOf<Byte>()
    val literal = StringBuilder()

    fun flushLiteral() {
        if (literal.isEmpty()) return
        literal.toString().encodeToByteArray().forEach(bytes::add)
        literal.clear()
    }

    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '=' && index + 2 < value.length) {
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                flushLiteral()
                bytes += ((high shl 4) or low).toByte()
                index += 3
                continue
            }
        }
        literal.append(ch)
        index++
    }
    flushLiteral()

    val data = bytes.toByteArray()
    val declared = charset?.trim()?.takeIf { it.isNotEmpty() }
    if (declared != null) {
        decodeBytesWithCharset(data, declared)?.let { return it }
    }
    return CalendarCharsets.firstNotNullOfOrNull { decodeBytesWithCharset(data, it) }
        ?: data.decodeToString()
}
