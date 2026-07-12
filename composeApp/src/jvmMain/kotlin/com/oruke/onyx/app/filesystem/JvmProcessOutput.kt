package com.oruke.onyx.app.filesystem

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 按 BOM、UTF-8 和平台候选字符集依次解码外部进程输出。
 *
 * @return 解码后的文本；空字节数组返回空字符串。
 */
internal fun ByteArray.decodePlatformProcessOutput(): String {
    val platformCharsets = platformProcessCharsets()
    val decoded = decodeWithBom()
        ?: decodeStrict(StandardCharsets.UTF_8)
        ?: platformCharsets.firstNotNullOfOrNull { charset -> decodeStrict(charset) }
    return when {
        isEmpty() -> ""
        decoded != null -> decoded
        else -> String(this, platformCharsets.firstOrNull() ?: Charset.defaultCharset())
    }
}

/**
 * 根据 Unicode BOM 识别并解码字节数组。
 *
 * @return 能识别 BOM 时返回文本，否则返回 `null`。
 */
private fun ByteArray.decodeWithBom(): String? {
    return when {
        size >= UTF_8_BOM_SIZE &&
            this[0] == 0xEF.toByte() &&
            this[1] == 0xBB.toByte() &&
            this[2] == 0xBF.toByte() ->
            String(copyOfRange(UTF_8_BOM_SIZE, size), StandardCharsets.UTF_8)

        size >= 2 &&
            this[0] == 0xFF.toByte() &&
            this[1] == 0xFE.toByte() ->
            String(copyOfRange(UTF_16_BOM_SIZE, size), StandardCharsets.UTF_16LE)

        size >= 2 &&
            this[0] == 0xFE.toByte() &&
            this[1] == 0xFF.toByte() ->
            String(copyOfRange(UTF_16_BOM_SIZE, size), StandardCharsets.UTF_16BE)

        else -> null
    }
}

/**
 * 使用严格错误策略尝试按指定字符集解码。
 *
 * @param charset 候选字符集。
 * @return 解码成功的文本；字节非法时返回 `null`。
 */
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

/**
 * 构建当前平台可能使用的进程输出字符集，顺序即尝试优先级。
 *
 * @return 去重后的字符集列表。
 */
private fun platformProcessCharsets(): List<Charset> {
    return buildList {
        System.getProperty("native.encoding").toCharsetOrNull()?.let(::add)
        System.getProperty("sun.jnu.encoding").toCharsetOrNull()?.let(::add)
        add(Charset.defaultCharset())
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            windowsFallbackCharsets.mapNotNullTo(this) { name -> name.toCharsetOrNull() }
        }
    }.distinct()
}

/**
 * 将系统属性中的字符集名称安全转换为字符集对象。
 *
 * @return 名称有效时返回字符集，否则返回 `null`。
 */
private fun String?.toCharsetOrNull(): Charset? {
    if (isNullOrBlank()) return null
    return runCatching { Charset.forName(this) }.getOrNull()
}

/** Windows 控制台常见的后备字符集名称。 */
private val windowsFallbackCharsets = listOf(
    "GBK",
    "windows-1252",
    "Shift_JIS",
    "windows-949",
    "Big5",
)

/** UTF-8 BOM 所占字节数。 */
private const val UTF_8_BOM_SIZE = 3

/** UTF-16 BOM 所占字节数。 */
private const val UTF_16_BOM_SIZE = 2
