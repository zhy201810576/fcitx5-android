/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCorrectionTest {

    @Test
    fun buildCorrectionPrompt_containsSystemAndRecognition() {
        val prompt = AsrRescore.buildCorrectionPrompt("我睡觉了", null)
        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("语言专家"))
        assertTrue(prompt.contains("识别：我睡觉了"))
        assertTrue(prompt.contains("<|im_start|>assistant"))
    }

    @Test
    fun buildCorrectionPrompt_includesPronounRule() {
        val prompt = AsrRescore.buildCorrectionPrompt("他说", null)
        assertTrue(prompt.contains("第三人称"))
        assertTrue(prompt.contains("她"))
        assertTrue(prompt.contains("他"))
        assertTrue(prompt.contains("它"))
    }

    @Test
    fun buildCorrectionPrompt_injectsContext() {
        val prompt = AsrRescore.buildCorrectionPrompt("水饺", "我想吃")
        assertTrue(prompt.contains("前文：我想吃"))
        assertTrue(prompt.contains("识别：水饺"))
    }

    @Test
    fun buildCorrectionPrompt_noContextOmitsPrefix() {
        val prompt = AsrRescore.buildCorrectionPrompt("晚安", "   ")
        assertFalse(prompt.contains("前文："))
    }

    @Test
    fun acceptCorrection_sameText() {
        assertEquals("我睡觉了", AsrRescore.acceptCorrection("我睡觉了", "我睡觉了"))
    }

    @Test
    fun acceptCorrection_smallEditWithinThreshold() {
        // 「网安」→「晚安」：仅一字同音替换，阈值内应采纳
        assertEquals("晚安", AsrRescore.acceptCorrection("网安", "晚安"))
    }

    @Test
    fun acceptCorrection_largeEditRejected() {
        // 大幅改写（幻觉/解释性输出）应被拒绝，保留原文
        assertNull(
            AsrRescore.acceptCorrection(
                "我睡觉了",
                "好的，我已将文本纠正为：我睡觉了，希望对你有帮助",
            ),
        )
    }

    @Test
    fun acceptCorrection_blankOutputRejected() {
        assertNull(AsrRescore.acceptCorrection("我睡觉了", "   "))
    }

    @Test
    fun levenshteinDistance_basic() {
        assertEquals(0, AsrRescore.levenshteinDistance("abc", "abc"))
        assertEquals(1, AsrRescore.levenshteinDistance("abc", "abd"))
        assertEquals(1, AsrRescore.levenshteinDistance("网安", "晚安"))
        assertEquals(3, AsrRescore.levenshteinDistance("abc", ""))
    }
}
