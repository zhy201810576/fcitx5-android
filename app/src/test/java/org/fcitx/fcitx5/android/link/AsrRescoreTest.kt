/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertEquals
import org.junit.Test

class AsrRescoreTest {

    private val pinyin = mapOf(
        "我" to "wo",
        "睡" to "shui",
        "觉" to "jue",
        "水" to "shui",
        "饺" to "jiao",
        "了" to "le",
        "你" to "ni",
        "好" to "hao",
        "世" to "shi",
        "界" to "jie",
        "女" to "nv",
    )

    @Test
    fun toPinyin_basic() {
        assertEquals("wo'shui'jue'le", AsrRescore.toPinyin("我睡觉了", pinyin))
    }

    @Test
    fun toPinyin_homophonePair() {
        assertEquals("shui'jiao", AsrRescore.toPinyin("水饺", pinyin))
    }

    @Test
    fun toPinyin_skipsNonHanzi() {
        assertEquals("ni'hao'shi'jie", AsrRescore.toPinyin("你好，世界！", pinyin))
    }

    @Test
    fun toPinyin_empty() {
        assertEquals("", AsrRescore.toPinyin("", pinyin))
        assertEquals("", AsrRescore.toPinyin("，。？！", pinyin))
    }

    @Test
    fun toPinyin_vForUmlaut() {
        assertEquals("nv", AsrRescore.toPinyin("女", pinyin))
    }

    @Test
    fun extractContextWords_takesLastChinese() {
        assertEquals(listOf("这", "个", "方", "案"), AsrRescore.extractContextWords("这个方案"))
        assertEquals(listOf("方", "案"), AsrRescore.extractContextWords("abc123方案"))
        assertEquals(emptyList<String>(), AsrRescore.extractContextWords("abc 123"))
    }
}
