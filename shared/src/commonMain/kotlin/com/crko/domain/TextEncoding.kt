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
 * Whether [bytes] read more plausibly as double-byte Chinese than as Windows-1252. The two encodings
 * cannot always be told apart byte by byte: "ÄÖ" and one Chinese character are both two high bytes
 * that GB18030 would accept. The statistics still separate almost everything: real Chinese text is
 * built from pairs where both bytes are high, while accented Western text puts single high bytes
 * between ASCII letters — pairs whose second byte is low. A sequence GB18030 cannot read at all
 * settles the question for Windows-1252, since a strict decoder would reject the file anyway.
 */
internal fun looksLikeDoubleByteText(bytes: ByteArray): Boolean {
    var purePairs = 0
    var mixedPairs = 0
    var broken = false
    var index = 0
    while (index < bytes.size) {
        val lead = bytes[index].toInt() and 0xFF
        when {
            lead < 0x80 || lead == 0x80 -> index++
            lead in 0x81..0xFE -> {
                val second = if (index + 1 < bytes.size) (bytes[index + 1].toInt() and 0xFF) else null
                when {
                    // Truncated at the end of the file: no evidence either way.
                    second == null -> index = bytes.size
                    second in 0x30..0x39 -> {
                        // Four-byte GB18030 form.
                        val third = if (index + 2 < bytes.size) (bytes[index + 2].toInt() and 0xFF) else null
                        val fourth = if (index + 3 < bytes.size) (bytes[index + 3].toInt() and 0xFF) else null
                        if (third != null && fourth != null && third in 0x81..0xFE && fourth in 0x30..0x39) {
                            purePairs++
                            index += 4
                        } else {
                            if (third != null && fourth != null) broken = true
                            index = bytes.size
                        }
                    }
                    second in 0x40..0x7E || second in 0x80..0xFE -> {
                        if (second >= 0x80) purePairs++ else mixedPairs++
                        index += 2
                    }
                    else -> {
                        broken = true
                        index = bytes.size
                    }
                }
            }
            else -> {
                broken = true
                index = bytes.size
            }
        }
    }
    if (broken) return false
    return purePairs >= 2 || (purePairs >= 1 && mixedPairs == 0)
}

/**
 * Charsets to try for [bytes], best evidence first: a byte order mark (some exporters write UTF-16
 * although RFC 5545 allows only UTF-8), then UTF-8 itself (a strict decoder that fails on anything
 * else), then what the file declares for itself, then the remaining fallbacks. A declaration is
 * trusted after UTF-8 rather than before it because prose can mention `CHARSET=` inside a
 * description without meaning the whole file.
 */
internal fun calendarCharsetCandidates(bytes: ByteArray): List<String> {
    val declared = declaredCalendarCharset(bytes)
    val doubleByte = looksLikeDoubleByteText(bytes)
    return buildList {
        byteOrderCharset(bytes)?.let(::add)
        add("UTF-8")
        declared?.let(::add)
        CalendarCharsets.forEach { name ->
            if (declared == null && !doubleByte && name.uppercase() in doubleByteChineseNames) return@forEach
            if (name !in this) add(name)
        }
    }
}

/** Charset named by the byte order mark of [bytes], if it carries one. */
internal fun byteOrderCharset(bytes: ByteArray): String? = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
    else -> null
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
