/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrEvalCollectorTest {

    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun sampleJsonOmitsNulls() {
        val s = AsrEvalSample(ts = 1L, raw = "你好", mode = "none", output = "你好")
        val str = json.encodeToString(s)
        assertTrue(str.contains("\"raw\":\"你好\""))
        assertTrue(str.contains("\"mode\":\"none\""))
        assertFalse(str.contains("context"))
        assertFalse(str.contains("cscEdits"))
    }

    @Test
    fun sampleJsonRoundTrip() {
        val s = AsrEvalSample(
            ts = 1700000000000L,
            raw = "今天新情很好",
            context = "晚安",
            mode = "csc",
            output = "今天心情很好",
            cscEdits = "2:新->心",
        )
        val back = json.decodeFromString<AsrEvalSample>(json.encodeToString(s))
        assertEquals(s.ts, back.ts)
        assertEquals(s.raw, back.raw)
        assertEquals(s.context, back.context)
        assertEquals(s.mode, back.mode)
        assertEquals(s.output, back.output)
        assertEquals(s.cscEdits, back.cscEdits)
    }

    @Test
    fun sampleJsonIgnoresLegacyLlmField() {
        // 移除 LLM 前采集的旧样本含 llmRaw 字段，ignoreUnknownKeys 应忽略而不抛异常
        val legacy = """{"ts":1,"raw":"你好","mode":"llm","llmRaw":"你好吗","output":"你好"}"""
        val back = json.decodeFromString<AsrEvalSample>(legacy)
        assertEquals("你好", back.raw)
        assertEquals("你好", back.output)
    }

    @Test
    fun summarizeCounts() {
        val samples = listOf(
            // 无纠错
            AsrEvalSample(1, "随便", null, "none", "随便"),
            // libime round-trip 改动（过度纠正示例）
            AsrEvalSample(2, "你说是吧", null, "libime", "你说十八"),
        )
        val sum = AsrEvalCollector.summarize(samples)
        assertEquals(2, sum.total)
        assertEquals(1, sum.libimeChanged)
        assertEquals(1, sum.unchanged)
    }

    @Test
    fun summarizeCountsWithCsc() {
        val samples = listOf(
            // csc 替换：新→心
            AsrEvalSample(1, "今天新情很好", null, "csc", "今天心情很好", "2:新->心"),
            // csc 引擎未就绪 / 无替换：原文返回
            AsrEvalSample(2, "你好吗", null, "csc", "你好吗", null),
            // 无纠错
            AsrEvalSample(3, "随便", null, "none", "随便", null),
        )
        val sum = AsrEvalCollector.summarize(samples)
        assertEquals(3, sum.total)
        assertEquals(2, sum.cscAttempted)
        assertEquals(1, sum.cscAccepted)
        assertEquals(2, sum.unchanged)
    }
}
