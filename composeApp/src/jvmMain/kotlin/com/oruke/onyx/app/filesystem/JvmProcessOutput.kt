package com.oruke.onyx.app.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun ByteArray.decodePlatformProcessOutput(): String {
    if (isEmpty()) return ""
    decodeWithBom()?.let { value -> return value }
    decodeStrict(StandardCharsets.UTF_8)?.let { value -> return value }
    for (charset in platformProcessCharsets()) {
        decodeStrict(charset)?.let { value -> return value }
    }
    return String(this, platformProcessCharsets().firstOrNull() ?: Charset.defaultCharset())
}

private fun ByteArray.decodeWithBom(): String? {
    return when {
        size >= 3 &&
            this[0] == 0xEF.toByte() &&
            this[1] == 0xBB.toByte() &&
            this[2] == 0xBF.toByte() ->
            String(copyOfRange(3, size), StandardCharsets.UTF_8)

        size >= 2 &&
            this[0] == 0xFF.toByte() &&
            this[1] == 0xFE.toByte() ->
            String(copyOfRange(2, size), StandardCharsets.UTF_16LE)

        size >= 2 &&
            this[0] == 0xFE.toByte() &&
            this[1] == 0xFF.toByte() ->
            String(copyOfRange(2, size), StandardCharsets.UTF_16BE)

        else -> null
    }
}

private fun ByteArray.decodeStrict(charset: Charset): String? {
    return try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun platformProcessCharsets(): List<Charset> {
    return buildList {
        System.getProperty("native.encoding").toCharsetOrNull()?.let(::add)
        System.getProperty("sun.jnu.encoding").toCharsetOrNull()?.let(::add)
        add(Charset.defaultCharset())
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            WINDOWS_FALLBACK_CHARSETS.mapNotNullTo(this) { name -> name.toCharsetOrNull() }
        }
    }.distinct()
}

private fun String?.toCharsetOrNull(): Charset? {
    if (isNullOrBlank()) return null
    return runCatching { Charset.forName(this) }.getOrNull()
}

private val WINDOWS_FALLBACK_CHARSETS = listOf(
    "GBK",
    "windows-1252",
    "Shift_JIS",
    "windows-949",
    "Big5",
)
