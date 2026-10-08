/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

/**
 * 中文数字 → 阿拉伯数字的保守归一化（语音识别 ITN）。
 *
 * Paraformer 对数字/日期时间的识别输出为中文数字（如「二零二六年十月八号」），
 * 上屏前把「高置信的数字语境」转回阿拉伯数字。保守策略：只转以下语境，其余一律
 * 原样返回，避免误伤成语（一模一样）、口语（十分感谢）、孤立数量词（一百二十三）：
 *
 * 1. 年份 ：[零〇一二三四五六七八九]{2,4}年      二零二六 → 2026
 * 2. 月日 ：X月Y号 / X月Y日                       十月八号 → 10月8号
 * 3. 月份 ：X月（后无号/日时）                     十月 → 10月
 * 4. 日期 ：X号（前无月时）                        二十三号 → 23号
 * 5. 时刻 ：X点Y分 / X点Y分Z秒                    九点零八分 → 9点08分
 * 6. 整点 ：含「十/零」的 X点                      十点/十二点/零点 → 10点/12点/0点
 *
 * 纯函数、无状态、无依赖，可直接 JVM 单测（见 [CnNumberNormalizerTest]）。
 */
object CnNumberNormalizer {

    /** 完整时刻：X点Y分，可带 Z秒（「分」仅在「点」之后才转，避开副词「十分」）。
     *  小时/分钟/秒均允许 {1,3}：覆盖「二十三点」「五十八分」这类含「十」的三字段。 */
    private val TIME_HMS =
        Regex("([零〇一二三四五六七八九十]{1,3})点([零〇一二三四五六七八九十]{1,3})分(?:([零〇一二三四五六七八九十]{1,3})秒)?")

    /** 年份：2-4 位纯中文数字（不含「十」） */
    private val YEAR = Regex("([零〇一二三四五六七八九]{2,4})年")

    /** 月+日/号：十月八号 / 十月十日 */
    private val MONTH_DAY =
        Regex("([零〇一二三四五六七八九十]{1,2})月([零〇一二三四五六七八九十]{1,3})([号日])")

    /** 单独月份（后无号/日） */
    private val MONTH = Regex("([零〇一二三四五六七八九十]{1,2})月")

    /** 单独日期（前无月） */
    private val DAY = Regex("([零〇一二三四五六七八九十]{1,3})号")

    /** 单独整点（仅转换含「十/零」的时段，单字 1-9 点歧义大、保留原文） */
    private val CLOCK = Regex("([零〇一二三四五六七八九十]{1,3})点")

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = text
        result = TIME_HMS.replace(result) { m -> timeHms(m) }
        result = YEAR.replace(result) { m -> m.digitsYear() }
        result = MONTH_DAY.replace(result) { m -> m.monthDay() }
        result = MONTH.replace(result) { m -> m.numberWithSuffix("月") }
        result = DAY.replace(result) { m -> m.numberWithSuffix("号") }
        result = CLOCK.replace(result) { m -> m.clock() }
        return result
    }

    private fun timeHms(m: MatchResult): String {
        val hour = numberToArabic(m.groupValues[1]) ?: return m.value
        val minute = numberToArabic(m.groupValues[2]) ?: return m.value
        val secondGroup = m.groupValues[3]
        val second = if (secondGroup.isEmpty()) null else numberToArabic(secondGroup) ?: return m.value
        return buildString {
            append(hour).append("点").append(minute).append("分")
            if (second != null) append(second).append("秒")
        }
    }

    private fun MatchResult.digitsYear(): String =
        digitsToArabic(groupValues[1])?.let { it + "年" } ?: value

    private fun MatchResult.monthDay(): String {
        val month = numberToArabic(groupValues[1]) ?: return value
        val day = numberToArabic(groupValues[2]) ?: return value
        return month + "月" + day + groupValues[3]
    }

    private fun MatchResult.numberWithSuffix(suffix: String): String =
        numberToArabic(groupValues[1])?.let { it + suffix } ?: value

    private fun MatchResult.clock(): String {
        val raw = groupValues[1]
        // 单字 1-9 点（一点/两点…）歧义大（「一点意思」），保留原文
        if (!raw.any { it == '十' || it == '零' || it == '〇' }) return value
        return numberToArabic(raw)?.let { it + "点" } ?: value
    }

    /** 纯中文数字串逐位转阿拉伯数字；含非法字符返回 null */
    private fun digitsToArabic(s: String): String? {
        if (s.isEmpty()) return null
        val sb = StringBuilder(s.length)
        for (c in s) {
            val d = cnDigitToArab(c) ?: return null
            sb.append(d)
        }
        return sb.toString()
    }

    /** 中文数值（≤99，可含「十」）转阿拉伯数字；格式异常返回 null */
    private fun numberToArabic(s: String): String? {
        if (s.isEmpty()) return null
        if (!s.contains('十')) return digitsToArabic(s)
        val idx = s.indexOf('十')
        if (s.indexOf('十', idx + 1) >= 0) return null // 多个「十」视为非法
        val tens = when (val left = s.substring(0, idx)) {
            "" -> 1
            else -> digitsToArabic(left)?.takeIf { it.length == 1 }?.toInt() ?: return null
        }
        val ones = when (val right = s.substring(idx + 1)) {
            "" -> 0
            else -> digitsToArabic(right)?.takeIf { it.length == 1 }?.toInt() ?: return null
        }
        return (tens * 10 + ones).toString()
    }

    private fun cnDigitToArab(c: Char): Char? = when (c) {
        '零', '〇' -> '0'
        '一' -> '1'
        '二' -> '2'
        '三' -> '3'
        '四' -> '4'
        '五' -> '5'
        '六' -> '6'
        '七' -> '7'
        '八' -> '8'
        '九' -> '9'
        else -> null
    }
}
