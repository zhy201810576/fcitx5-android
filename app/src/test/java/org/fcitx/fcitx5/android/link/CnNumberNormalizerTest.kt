/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import org.junit.Assert.assertEquals
import org.junit.Test

class CnNumberNormalizerTest {

    private fun norm(s: String) = CnNumberNormalizer.normalize(s)

    // ---- 年份 ----

    @Test
    fun year() {
        assertEquals("2026年", norm("二零二六年"))
        assertEquals("1998年", norm("一九九八年"))
        assertEquals("2026年", norm("二〇二六年"))
        assertEquals("现在是2026年", norm("现在是二零二六年"))
    }

    // ---- 月日 / 月份 / 日期 ----

    @Test
    fun monthDay() {
        assertEquals("10月8号", norm("十月八号"))
        assertEquals("12月31号", norm("十二月三十一号"))
        assertEquals("10月10日", norm("十月十日"))
        assertEquals("1月2号", norm("一月二号"))
    }

    @Test
    fun monthOnly() {
        assertEquals("现在是10月", norm("现在是十月"))
        assertEquals("11月", norm("十一月"))
    }

    @Test
    fun dayOnly() {
        assertEquals("8号", norm("八号"))
        assertEquals("23号", norm("二十三号"))
    }

    // ---- 时刻 ----

    @Test
    fun timeHms() {
        assertEquals("9点08分", norm("九点零八分"))
        assertEquals("9点05分", norm("九点零五分"))
        assertEquals("下午3点23分58秒", norm("下午三点二十三分五十八秒"))
        assertEquals("23点50分", norm("二十三点五十分"))
        assertEquals("5点58分", norm("五点五十八分"))
    }

    @Test
    fun clockWithTenOrZero() {
        assertEquals("现在10点", norm("现在十点"))
        assertEquals("12点整", norm("十二点整"))
        assertEquals("0点开会", norm("零点开会"))
        assertEquals("23点", norm("二十三点"))
    }

    // ---- 完整句（用户核心场景） ----

    @Test
    fun fullDateSentence() {
        assertEquals(
            "今天是2026年10月8号晚上9点08分",
            norm("今天是二零二六年十月八号晚上九点零八分"),
        )
    }

    // ---- 不转（保守边界） ----

    @Test
    fun idiomsAndColloquialUntouched() {
        assertEquals("一模一样", norm("一模一样"))
        assertEquals("三心二意", norm("三心二意"))
        assertEquals("一日千里", norm("一日千里"))
        assertEquals("十分感谢", norm("十分感谢"))
        assertEquals("一点意思", norm("一点意思"))
        assertEquals("三点半", norm("三点半"))
    }

    @Test
    fun bareQuantityUntouched() {
        assertEquals("我有二十三块钱", norm("我有二十三块钱"))
        assertEquals("一百二十三块钱", norm("一百二十三块钱"))
    }

    @Test
    fun alreadyArabicUntouched() {
        assertEquals("今天是2026年10月8号", norm("今天是2026年10月8号"))
    }

    @Test
    fun noNumberUntouched() {
        assertEquals("今天天气不错", norm("今天天气不错"))
        assertEquals("", norm(""))
        assertEquals("好的", norm("好的"))
    }
}
