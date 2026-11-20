package com.oruke.onyx.app.filesystem

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 文本预览有界解码器：支持 BOM 识别、UTF-8 / UTF-16 / GBK / Shift-JIS 自动探测、
 * 二进制防护、截断尾部多字节无损剥离与精确按行数截断。
 */
internal object TextPreviewDecoder {
    private val gbkCharset: Charset = runCatching { Charset.forName("GB18030") }
        .getOrElse { Charset.forName("GBK") }
    private val shiftJisCharset: Charset = runCatching { Charset.forName("Windows-31J") }
        .getOrElse { Charset.forName("Shift_JIS") }

    /**
     * 将读取到的有界字节前缀按指定或自动探测编码解码为文本预览结果。
     *
     * @param bytes 已读取的文件前缀字节。
     * @param requestedEncoding 用户请求的编码（[PreviewTextEncoding.AUTO] 时自动探测）。
     * @param maxLines 允许展示的最大行数。
     * @param bytesTruncated 底层字节流是否已因达到上限而截断。
     * @param totalBytes 已知文件总字节数；未知时为 `null`。
     * @return 解码后的文本结果；检测到疑似二进制文件时返回 [PreviewTextResult.Unavailable]。
     */
    fun decode(
        bytes: ByteArray,
        requestedEncoding: PreviewTextEncoding,
        maxLines: Int,
        bytesTruncated: Boolean,
        totalBytes: Long? = null,
    ): PreviewTextResult {
        if (bytes.isEmpty()) {
            return PreviewTextResult.Text(
                value = "",
                detectedEncoding = resolveEffectiveDefault(requestedEncoding),
                truncated = false,
                lineCount = 0,
                totalBytes = totalBytes ?: 0L,
            )
        }
        val (effectiveEncoding, decodedText) = when (requestedEncoding) {
            PreviewTextEncoding.AUTO -> detectAndDecode(bytes, bytesTruncated)
                ?: return PreviewTextResult.Unavailable

            PreviewTextEncoding.UTF_8 -> {
                val safeLen = if (bytesTruncated) trimIncompleteUtf8TailLength(bytes) else bytes.size
                PreviewTextEncoding.UTF_8 to decodeUtf8(bytes, length = safeLen, stripBom = true)
            }

            PreviewTextEncoding.UTF_16 -> PreviewTextEncoding.UTF_16 to decodeUtf16(bytes, bytesTruncated)

            PreviewTextEncoding.GBK -> {
                val safeLen = resolveValidDoubleByteLength(bytes, gbkCharset, bytesTruncated) ?: bytes.size
                PreviewTextEncoding.GBK to decodeLenient(bytes, gbkCharset, offset = 0, length = safeLen)
            }

            PreviewTextEncoding.SHIFT_JIS -> {
                val safeLen = resolveValidDoubleByteLength(bytes, shiftJisCharset, bytesTruncated) ?: bytes.size
                PreviewTextEncoding.SHIFT_JIS to decodeLenient(bytes, shiftJisCharset, offset = 0, length = safeLen)
            }
        }

        val normalized = decodedText.replace("\r\n", "\n").replace('\r', '\n')
        // 剥离末尾单个标准结束换行，避免 500 行带尾换行的文件被多拆出 1 个空行而误报截断
        val withoutSingleTrailingNewline = if (normalized.endsWith('\n')) {
            normalized.dropLast(1)
        } else {
            normalized
        }
        val allLines = if (withoutSingleTrailingNewline.isEmpty()) {
            listOf("")
        } else {
            withoutSingleTrailingNewline.split('\n')
        }
        val safeMaxLines = maxLines.coerceAtLeast(1)
        val linesTruncated = allLines.size > safeMaxLines
        val boundedLines = if (linesTruncated) allLines.take(safeMaxLines) else allLines
        return PreviewTextResult.Text(
            value = boundedLines.joinToString("\n"),
            detectedEncoding = effectiveEncoding,
            truncated = bytesTruncated || linesTruncated,
            lineCount = boundedLines.size,
            totalBytes = totalBytes,
        )
    }

