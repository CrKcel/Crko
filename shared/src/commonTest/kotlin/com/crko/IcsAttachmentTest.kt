package com.crko

import com.crko.domain.Attachment
import com.crko.domain.exportIcs
import com.crko.domain.importIcs
import com.crko.domain.safeAttachmentFileName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcsAttachmentTest : IcsTestBase() {

    @Test
    fun attachmentsAreParsedExportedAndShownByName() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:attach
            SUMMARY:Trip
            DTSTART:20261027T090000
            ATTACH;FMTTYPE=image/png;FILENAME=ticket.png:https://example.com/ticket.png
            ATTACH;ENCODING=BASE64;FMTTYPE=text/plain:VGVzdA==
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(2, event.attachments.size, event.attachments.toString())
        assertEquals("ticket.png", event.attachments[0].name)
        assertEquals("https://example.com/ticket.png", event.attachments[0].uri)
        assertEquals("image/png", event.attachments[0].mimeType)
        assertFalse(event.attachments[0].binary)
        assertTrue(event.attachments[1].binary)
        assertEquals("VGVzdA==", event.attachments[1].data)
        assertEquals("text/plain", event.attachments[1].mimeType)
        val exported = exportIcs(listOf(event), now)
        assertEquals(2, exported.split("ATTACH").size - 1, exported)
        val back = importIcs(exported, utc, "Untitled").single()
        assertEquals(event.attachments, back.attachments)
    }

    @Test
    fun attachmentNamesNeverCarryAPath() {
        assertEquals("Secrets.pdf", safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "../../Documents/Secrets.pdf"))))
        assertEquals("config", safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "..\\..\\windows\\config"))))
        assertEquals("note.txt", safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "/etc/note.txt"))))
        assertEquals("attachment", safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "...."))))
        assertEquals("a".repeat(80), safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "a".repeat(400)))))
        assertEquals("report.pdf", safeAttachmentFileName(Attachment(value = "https://x.test/a/b/report.pdf")))
        val cleaned = safeAttachmentFileName(Attachment(parameters = mapOf("FILENAME" to "evil\u0000name")))
        assertTrue(cleaned.none { it.code < 0x20 }, cleaned)
    }
}
