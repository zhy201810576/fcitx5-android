/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs
import java.io.File

/**
 * 语音纠错评测样本采集器（评估「端侧 LLM 纠错 → 专用 CSC 模型」替换方案的第一步）。
 *
 * 目的：在没有人工标注语料的情况下，先把每次识别的真实输入/输出落盘成 JSONL，
 * 攒出本机真实 ASR 错误分布，后续用它：(1) 量化 CSC 纠错的尝试率/采纳率；
 * (2) 作为 MacBERT4CSC 端侧原型的评测集。
 *
 * - 开关：评测期间临时默认开启（[MemeBoardPrefs.getAsrEvalCollectEnabled]），
 *   **发布前必须改回关闭**，避免给普通用户写文件；
 * - 存储：app 内部 filesDir/asr_eval/samples.jsonl，每行一条 [AsrEvalSample]；
 * - 隐私：样本含用户语音识别文本，只存应用内部目录，不上传；评测完应关闭开关并删除文件。
 */
object AsrEvalCollector {

    private const val TAG = "AsrEvalCollector"

    private val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = true }

    private val lock = Any()

    /** 样本文件路径（内部存储，不对外暴露）。 */
    fun file(ctx: Context): File = File(File(ctx.filesDir, "asr_eval"), "samples.jsonl")

    /** 追加一条样本；开关关闭或写盘失败时静默跳过（不干扰纠错主流程）。 */
    fun record(ctx: Context, sample: AsrEvalSample) {
        if (!MemeBoardPrefs.getAsrEvalCollectEnabled(ctx)) return
        runCatching {
            synchronized(lock) {
                val f = file(ctx)
                f.parentFile?.mkdirs()
                f.appendText(json.encodeToString(sample) + "\n")
            }
        }.onFailure { Log.w(TAG, "record failed", it) }
    }

    /** 读回全部样本（用于本地聚合/评测）；文件不存在或解析失败返回空列表。 */
    fun readAll(ctx: Context): List<AsrEvalSample> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                runCatching { json.decodeFromString<AsrEvalSample>(line) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 纯函数聚合（JVM 可测）：从样本里统计当前纠错链路的行为，无需人工标注即可先看趋势。
     * precision/recall 需人工标注后另算，这里只给「CSC 尝试/采纳、libime 改动」的量。
     */
    fun summarize(samples: List<AsrEvalSample>): AsrEvalSummary {
        var libimeChanged = 0
        var cscAttempted = 0
        var cscAccepted = 0
        var unchanged = 0
        for (s in samples) {
            if (s.output == s.raw) unchanged++
            when {
                s.mode == "libime" && s.output != s.raw -> libimeChanged++
                s.mode == "csc" -> {
                    cscAttempted++
                    if (s.output != s.raw) cscAccepted++
                }
            }
        }
        return AsrEvalSummary(
            total = samples.size,
            libimeChanged = libimeChanged,
            cscAttempted = cscAttempted,
            cscAccepted = cscAccepted,
            unchanged = unchanged,
        )
    }
}

/** 单条识别纠错样本。 */
@Serializable
data class AsrEvalSample(
    /** 采集时间戳（毫秒） */
    val ts: Long,
    /** ASR 原文（纠错输入） */
    val raw: String,
    /** 光标前已有文字（跨句语境，可能为空） */
    val context: String? = null,
    /** 纠错模式："libime" / "csc" / "none" */
    val mode: String,
    /** 最终上屏文本 */
    val output: String,
    /** CSC 逐字替换明细（仅 csc 模式且模型产出过才有），形如 "2:新->心,5:网->晚" */
    val cscEdits: String? = null,
)

/** 样本聚合统计。 */
@Serializable
data class AsrEvalSummary(
    val total: Int = 0,
    /** libime 模式且最终文本被改动的样本数 */
    val libimeChanged: Int = 0,
    /** csc 模式且引擎被调用的样本数（含模型未就绪降级） */
    val cscAttempted: Int = 0,
    /** csc 模式且最终文本被改动（至少一处替换）的样本数 */
    val cscAccepted: Int = 0,
    /** 最终文本 == 原文（未被纠错）的样本数 */
    val unchanged: Int = 0,
)
