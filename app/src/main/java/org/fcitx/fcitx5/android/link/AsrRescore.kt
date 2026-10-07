/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.Context
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs

/**
 * 语音识别结果的语言模型重打分纠错（同音字消歧）。
 *
 * Paraformer 是「逐字声学打分、无语言模型」的非自回归模型，对拼音正确但选字错的
 * 同音/近音字仍会出错（「睡觉→水饺」「网安→晚安」）。本类把识别文本转成无调拼音，
 * 再交给 libime 的 PinyinDecoder 整句解码，用语言模型在完整上下文里重选汉字。
 *
 * 边界：只修复「拼音对、字错」的同音错误；不修「误听」导致的拼音级错误（如 d/z
 * 声母混淆）。含英文/数字的句子跳过（避免双语混排被拼音重解码破坏）。
 */
object AsrRescore {

    /** 超过该长度的句子不做纠错（长文本重解码易偏且耗时） */
    private const val MAX_LEN = 50

    /** 从光标前文字提取的语境字数（LM n-gram 阶数通常 ≤4，取 6 个已足够） */
    private const val CONTEXT_MAX_CHARS = 6

    private val lock = Any()

    @Volatile
    private var pinyinMap: Map<String, String>? = null

    /** 用 libime 拼音解码器（内置 zh_CN.lm + sc.dict）把无调拼音整句解码成汉字；失败返回 null。
     *  [contextWords] 为光标前已有文字拆出的语境词，用于整句消歧。
     *  [originalText] 为识别原文——若它在 LM 的 top-N 候选里则原样返回（避免把合理的原文改成更常见的同音字）。 */
    external fun decodePinyin(pinyin: String, contextWords: Array<String>?, originalText: String?): String?

    /** 纠错入口：返回纠错后的文本，无纠错/异常时原样返回。
     *  [contextText] 为光标前已有文字，作为解码的跨句语境。 */
    fun postprocess(ctx: Context, text: String, contextText: String? = null): String {
        if (!MemeBoardPrefs.getAsrRescoreEnabled(ctx)) return text
        if (text.isBlank() || text.length > MAX_LEN) return text
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return text
        val pinyin = toPinyin(text, loadPinyinMap(ctx))
        if (pinyin.isBlank()) return text
        val contextWords = contextText?.let { extractContextWords(it) } ?: emptyList()
        return runCatching {
            decodePinyin(pinyin, contextWords.ifEmpty { null }?.toTypedArray(), text)
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: text
    }

    /** 从光标前文字里取末尾几个汉字作为语境词。 */
    fun extractContextWords(contextText: String): List<String> =
        contextText.filter { it.code in 0x4E00..0x9FFF }
            .takeLast(CONTEXT_MAX_CHARS)
            .map { it.toString() }

    /** 汉字→无调拼音（撇号分隔——libime 的音节分隔符）；非汉字（标点/空白）跳过；多音字取最常见读音。 */
    fun toPinyin(text: String, pinyin: Map<String, String>): String = buildString {
        text.forEach { ch ->
            val py = pinyin[ch.toString()]
            if (py != null) {
                if (isNotEmpty()) append('\'')
                append(py)
            }
        }
    }

    /** 懒加载 assets 里的汉字→拼音表（进程内缓存）。 */
    private fun loadPinyinMap(ctx: Context): Map<String, String> {
        pinyinMap?.let { return it }
        synchronized(lock) {
            pinyinMap?.let { return it }
            val map = HashMap<String, String>(45000)
            runCatching {
                ctx.applicationContext.assets
                    .open("memeboard/pinyin-table.txt")
                    .bufferedReader(Charsets.UTF_8)
                    .useLines { lines ->
                        lines.forEach { line ->
                            val idx = line.indexOf('\t')
                            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
                        }
                    }
            }
            pinyinMap = map
            return map
        }
    }
}
