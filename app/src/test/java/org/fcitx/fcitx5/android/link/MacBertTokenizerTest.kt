/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacBertTokenizerTest {

    /** 迷你词表：只含特殊 token 与用例用到的汉字，用于验证对齐逻辑而非词表内容。 */
    private fun miniVocab(): Pair<Map<String, Int>, Array<String>> {
        val tokens = arrayOf(
            "[PAD]", "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "##ab",
            "你", "好", "吗", "今", "天", "新", "情", "很", "晚", "安",
        )
        // 用标准 BERT 特殊 token id 更贴近真实词表（内容 token 放在 200+ 区，需足够大）
        val idToToken = Array(256) { "[UNK]" }
        idToToken[0] = "[PAD]"
        idToToken[100] = "[UNK]"
        idToToken[101] = "[CLS]"
        idToToken[102] = "[SEP]"
        tokens.forEachIndexed { idx, tok ->
            if (tok !in listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]")) {
                idToToken[200 + idx] = tok // 避开特殊 id 区
            }
        }
        val tokenToId = HashMap<String, Int>()
        idToToken.forEachIndexed { idx, tok -> if (tok != "[UNK]" || idx == 100) tokenToId[tok] = idx }
        tokenToId["[UNK]"] = 100
        return tokenToId to idToToken
    }

    @Test
    fun pureChineseAlignsOneCharPerToken() {
        val (tokToId, idToTok) = miniVocab()
        val tok = MacBertTokenizer(tokToId, idToTok)
        val r = tok.tokenize("今天心情很好")
        assertEquals(MacBertTokenizer.CLS_ID, r.inputIds.first())
        assertEquals(MacBertTokenizer.SEP_ID, r.inputIds.last())
        // 6 个汉字 + [CLS] + [SEP]
        assertEquals(8, r.inputIds.size)
        // 每个汉字映射到 i+1（无标点被丢弃）
        for (i in 0 until 6) assertEquals(i + 1, r.charToTokenPos[i])
        assertEquals(tokToId["今"], r.inputIds[1])
        assertEquals(tokToId["天"], r.inputIds[2])
        assertEquals(tokToId["好"], r.inputIds[6])
    }

    @Test
    fun punctuationIsDroppedAndAlignmentSkipsIt() {
        val (tokToId, idToTok) = miniVocab()
        val tok = MacBertTokenizer(tokToId, idToTok)
        val r = tok.tokenize("你好吗？")
        // 标点不出 token：3 汉字 + [CLS] + [SEP]
        assertEquals(5, r.inputIds.size)
        assertArrayEquals(intArrayOf(1, 2, 3, -1), r.charToTokenPos)
        assertEquals(tokToId["你"], r.inputIds[1])
        assertEquals(tokToId["好"], r.inputIds[2])
        assertEquals(tokToId["吗"], r.inputIds[3])
    }

    @Test
    fun unknownCharFallsBackToUnkId() {
        val (tokToId, idToTok) = miniVocab()
        val tok = MacBertTokenizer(tokToId, idToTok)
        // 「吃/饿」不在迷你词表，但仍是 CJK，应映射到 [UNK]
        val r = tok.tokenize("吃饿")
        assertEquals(MacBertTokenizer.UNK_ID, r.inputIds[1])
        assertEquals(MacBertTokenizer.UNK_ID, r.inputIds[2])
    }

    @Test
    fun cjkAndSingleCjkChecks() {
        assertTrue(MacBertTokenizer.isCjkChar('你'))
        assertTrue(MacBertTokenizer.isCjkChar('安'))
        assertFalse(MacBertTokenizer.isCjkChar('a'))
        assertFalse(MacBertTokenizer.isCjkChar('，'))
        assertFalse(MacBertTokenizer.isCjkChar('。'))
        assertFalse(MacBertTokenizer.isCjkChar('1'))
        assertTrue(MacBertTokenizer.isSingleCjk("心"))
        assertFalse(MacBertTokenizer.isSingleCjk("##ab"))
        assertFalse(MacBertTokenizer.isSingleCjk("[UNK]"))
        assertFalse(MacBertTokenizer.isSingleCjk("你好"))
    }

    @Test
    fun loadVocabFromLines() {
        // 按行序编号（BERT vocab.txt 本身已把 [UNK]/[CLS] 等排在标准位置，loadVocab 只负责按行序建映射）
        val lines = sequenceOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "的", "一", "是")
        val (tokToId, idToTok) = MacBertTokenizer.loadVocab(lines)
        assertEquals(8, idToTok.size)
        assertEquals(0, tokToId["[PAD]"])
        assertEquals(1, tokToId["[UNK]"])
        assertEquals(3, tokToId["[SEP]"])
        assertEquals("的", idToTok[5])
    }
}
