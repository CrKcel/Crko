package com.crko.domain

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

internal actual fun decodeBytesWithCharset(bytes: ByteArray, charsetName: String): String? = runCatching {
    Charset.forName(charsetName)
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}.getOrNull()