    /**
     * 自动探测字节编码并解码；疑似二进制内容返回 `null`。
     *
     * @param bytes 原始字节前缀。
     * @param bytesTruncated 是否在尾部被硬截断（截断处可能拆断多字节字符）。
     * @return 探测到的编码与解码文本对；非文本内容返回 `null`。
     */
    private fun detectAndDecode(
        bytes: ByteArray,
        bytesTruncated: Boolean,
    ): Pair<PreviewTextEncoding, String>? {
        // 1. BOM 精确匹配
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            val safeLen = if (bytesTruncated) trimIncompleteUtf8TailLength(bytes) else bytes.size
            return PreviewTextEncoding.UTF_8 to decodeUtf8(bytes, length = safeLen, stripBom = true)
        }
        if (bytes.size >= 2 &&
            ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        ) {
            return PreviewTextEncoding.UTF_16 to decodeUtf16(bytes, bytesTruncated)
        }

        // 2. 排除无 BOM 的常见二进制文件
        if (looksLikeBinary(bytes)) {
            return null
        }

        // 3. 严格 UTF-8 校验（截断时仅校验并解码剥离不完整尾字节后的有效前缀，避免末尾出现 \uFFFD）
        val utf8ValidLength = if (bytesTruncated) trimIncompleteUtf8TailLength(bytes) else bytes.size
        if (isValidCharset(bytes, utf8ValidLength, StandardCharsets.UTF_8)) {
            return PreviewTextEncoding.UTF_8 to decodeUtf8(bytes, length = utf8ValidLength, stripBom = false)
        }

