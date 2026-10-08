/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

/**
 * 中文 BERT（WordPiece）最小分词器，供 MacBERT4CSC 端侧推理使用。
 *
 * 复用 BERT `BasicTokenizer` 的中文语义：CJK 字符 → 单个 token；标点/空白 → 分隔符（不出 token）；
 * 其余字符（拉丁等）→ 依赖 WordPiece。因 [AsrRescore] 已在上游过滤含英文/数字的句子，
 * 本分词器只处理「CJK 字符 + 中文标点」的场景，不实现 WordPiece 子词切分。
 *
 * 关键：返回每个源字符到 token 序列位置的映射 [TokenizeResult.charToTokenPos]，
 * 用于把模型在 token 维度的预测对齐回原始字符（BERT 序列含 [CLS]/[SEP]，且标点会被丢弃，
 * 因此 token 下标 ≠ 字符下标）。
 */
class MacBertTokenizer(
    private val vocab: Map<String, Int>,
    private val idToToken: Array<String>,
) {

    fun tokenize(text: String): TokenizeResult {
        val ids = ArrayList<Int>(text.length + 2)
        val charPos = IntArray(text.length) { -1 }
        ids.add(CLS_ID)
        var next = 1
        for (i in text.indices) {
            val ch = text[i]
            if (isCjkChar(ch)) {
                ids.add(vocab[ch.toString()] ?: UNK_ID)
                charPos[i] = next
                next++
            }
            // 非 CJK 字符按 BERT 语义视为分隔符：不产生 token，原样保留在输出中
        }
        ids.add(SEP_ID)
        return TokenizeResult(ids.toIntArray(), charPos)
    }

    /** 按 token id 反查文本（用于 argmax 解码）；未知 id 返回 null。 */
    fun tokenById(id: Int): String? =
        if (id in idToToken.indices) idToToken[id] else null

    companion object {
        const val PAD_ID = 0
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102

        /** BERT `_is_chinese_char` 的 CJK 码点范围（BMP 部分，忽略极少见的上千字面）。 */
        fun isCjkChar(ch: Char): Boolean {
            val cp = ch.code
            return (cp in 0x4E00..0x9FFF) ||
                (cp in 0x3400..0x4DBF) ||
                (cp in 0xF900..0xFAFF)
        }

        /** 单个 CJK 字符（用于判断 argmax 解码出的 token 是否可作纠错替换）。 */
        fun isSingleCjk(token: String): Boolean = token.length == 1 && isCjkChar(token[0])

        /** 从 assets 的 BERT vocab.txt 加载词表；返回 (token->id, id->token)。 */
        fun loadVocab(lines: Sequence<String>): Pair<Map<String, Int>, Array<String>> {
            val idToToken = lines.toList().toTypedArray()
            val tokenToId = HashMap<String, Int>(idToToken.size * 2)
            idToToken.forEachIndexed { idx, tok -> tokenToId[tok] = idx }
            return tokenToId to idToToken
        }
    }
}

/** 分词结果：[inputIds] 为完整序列（含 [CLS]/[SEP]），[charToTokenPos] 记录每个源字符在序列中的下标，无 token 的字符为 -1。 */
data class TokenizeResult(
    val inputIds: IntArray,
    val charToTokenPos: IntArray,
)
