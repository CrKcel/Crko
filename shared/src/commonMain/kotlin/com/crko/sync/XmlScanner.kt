package com.crko.sync

/**
 * Minimal namespace-agnostic XML reader shared by the CalDAV client and the subscription fetcher.
 * CalDAV servers answer with arbitrary namespace prefixes, so elements are matched by local name
 * only; text values keep CDATA and character references resolved.
 */

/** An element found by [findElement]: decoded text, raw inner content, attributes, and the index after it. */
internal class FoundElement(
    val value: String,
    val inner: String,
    val end: Int,
    /** Tag attributes keyed by their lower-cased local name, e.g. `name` of `name="VEVENT"`. */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * First element whose local name is [name] at or after [from]. Returns its inner text (character
 * references and CDATA resolved) and the offset just past its closing tag. Attributes and the
 * namespace prefix are ignored, matching how CalDAV clients must read server answers.
 */
internal fun findElement(xml: String, from: Int, name: String): FoundElement? {
    var index = from
    while (index < xml.length) {
        val open = xml.indexOf('<', index)
        if (open < 0) return null
        val close = xml.indexOf('>', open)
        if (close < 0) return null
        val tag = xml.substring(open + 1, close)
        when {
            tag.startsWith('?') || tag.startsWith('!') -> index = close + 1
            tag.startsWith("/") -> index = close + 1
            else -> {
                val localName = tag.substringBefore(' ').substringBefore('/').substringAfterLast(':')
                if (localName == name) {
                    // A self-closing element (`<d:sync-token/>`) carries no content but still means
                    // "present and empty"; skipping it would make every sync fall back to full.
                    if (tag.endsWith("/")) return FoundElement("", "", close + 1, parseAttributes(tag))
                    val innerEnd = findCloseTag(xml, close + 1, name) ?: return null
                    val raw = xml.substring(close + 1, innerEnd.first)
                    return FoundElement(decodeXmlText(raw), raw, innerEnd.second, parseAttributes(tag))
                }
                index = close + 1
            }
        }
    }
    return null
}

/** Every element with local name [name] inside [xml] at or after [from], in document order. */
internal fun findAllElements(xml: String, from: Int, name: String): List<FoundElement> {
    val found = mutableListOf<FoundElement>()
    var index = from
    while (true) {
        val element = findElement(xml, index, name) ?: return found
        found += element
        index = element.end
    }
}

/** Close tag of the element opened before [from]; returns its position and the offset past it. */
private fun findCloseTag(xml: String, from: Int, name: String): Pair<Int, Int>? {
    var index = from
    var depth = 1
    while (index < xml.length) {
        val open = xml.indexOf('<', index)
        if (open < 0) return null
        val close = xml.indexOf('>', open)
        if (close < 0) return null
        val tag = xml.substring(open + 1, close)
        when {
            tag.startsWith('/') -> {
                if (tag.substring(1).trim().substringAfterLast(':') == name) {
                    depth--
                    if (depth == 0) return open to close + 1
                }
            }
            !tag.endsWith("/") -> {
                val localName = tag.substringBefore(' ').substringAfterLast(':')
                if (localName == name) depth++
            }
        }
        index = close + 1
    }
    return null
}

/** Lower-cased attribute local names to values of one open tag, e.g. `<d:comp name="VEVENT">`. */
internal fun parseAttributes(tag: String): Map<String, String> {
    val withoutName = tag.substringAfter(' ').removeSuffix("/")
    if (withoutName.isEmpty()) return emptyMap()
    val attributes = mutableMapOf<String, String>()
    Regex("([\\w:-]+)\\s*=\\s*\"([^\"]*)\"").findAll(withoutName).forEach { match ->
        attributes[match.groupValues[1].substringAfterLast(':').lowercase()] = match.groupValues[2]
    }
    Regex("([\\w:-]+)\\s*=\\s*'([^']*)'").findAll(withoutName).forEach { match ->
        val key = match.groupValues[1].substringAfterLast(':').lowercase()
        if (key !in attributes) attributes[key] = match.groupValues[2]
    }
    return attributes
}

/** Resolves character references and CDATA sections of an XML text node, keeping the layout. */
internal fun decodeXmlText(raw: String): String {
    if (!raw.contains('&') && !raw.contains("<![CDATA[")) {
        return raw
    }
    var text = raw
    // `RegexOption.DOT_MATCHES_ALL` is JVM-only; the inline (?s) flag is its cross-platform form.
    val cdata = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")
    text = cdata.replace(text) { match -> xmlEscape(match.groupValues[1]) }
    return text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(numericReference) { match -> numericReferenceText(match.groupValues[1]) ?: match.value }
        .replace("&amp;", "&")
}

/** Escapes XML text content on the wire. */
internal fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

/** Decimal (`&#233;`) and hexadecimal (`&#xE9;`) character references in one pattern. */
private val numericReference = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")

/** Text of one numeric character reference; null (kept verbatim) when it names no character. */
private fun numericReferenceText(token: String): String? {
    val code = if (token.startsWith('x')) {
        token.substring(1).toLongOrNull(16)?.takeIf { it in 0..0x10FFFF.toLong() }
    } else {
        token.toLongOrNull()?.takeIf { it in 0..0x10FFFF.toLong() }
    } ?: return null
    return if (code < 0x10000) {
        Char(code.toInt()).toString()
    } else {
        // Outside the basic multilingual plane the value is a code point, not a code unit.
        val offset = code.toInt() - 0x10000
        charArrayOf(Char(0xD800 + (offset shr 10)), Char(0xDC00 + (offset and 0x3FF))).concatToString()
    }
}
