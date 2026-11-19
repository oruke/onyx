package com.oruke.onyx.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 验证 Samba / macOS SMB SFM 私用区字符（U+F001..U+F029）还原逻辑。 */
class SfmDisplayTextTest {
    /** 验证尾随句点（U+F029）与尾随空格（U+F028）可还原为可读字符，消除豆腐块乱码。 */
    @Test
    fun decodesTrailingPeriodAndSpace() {
        val rawFolder = "我说过要戴安全套吧..\uF029"
        assertTrue(rawFolder.containsSfmMappedChars())
        assertEquals("我说过要戴安全套吧...", rawFolder.decodeSfmDisplayText())

        val rawTrailingSpace = "Album Title\uF028"
        assertEquals("Album Title ", rawTrailingSpace.decodeSfmDisplayText())
    }

    /** 验证全部可打印 SFM 符号（U+F020..U+F029）与控制字符（U+F001..U+F01F）映射。 */
    @Test
    fun decodesReservedSymbolsAndStripsControlCharacters() {
        val allSymbols = "\uF020\uF021\uF022\uF023\uF024\uF025\uF026\uF027\uF028\uF029"
        assertEquals("\"*:<>?/| .", allSymbols.decodeSfmDisplayText())

        val withCr = "Digital Lover (なかじまゆか)\uF00D\uF00D"
        assertEquals("Digital Lover (なかじまゆか)", withCr.decodeSfmDisplayText())

        val withTab = "part1\uF009part2"
        assertEquals("part1 part2", withTab.decodeSfmDisplayText())
    }

    /** 验证普通文件名走零分配快速返回，且全控制字符极端情况不会退化为空串。 */
    @Test
    fun preservesPlainStringsAndHandlesControlOnlyEdgeCase() {
        val plain = "大哥哥...你是一个人吗？"
        assertFalse(plain.containsSfmMappedChars())
        assertSame(plain, plain.decodeSfmDisplayText())

        val controlOnly = "\uF00D\uF00A"
        assertEquals(controlOnly, controlOnly.decodeSfmDisplayText())
    }
}
