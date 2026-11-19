package com.oruke.onyx.core.model

/** SFM（Services for Macintosh / Samba vfs_fruit）私用区映射起始码位。 */
private const val SFM_RANGE_START: Char = '\uF001'

/** SFM 私用区映射结束码位（尾随句点）。 */
private const val SFM_RANGE_END: Char = '\uF029'

/** SFM 控制字符映射结束码位（对应 ASCII 0x1F）。 */
private const val SFM_CONTROL_END: Char = '\uF01F'

/** SFM 水平制表符映射码位（对应 ASCII 0x09）。 */
private const val SFM_TAB: Char = '\uF009'

/** SFM 可打印保留符号表（对应 U+F020..U+F029）。 */
private val SFM_PRINTABLE_SYMBOLS: CharArray = charArrayOf(
    '"',  // U+F020 SFM_DOUBLEQUOTE
    '*',  // U+F021 SFM_ASTERISK
    ':',  // U+F022 SFM_COLON
    '<',  // U+F023 SFM_LESSTHAN
    '>',  // U+F024 SFM_GRTRTHAN
    '?',  // U+F025 SFM_QUESTION
    '/',  // U+F026 SFM_SLASH
    '|',  // U+F027 SFM_PIPE
    ' ',  // U+F028 SFM_SPACE（POSIX 文件名末尾空格）
    '.',  // U+F029 SFM_PERIOD（POSIX 文件名末尾句点）
)

/** SFM 可打印保留符号起始码位（U+F020）。 */
private const val SFM_PRINTABLE_START: Char = '\uF020'

/**
 * 判断字符串是否包含 Samba / macOS SMB 客户端使用的 SFM 私用区映射字符（`U+F001..U+F029`）。
 *
 * @return 至少包含一个 SFM 私用区字符时返回 `true`。
 */
fun String.containsSfmMappedChars(): Boolean {
    var found = false
    for (index in indices) {
        if (this[index] in SFM_RANGE_START..SFM_RANGE_END) {
            found = true
            break
        }
    }
    return found
}

/**
 * 将文件名或目录标签中的 SFM 私用区字符（`U+F001..U+F029`）还原为可读展示文本。
 *
 * Linux / macOS 允许文件名以句点 `.` 或空格结尾，也允许包含 `?`、`:`、`*`、`"` 等 Win32 保留字符。
 * Samba（`vfs_fruit`）及 SMB 协议会将这些字符映射到 Unicode 私用区 `U+F001..U+F029`，导致普通字体渲染为豆腐块 `▯`。
 * 本函数**仅用于只读界面展示与搜索匹配**，底层文件系统路径（`VFile.location` / `VFile.name`）必须保持原始码位不变。
 *
 * @return 还原后的可读展示字符串；不含 SFM 字符时零分配原样返回。
 */
fun String.decodeSfmDisplayText(): String {
    if (!containsSfmMappedChars()) return this
    val builder = StringBuilder(length)
    for (index in indices) {
        val ch = this[index]
        when {
            ch == SFM_TAB -> builder.append(' ')
            ch in SFM_RANGE_START..SFM_CONTROL_END -> Unit
            ch in SFM_PRINTABLE_START..SFM_RANGE_END -> {
                builder.append(SFM_PRINTABLE_SYMBOLS[ch - SFM_PRINTABLE_START])
            }
            else -> builder.append(ch)
        }
    }
    return if (builder.isEmpty() && isNotEmpty()) this else builder.toString()
}
