package com.crko.storage

import com.crko.domain.IcsImport
import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsUnfolder
import com.crko.domain.UnfoldedLine
import com.crko.domain.byteOrderCharset
import com.crko.domain.decodeBytesWithCharset
import com.crko.domain.decodeCalendarBytes
import com.crko.domain.importCalendarLines
import com.crko.domain.unfold
import kotlinx.datetime.TimeZone

/** Reading the picked file failed before any record could be parsed. */
class CalendarReadException(val failure: IcsImportFailure) : Exception()

/**
 * Imports calendar bytes that arrive in chunks. UTF-8 (the only encoding RFC 5545 requires) and
 * BOM-marked UTF-16 are decoded piece by piece, so the file never has to exist as one decoded
 * text; the older single-byte and double-byte encodings are still decoded as a whole, because
 * telling GB18030 from Windows-1252 needs the statistics of the full file.
 */
internal fun importCalendarChunks(
    chunks: List<ByteArray>,
    timeZone: TimeZone,
    untitledTitle: String,
): IcsImport = importCalendarLines(calendarLines(chunks), timeZone, untitledTitle)

/** How the calendar bytes are turned into text: streamed per chunk, or already decoded as a whole. */
private sealed interface ResolvedCharset {
    data class Streamed(val charset: String) : ResolvedCharset
    data class Decoded(val text: String) : ResolvedCharset
}

/**
 * Charset the chunks are read with, or null when no known charset can read them. A byte order mark
 * settles the question immediately — UTF-16 bytes are not valid UTF-8, so waiting for the
 * validation would only send the file down the fallback list; otherwise strict UTF-8 validation
 * decides, and only a file that is not UTF-8 falls back to the whole-text candidate list.
 */
private fun resolveCalendarCharset(chunks: List<ByteArray>): ResolvedCharset? {
    byteOrderCharset(chunks.firstOrNull() ?: ByteArray(0))?.let { charset ->
        return ResolvedCharset.Streamed(charset)
    }
    if (isValidUtf8(chunks)) return ResolvedCharset.Streamed("UTF-8")
    // The legacy candidates (a declared CHARSET, GB18030, Windows-1252) need the statistics of the
    // full file, so this path decodes it as a whole like it always did.
    return decodeCalendarBytes(chunks.join())?.let { ResolvedCharset.Decoded(it) }
}

/**
 * Unfolded logical lines of the calendar in [chunks]. Throws [CalendarReadException] when no known
 * charset can read the bytes; decoding failures noticed mid-stream throw the same way.
 */
internal fun calendarLines(chunks: List<ByteArray>): Sequence<UnfoldedLine> {
    if (chunks.isEmpty()) return emptySequence()
    return when (val resolved = resolveCalendarCharset(chunks)) {
        null -> throw CalendarReadException(IcsImportFailure.InvalidEncoding)
        is ResolvedCharset.Decoded -> unfold(resolved.text).asSequence()
        is ResolvedCharset.Streamed -> streamedLines(chunks, resolved.charset)
    }
}

/** Decodes chunk by chunk with a boundary carry, unfolding lines as they complete. */
private fun streamedLines(chunks: List<ByteArray>, charset: String): Sequence<UnfoldedLine> = sequence {
    val unfolder = IcsUnfolder()
    var carry = ByteArray(0)
    fun decode(buffer: ByteArray): String =
        decodeBytesWithCharset(buffer, charset)
            ?: throw CalendarReadException(IcsImportFailure.InvalidEncoding)

    chunks.forEach { chunk ->
        val buffer = if (carry.isEmpty()) chunk else carry + chunk
        val split = buffer.size - carryLength(buffer, charset)
        carry = buffer.copyOfRange(split, buffer.size)
        if (split > 0) yieldAll(unfolder.feed(decode(buffer.copyOfRange(0, split))))
    }
    if (carry.isNotEmpty()) yieldAll(unfolder.feed(decode(carry)))
    yieldAll(unfolder.finish())
}

/**
 * Bytes at the end of [buffer] that belong to a character the next chunk has to complete. Any
 * earlier malformed sequence is left in place, so the decoder reports it instead of the carry
 * hiding it.
 */
private fun carryLength(buffer: ByteArray, charset: String): Int = when (charset) {
    "UTF-16LE", "UTF-16BE" -> buffer.size % 2
    else -> utf8CarryLength(buffer)
}

/**
 * Length of the trailing incomplete UTF-8 sequence in [buffer]: its continuation bytes plus the
 * lead byte, but nothing when the sequence already completes inside the buffer.
 */
private fun utf8CarryLength(buffer: ByteArray): Int {
    var continuations = 0
    var index = buffer.size
    while (index > 0 && (buffer[index - 1].toInt() and 0xFF) in 0x80..0xBF) {
        continuations++
        index--
        // A valid sequence has at most three continuation bytes; a longer run is malformed and the
        // decoder rejects it, carry or no carry.
        if (continuations == 3) break
    }
    if (index == 0) return continuations
    val expected = when (buffer[index - 1].toInt() and 0xFF) {
        in 0xC2..0xDF -> 1
        in 0xE0..0xEF -> 2
        in 0xF0..0xF4 -> 3
        // A lead byte that cannot start a sequence (or plain ASCII) is decoded as it is.
        else -> return continuations
    }
    return if (continuations < expected) continuations + 1 else 0
}

/**
 * Whether the chunks hold nothing but well-formed UTF-8, checked without building any text. The
 * validator is strict in the same places a platform decoder is: no overlong forms, no surrogates,
 * nothing above U+10FFFF.
 */
private fun isValidUtf8(chunks: List<ByteArray>): Boolean {
    var remaining = 0
    var codePoint = 0
    var lowerBound = 0
    var upperBound = 0x10FFFF
    chunks.forEach { chunk ->
        chunk.forEach { byte ->
            val value = byte.toInt() and 0xFF
            when {
                remaining > 0 && value in 0x80..0xBF -> {
                    codePoint = (codePoint shl 6) or (value and 0x3F)
                    remaining--
                    if (remaining == 0) {
                        if (codePoint < lowerBound || codePoint > upperBound) return false
                        lowerBound = 0
                        upperBound = 0x10FFFF
                    }
                }
                remaining > 0 -> return false
                value < 0x80 -> Unit
                value in 0xC2..0xDF -> {
                    codePoint = value and 0x1F
                    remaining = 1
                    lowerBound = 0x80
                }
                value in 0xE0..0xEF -> {
                    codePoint = value and 0x0F
                    remaining = 2
                    // 0xE0 would otherwise accept overlong forms, 0xED the surrogate range.
                    lowerBound = if (value == 0xE0) 0x800 else 0
                    upperBound = if (value == 0xED) 0xD7FF else 0x10FFFF
                }
                value in 0xF0..0xF4 -> {
                    codePoint = value and 0x07
                    remaining = 3
                    // 0xF0 would otherwise accept overlong forms; 0xF4 is capped by the bound.
                    lowerBound = if (value == 0xF0) 0x10000 else 0
                }
                else -> return false
            }
        }
    }
    return remaining == 0
}

private fun List<ByteArray>.join(): ByteArray {
    val total = sumOf { it.size }
    val joined = ByteArray(total)
    var offset = 0
    forEach { chunk ->
        chunk.copyInto(joined, offset)
        offset += chunk.size
    }
    return joined
}
