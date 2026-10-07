/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.Context
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs

/**
 * 语音识别结果纠错（同音字消歧）。
 *
 * Paraformer 是「逐字声学打分、无语言模型」的非自回归模型，对拼音正确但选字错的
 * 同音/近音字仍会出错（「睡觉→水饺」「网安→晚安」「你好吗→你好嘛」）。
 *
 * 首选方案：端侧小 LLM（Qwen2.5-1.5B）**选择性纠错**——只改明显同音/近音错字、
 * 保持原意、不整句重写（见 [correctWithLlm] + [acceptCorrection] 安全门控），
 * 避免「过度纠错」把对的改错（这是旧 libime round-trip 的失败点）。
 *
 * 备选/对比方案：libime PinyinDecoder pinyin round-trip（默认关闭，见 [rescoreWithLibime]）。
 *
 * 边界：只修复「拼音对、字错」的同音错误；不修「误听」导致的拼音级错误（如 d/z
 * 声母混淆）。含英文/数字的句子跳过。
 */
object AsrRescore {

    /** 超过该长度的句子不做纠错（长文本重解码易偏且耗时） */
    private const val MAX_LEN = 50

    /** 从光标前文字提取的语境字数（LM n-gram 阶数通常 ≤4，取 6 个已足够） */
    private const val CONTEXT_MAX_CHARS = 6

    /** 端侧 LLM 纠错的 system 约束（中文语言专家：选择性纠错 + 第三人称代词按语境区分） */
    private const val SYSTEM_PROMPT =
        "你是资深中文语言专家，负责纠正中文语音识别文本中的用字错误。请严格按以下规则：\n" +
            "1. 只纠正同音字/近音字的用字错误，保持原意，不增删字词，不改写句子结构。\n" +
            "2. 特别注意第三人称代词：结合上下文判断性别——指女性（如女孩、她、妈妈、女士、小姐、妻子等）用「她」，指男性用「他」，指动物或事物用「它」。他/她/它发音相同，是常见识别错误，务必按语境纠正。\n" +
            "3. 只输出纠正后的文本本身，不添加任何解释。"

    private val lock = Any()

    @Volatile
    private var pinyinMap: Map<String, String>? = null

    /** 用 libime 拼音解码器（内置 zh_CN.lm + sc.dict）把无调拼音整句解码成汉字；失败返回 null。
     *  [contextWords] 为光标前已有文字拆出的语境词，用于整句消歧。
     *  [originalText] 为识别原文——若它在 LM 的 top-N 候选里则原样返回（避免把合理的原文改成更常见的同音字）。 */
    external fun decodePinyin(pinyin: String, contextWords: Array<String>?, originalText: String?): String?

    /** 纠错入口：返回纠错后的文本，无纠错/异常时原样返回。
     *  [contextText] 为光标前已有文字，作为跨句语境。 */
    fun postprocess(ctx: Context, text: String, contextText: String? = null): String {
        if (text.isBlank()) return text
        // 端侧 LLM 选择性纠错（默认开）：只改明显同音/近音错字，不整句重写
        if (MemeBoardPrefs.getLlmCorrectEnabled(ctx)) {
            correctWithLlm(ctx, text, contextText)?.let { return it }
        }
        // 旧 libime pinyin round-trip（默认关，保留作对比回退）
        if (MemeBoardPrefs.getAsrRescoreEnabled(ctx)) {
            return rescoreWithLibime(ctx, text, contextText)
        }
        return text
    }

    /** 端侧 LLM 选择性纠错；模型未就绪 / 失败 / 超出边界时返回 null（降级为不纠错）。 */
    private fun correctWithLlm(ctx: Context, text: String, contextText: String?): String? {
        if (text.length > MAX_LEN) return null
        if (text.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return null
        val engine = LlmEngineController.ensureLoaded(ctx) ?: return null
        val prompt = buildCorrectionPrompt(text, contextText)
        val out = engine.generate(prompt, maxTokens = text.length + 16)
        return out?.let { acceptCorrection(text, it) }
    }

    /** 旧 libime pinyin round-trip 纠错（已边缘化，默认关闭）。 */
    private fun rescoreWithLibime(ctx: Context, text: String, contextText: String?): String {
        if (text.length > MAX_LEN) return text
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

    /** 构建纠错 prompt（Qwen2.5 ChatML 格式：system 约束 + 前文语境 + 识别文本）。 */
    fun buildCorrectionPrompt(recognition: String, contextText: String?): String = buildString {
        append("<|im_start|>system\n")
        append(SYSTEM_PROMPT)
        append("<|im_end|>\n<|im_start|>user\n")
        if (!contextText.isNullOrBlank()) {
            append("前文：").append(contextText.trim()).append('\n')
        }
        append("识别：").append(recognition)
        append("<|im_end|>\n<|im_start|>assistant\n")
    }

    /**
     * 安全门控：仅当 LLM 输出与输入字符级编辑距离很小（同音纠错是局部换字）才采纳，
     * 防止 LLM 幻觉/改写。返回采纳文本，否则 null（保留原文）。
     */
    fun acceptCorrection(input: String, output: String): String? {
        val o = output.trim()
        if (o.isEmpty()) return null
        val dist = levenshteinDistance(input, o)
        val maxDist = maxOf(2, input.length / 4)
        if (dist > maxDist) return null
        return o
    }

    /** 字符级 Levenshtein 编辑距离。 */
    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            for (j in 0..b.length) prev[j] = curr[j]
        }
        return curr[b.length]
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
