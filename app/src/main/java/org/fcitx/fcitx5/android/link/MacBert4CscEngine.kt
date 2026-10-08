/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.util.Log
import java.io.File
import kotlin.math.exp

/**
 * 端侧中文拼写纠错（CSC）推理引擎：MacBERT4CSC（INT8 动态量化 ONNX）。
 *
 * 模型即标准 `BertForMaskedLM` 的导出，输入 [input_ids]/[attention_mask]/[token_type_ids]，
 * 输出 logits [1, seq, vocab]。逐字取 argmax 的 softmax 置信度，仅当置信度 ≥ 阈值且预测为
 * 单个 CJK 字符时替换——天然「等长替换、低置信不改」，避免自回归 LLM 的增删字与过度纠正问题。
 *
 * 推理经 native-lib 的 ORT C API（`nativeCscLoad/nativeCscRun/nativeCscFree`）完成：
 * 复用 sherpa-onnx 自带、已打入 app 的 `libonnxruntime.so`（1.27.1），用 dlopen+dlsym 取符号，
 * 从而绕开 onnxruntime-android Java API 与 sherpa 的 ORT 版本号（versioned 符号）冲突。
 *
 * 线程安全：入口在 [lock] 上串行。
 */
object MacBert4CscEngine {

    private const val TAG = "MacBert4Csc"

    @Volatile
    var ready: Boolean = false
        private set

    private val lock = Any()
    private var session: Long = 0L
    private var tokenizer: MacBertTokenizer? = null

    /** 加载模型与词表（耗时，需在后台线程调用）；返回是否就绪。 */
    fun load(modelPath: String, vocabPath: String, ortLibPath: String): Boolean = synchronized(lock) {
        if (ready) return true
        return try {
            val (tokToId, idToTok) = File(vocabPath).useLines { MacBertTokenizer.loadVocab(it) }
            val h = nativeCscLoad(modelPath, ortLibPath)
            if (h != 0L) {
                session = h
                tokenizer = MacBertTokenizer(tokToId, idToTok)
                ready = true
                Log.i(TAG, "engine ready (loaded)")
                true
            } else {
                Log.e(TAG, "nativeCscLoad returned 0")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            false
        }
    }

    /**
     * 纠错一段文本；未就绪/失败返回 null。
     * [threshold] 为替换置信度阈值（pycorrector 默认 0.7；IME 场景偏好 precision，可调高）。
     */
    fun correct(text: String, threshold: Float = 0.7f): CscCorrection? = synchronized(lock) {
        if (!ready) return null
        val tok = tokenizer ?: return null
        val h = session
        if (h == 0L) return null
        return try {
            infer(h, tok, text, threshold)
        } catch (t: Throwable) {
            Log.e(TAG, "correct failed", t)
            null
        }
    }

    /** 释放模型（输入法服务销毁时调用）。 */
    fun release() = synchronized(lock) {
        if (session != 0L) {
            try {
                nativeCscFree(session)
            } catch (_: Throwable) {
            }
            session = 0L
        }
        tokenizer = null
        ready = false
    }

    /** 单次前向（JNI）+ 逐字解码。 */
    private fun infer(handle: Long, tok: MacBertTokenizer, text: String, threshold: Float): CscCorrection {
        val r = tok.tokenize(text)
        val n = r.inputIds.size
        val longIds = LongArray(n) { r.inputIds[it].toLong() }
        val attMask = LongArray(n) { 1L }
        val typeIds = LongArray(n) { 0L }

        val logits = nativeCscRun(handle, longIds, attMask, typeIds)
        if (logits.isEmpty()) return CscCorrection(corrected = text, edits = emptyList())
        val vocab = logits.size / n

        val sb = StringBuilder(text)
        val edits = ArrayList<CscEdit>()
        for (i in text.indices) {
            val p = r.charToTokenPos[i]
            if (p <= 0 || p >= n - 1) continue // 跳过 [CLS]/[SEP] 与无 token 的字符
            val (argId, prob) = argmaxSoftmax(logits, p * vocab, vocab)
            val predTok = tok.tokenById(argId) ?: continue
            if (MacBertTokenizer.isSingleCjk(predTok) && prob >= threshold && predTok[0] != text[i]) {
                sb.setCharAt(i, predTok[0])
                edits.add(CscEdit(position = i, original = text[i], corrected = predTok[0], prob = prob))
            }
        }
        return CscCorrection(corrected = sb.toString(), edits = edits)
    }

    /** 对 logits 第 [rowStart] 行做 softmax，返回 (argmax id, 对应 softmax 概率)。 */
    private fun argmaxSoftmax(logits: FloatArray, rowStart: Int, vocab: Int): Pair<Int, Float> {
        var max = Float.NEGATIVE_INFINITY
        for (j in 0 until vocab) {
            val v = logits[rowStart + j]
            if (v > max) max = v
        }
        var sum = 0.0
        var arg = -1
        var argVal = Float.NEGATIVE_INFINITY
        for (j in 0 until vocab) {
            val v = logits[rowStart + j]
            sum += exp((v - max).toDouble())
            if (v > argVal) {
                argVal = v
                arg = j
            }
        }
        val prob = if (sum > 0) exp((argVal - max).toDouble()) / sum else 0.0
        return arg to prob.toFloat()
    }

    private external fun nativeCscLoad(modelPath: String, ortLibPath: String): Long
    private external fun nativeCscFree(handle: Long)
    private external fun nativeCscRun(handle: Long, inputIds: LongArray, attMask: LongArray, typeIds: LongArray): FloatArray
}

/** 一次纠错的结果：[corrected] 为纠错后文本（未纠错处原样），[edits] 为逐字替换明细（供候选式 UX / 日志）。 */
data class CscCorrection(
    val corrected: String,
    val edits: List<CscEdit> = emptyList(),
)

/** 单字替换：[position] 为源字符串下标（0-based），[prob] 为该替换的 softmax 置信度。 */
data class CscEdit(
    val position: Int,
    val original: Char,
    val corrected: Char,
    val prob: Float,
)