        // 4. 在 GBK 与 Shift-JIS 之间做带截断尾容错的严格校验与字频评分
        val gbkValidLen = resolveValidDoubleByteLength(bytes, gbkCharset, bytesTruncated)
        val sjisValidLen = resolveValidDoubleByteLength(bytes, shiftJisCharset, bytesTruncated)
        return when {
            gbkValidLen != null && sjisValidLen == null ->
                PreviewTextEncoding.GBK to decodeLenient(bytes, gbkCharset, offset = 0, length = gbkValidLen)

            sjisValidLen != null && gbkValidLen == null ->
                PreviewTextEncoding.SHIFT_JIS to decodeLenient(bytes, shiftJisCharset, offset = 0, length = sjisValidLen)

            else -> chooseBetweenGbkAndShiftJis(
                bytes = bytes,
                gbkLen = gbkValidLen ?: bytes.size,
                sjisLen = sjisValidLen ?: bytes.size,
            )
        }
    }

    /** 针对 GBK/Shift-JIS 截断流：尝试完整长度及回退 1~3 字节消除末尾被腰斩的多字节字符。 */
    private fun resolveValidDoubleByteLength(
        bytes: ByteArray,
        charset: Charset,
        bytesTruncated: Boolean,
    ): Int? {
        if (isValidCharset(bytes, bytes.size, charset)) return bytes.size
        if (!bytesTruncated) return null
        val maxTrim = minOf(3, bytes.size - 1)
        for (trim in 1..maxTrim) {
            val candidateLen = bytes.size - trim
            if (isValidCharset(bytes, candidateLen, charset)) {
                return candidateLen
            }
        }
        return null
    }

    /** 通过假名与常用简中/日文字符分布区分 GBK 与 Shift-JIS。 */
    private fun chooseBetweenGbkAndShiftJis(
        bytes: ByteArray,
        gbkLen: Int,
        sjisLen: Int,
    ): Pair<PreviewTextEncoding, String> {
        val gbkText = decodeLenient(bytes, gbkCharset, offset = 0, length = gbkLen)
        val sjisText = decodeLenient(bytes, shiftJisCharset, offset = 0, length = sjisLen)
        val sjisScore = scoreDecodedText(sjisText, preferJapaneseKana = true)
        val gbkScore = scoreDecodedText(gbkText, preferJapaneseKana = false)
        return if (sjisScore > gbkScore) {
            PreviewTextEncoding.SHIFT_JIS to sjisText
        } else {
            PreviewTextEncoding.GBK to gbkText
        }
    }

    /** 计算候选解码文本的自然语言可信度分值。 */
    private fun scoreDecodedText(text: String, preferJapaneseKana: Boolean): Int {
        var score = 0
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\uFFFD' -> score -= 40
                code in 0x3040..0x309F || code in 0x30A0..0x30FF -> {
                    score += if (preferJapaneseKana) 8 else 2
                }
                code in 0x4E00..0x9FFF -> score += 3
                code in 0x3000..0x303F || code in 0xFF01..0xFF5E -> score += 2
                code in 0x0020..0x007E || ch == '\n' || ch == '\r' || ch == '\t' -> score += 1
                code < 0x20 || code in 0xE000..0xF8FF -> score -= 12
            }
        }
        return score
    }

    /** 检查样本前 8KB 是否包含疑似二进制控制字节或 NUL。 */
    private fun looksLikeBinary(bytes: ByteArray): Boolean {
        val sampleLimit = bytes.size.coerceAtMost(8192)
        var suspiciousControlCount = 0
        for (index in 0 until sampleLimit) {
            val unsigned = bytes[index].toInt() and 0xFF
            if (unsigned == 0) return true
            if (unsigned < 0x09 || (unsigned in 0x0E..0x1F && unsigned != 0x1B)) {
                suspiciousControlCount += 1
            }
        }
        return sampleLimit > 0 && suspiciousControlCount * 10 > sampleLimit
    }

    /**
     * 当字节流因上限被硬截断时，精确剥离末尾未完结的 UTF-8 多字节字符，避免误判编码或在末尾产生 `\uFFFD`。
     */
    private fun trimIncompleteUtf8TailLength(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        var pos = bytes.size - 1
        var continuationCount = 0
        while (pos >= 0 && continuationCount < 3 && (bytes[pos].toInt() and 0xC0) == 0x80) {
            pos -= 1
            continuationCount += 1
        }
        if (pos < 0) return bytes.size
        val lead = bytes[pos].toInt() and 0xFF
        val expectedLength = when {
            (lead and 0x80) == 0 -> 1
            (lead and 0xE0) == 0xC0 -> 2
            (lead and 0xF0) == 0xE0 -> 3
            (lead and 0xF8) == 0xF0 -> 4
            else -> return bytes.size
        }
        val availableLength = bytes.size - pos
        return if (availableLength < expectedLength) {
            pos.coerceAtLeast(1)
        } else {
            bytes.size
        }
    }

    /** 使用严格解码器检查给定字节区间是否完全符合指定字符集。 */
    private fun isValidCharset(bytes: ByteArray, length: Int, charset: Charset): Boolean {
        if (length <= 0) return true
        return runCatching {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, 0, length))
            true
        }.getOrDefault(false)
    }

    private fun decodeUtf8(bytes: ByteArray, length: Int, stripBom: Boolean): String {
        val offset = if (
            stripBom &&
            length >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            3
        } else {
            0
        }
        return decodeLenient(bytes, StandardCharsets.UTF_8, offset = offset, length = (length - offset).coerceAtLeast(0))
    }

    private fun decodeUtf16(bytes: ByteArray, bytesTruncated: Boolean): String {
        val (charset, offset) = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                StandardCharsets.UTF_16LE to 2
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                StandardCharsets.UTF_16BE to 2
            else -> StandardCharsets.UTF_16 to 0
        }
        var payloadLen = (bytes.size - offset).coerceAtLeast(0)
        if (bytesTruncated && payloadLen % 2 != 0) {
            payloadLen -= 1
        }
        return decodeLenient(bytes, charset, offset = offset, length = payloadLen)
    }

    private fun decodeLenient(bytes: ByteArray, charset: Charset, offset: Int = 0, length: Int = bytes.size - offset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes, offset, length.coerceAtLeast(0))).toString()
    }

    private fun resolveEffectiveDefault(requested: PreviewTextEncoding): PreviewTextEncoding {
        return if (requested == PreviewTextEncoding.AUTO) PreviewTextEncoding.UTF_8 else requested
    }
}
