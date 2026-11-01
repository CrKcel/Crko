package com.crko.test

import com.crko.domain.IcsUnfolder
import com.crko.domain.unfold
import com.crko.ui.transfer.CalendarReadException
import com.crko.ui.transfer.importCalendarChunks
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The reader turns picked file chunks into parsed records without the whole decoded text in memory;
 * these tests pin the chunk boundaries, the byte order marks and the unfolder equivalence.
 */
class CalendarTextReaderTest {
    private val utc = TimeZone.UTC

    @Test
    fun chunkedImportSurvivesSplitsInsideUtf8Sequences() {
        val value = buildString {
            append("BEGIN:VCALENDAR\r\n")
            repeat(3) { index ->
                append("BEGIN:VEVENT\r\nUID:chunk-$index\r\nSUMMARY:日程🚀 $index\r\n")
                append("DTSTART:20261027T090000\r\nEND:VEVENT\r\n")
            }
            append("END:VCALENDAR\r\n")
        }
        // Seven-byte chunks cut straight through the multibyte characters.
        val chunks = value.encodeToByteArray().toList().chunked(7).map { it.toByteArray() }
        val imported = importCalendarChunks(chunks, utc, "Untitled")
        assertEquals(listOf("日程🚀 0", "日程🚀 1", "日程🚀 2"), imported.events.map { it.title })
    }

    @Test
    fun chunkedImportReadsBomMarkedUtf16() {
        val value =
            "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:u16-1\r\nSUMMARY:会议\r\nDTSTART:20261027T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val littleEndian = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.utf16LeBytes()
        val chunks = littleEndian.toList().chunked(5).map { it.toByteArray() }
        assertEquals("会议", importCalendarChunks(chunks, utc, "Untitled").events.single().title)
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.utf16BeBytes()
        val bigChunks = bigEndian.toList().chunked(3).map { it.toByteArray() }
        assertEquals("会议", importCalendarChunks(bigChunks, utc, "Untitled").events.single().title)
    }

    @Test
    fun bytesNoKnownCharsetCanReadFailTheImport() {
        // Not UTF-8 (lone continuation byte), not GB18030 (truncated lead), and 0x81 has no
        // Windows-1252 mapping either.
        val chunks = listOf(byteArrayOf(0x80.toByte(), 0x81.toByte()))
        assertFailsWith<CalendarReadException> { importCalendarChunks(chunks, utc, "Untitled") }
    }

    @Test
    fun theIncrementalUnfolderProducesTheSameLinesAsTheWholeTextUnfold() {
        val samples = listOf(
            "BEGIN:VCALENDAR\r\nSUMMARY:a\r\n folded\r\nEND:VCALENDAR\r\n",
            "X:1\r\n\ttab\r\nY:2\nZ:3\rW:4\r\n",
            "END:VCALENDAR",
            "\r\n",
        )
        samples.forEach { value ->
            val expected = unfold(value)
            val unfolder = IcsUnfolder()
            val streamed = value.chunked(4).flatMap { unfolder.feed(it) } + unfolder.finish()
            assertEquals(expected, streamed, "mismatch for $value")
        }
    }
}

private fun String.utf16LeBytes(): ByteArray = flatMap { char ->
    listOf((char.code and 0xFF).toByte(), (char.code shr 8).toByte())
}.toByteArray()

private fun String.utf16BeBytes(): ByteArray = flatMap { char ->
    listOf((char.code shr 8).toByte(), (char.code and 0xFF).toByte())
}.toByteArray()
