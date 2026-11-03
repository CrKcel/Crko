package com.crko.domain

internal fun parseComponents(text: String): List<IcsComponent> = parseComponents(unfold(text).asSequence())

internal fun parseComponents(lines: Sequence<UnfoldedLine>): List<IcsComponent> {
    val roots = mutableListOf<IcsComponent>()
    val stack = ArrayDeque<IcsComponent>()
    lines.forEach { unfolded ->
        val parsed = parseContentLine(unfolded) ?: return@forEach
        when (parsed.name) {
            "BEGIN" -> {
                val component = IcsComponent(parsed.value.trim().uppercase(), unfolded.lineNumber)
                if (stack.isEmpty()) roots += component else stack.last().children += component
                stack.addLast(component)
            }
            "END" -> {
                // Close only a component whose name matches: a stray or mismatched END must not pop
                // the enclosing VCALENDAR, which would drop every event that follows it.
                val closing = parsed.value.trim().uppercase()
                if (stack.lastOrNull()?.name == closing) stack.removeLast()
            }
            else -> stack.lastOrNull()?.properties?.add(parsed)
        }
    }
    return roots
}

/**
 * Turns a whole calendar text into unfolded logical lines. For text that arrives in pieces, use
 * [IcsUnfolder], which produces the very same lines without the whole text in memory.
 */
internal fun unfold(text: String): List<UnfoldedLine> {
    val unfolder = IcsUnfolder()
    val lines = unfolder.feed(text)
    return lines + unfolder.finish()
}

/**
 * Assembles physical lines into folded ICS logical lines, one piece at a time. Line numbers follow
 * the physical lines of the original file, so warnings can point at them. Feeding the same text as
 * one piece produces the lines of the previous whole-text [unfold], including its edge cases: a
 * byte order mark is dropped, `\r\n` and lone `\r` both end a line, and a trailing newline counts
 * as one final (empty) physical line.
 */
internal class IcsUnfolder {
    private var logical: String? = null
    private var logicalNumber = 0
    private var lineNumber = 0
    private val pending = StringBuilder()
    private var pendingCarriageReturn = false
    private var started = false
    private var endedWithNewline = false

    /** Feeds one decoded piece of the file; returns the logical lines completed by it. */
    fun feed(piece: String): List<UnfoldedLine> {
        val lines = mutableListOf<UnfoldedLine>()
        var text = piece
        if (!started) {
            started = true
            // A byte order mark is produced by real exporters but is not part of the first line.
            if (text.startsWith('\uFEFF')) text = text.substring(1)
        }
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            // `\r\n` split across pieces is one line break, not two.
            if (text.startsWith('\n')) text = text.substring(1)
        }
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '\r' -> {
                    flush(pending.toString(), lines)
                    pending.clear()
                    endedWithNewline = true
                    if (index + 1 < text.length) {
                        if (text[index + 1] == '\n') index++
                    } else {
                        pendingCarriageReturn = true
                    }
                }
                '\n' -> {
                    flush(pending.toString(), lines)
                    pending.clear()
                    endedWithNewline = true
                }
                else -> {
                    pending.append(ch)
                    endedWithNewline = false
                }
            }
            index++
        }
        return lines
    }

    /** Ends the stream; call after the last [feed]. */
    fun finish(): List<UnfoldedLine> {
        val lines = mutableListOf<UnfoldedLine>()
        // A file ending in a line break has one final empty physical line, which still counts.
        if (pending.isNotEmpty() || endedWithNewline) {
            flush(pending.toString(), lines)
            pending.clear()
        }
        logical?.let { lines += UnfoldedLine(it, logicalNumber) }
        logical = null
        return lines
    }

    private fun flush(physical: String, lines: MutableList<UnfoldedLine>) {
        lineNumber++
        val current = logical
        when {
            current != null && (physical.startsWith(' ') || physical.startsWith('\t')) ->
                logical = current + physical.substring(1)
            current != null && current.endsWith("=") &&
                current.contains("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) ->
                // vCalendar 1.0 folds a quoted-printable value with a trailing "=" instead of a space.
                logical = current.dropLast(1) + physical
            else -> {
                if (current != null) lines += UnfoldedLine(current, logicalNumber)
                logical = physical
                logicalNumber = lineNumber
            }
        }
    }
}

internal fun parseContentLine(unfolded: UnfoldedLine): ContentLine? {
    val line = unfolded.text
    val colon = valueSeparatorIndex(line)
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    var value = line.substring(colon + 1)
    val parts = splitParameters(head)
    if (parts.isEmpty()) return null
    val params = parts.drop(1).mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null
        else part.substring(0, equals).trim().uppercase() to unquoteParameterValue(part.substring(equals + 1))
    }.toMap()
    if (params["ENCODING"].equals("QUOTED-PRINTABLE", ignoreCase = true)) {
        value = decodeQuotedPrintable(value, params["CHARSET"])
    }
    return ContentLine(parts.first().trim().uppercase(), params, value, line, unfolded.lineNumber)
}

/**
 * Index of the `:` that separates the parameters from the value. RFC 5545 escapes a double quote
 * inside a quoted parameter value as `\"`; without honouring the escape, `CN=Bob "B" Smith` and
 * every other stray quote kept the scan looking for a separator past the real one and dropped the
 * whole content line.
 */
private fun valueSeparatorIndex(line: String): Int {
    var quoted = false
    var index = 0
    while (index < line.length) {
        val ch = line[index]
        if (quoted && ch == '\\' && index + 1 < line.length) {
            index += 2
            continue
        }
        when {
            ch == '"' -> quoted = !quoted
            ch == ':' && !quoted -> return index
        }
        index++
    }
    return -1
}

/**
 * A quoted parameter value loses its surrounding quotes and, inside them, the backslash that
 * protects a double quote. Unquoted values are returned as they are.
 */
private fun unquoteParameterValue(text: String): String {
    val trimmed = text.trim()
    if (!isSingleQuotedValue(trimmed)) return trimmed
    val body = trimmed.substring(1, trimmed.length - 1)
    if (!body.contains('\\')) return body
    return buildString(body.length) {
        var index = 0
        while (index < body.length) {
            val ch = body[index]
            if (ch == '\\' && index + 1 < body.length && body[index + 1] == '"') {
                append('"')
                index += 2
            } else {
                append(ch)
                index++
            }
        }
    }
}

/**
 * Whether the value is one quoted string: it opens with a `"` and the quote that closes it is its
 * last character. A comma-separated list of quoted values (`DELEGATED-TO="mailto:a","mailto:b"`) is
 * not one, and keeping its quotes is what lets the list be split on the commas that sit outside them
 * instead of inside a quoted address.
 */
private fun isSingleQuotedValue(text: String): Boolean {
    if (text.length < 2 || text.first() != '"' || text.last() != '"') return false
    var index = 1
    while (index < text.length - 1) {
        when (text[index]) {
            '"' -> return false
            '\\' -> index++
        }
        index++
    }
    return true
}

private fun splitParameters(head: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < head.length) {
        val ch = head[index]
        when {
            // The escaped quote is part of the value, not the end of the quoted section.
            quoted && ch == '\\' && index + 1 < head.length -> {
                current.append(ch).append(head[index + 1])
                index += 2
                continue
            }
            ch == '"' -> {
                quoted = !quoted
                current.append(ch)
            }
            ch == ';' && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
        index++
    }
    parts += current.toString()
    return parts
}
