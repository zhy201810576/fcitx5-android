/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.ByteArrayOutputStream

/**
 * SenseVoice 离线语音识别引擎（进程内运行）。
 *
 * 双来源：
 *  - [fromAssets]：从 asr 插件 APK 的 assets 加载（出厂兜底）；
 *  - [fromDir]：从本地目录加载（在线下载的新模型，见 [AsrModelManager]）。
 *
 * SenseVoice 为离线模型：缓冲 PCM，结束会话时整段识别。
 */
class SpeechEngine private constructor(
    private val assets: AssetManager?,
    private val modelDir: String?,
) {

    companion object {
        private const val TAG = "AsrEngine"
        private const val ASSET_MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        private const val SAMPLE_RATE = 16000

        // 进程级模型缓存：输入法进程存活期间复用，避免重复加载 230MB 模型
        @Volatile
        private var cachedRecognizer: OfflineRecognizer? = null

        fun fromAssets(assets: AssetManager) = SpeechEngine(assets, null)

        fun fromDir(dir: String) = SpeechEngine(null, dir)

        /** 模型来源切换（如下载新模型）后清空缓存，强制重新加载 */
        fun invalidateCache() {
            cachedRecognizer = null
        }
    }

    private var recognizer: OfflineRecognizer? = cachedRecognizer

    @Volatile
    var ready: Boolean = false
        private set

    /** 加载模型（耗时，需在后台线程调用）；命中缓存则瞬时就绪 */
    @Synchronized
    fun init() {
        if (ready) return
        try {
            recognizer = cachedRecognizer
            if (recognizer == null) {
                Log.i(TAG, "loading SenseVoice model (${if (modelDir != null) "file" else "assets"}) ...")
                val sv = OfflineSenseVoiceModelConfig()
                sv.model = modelPath("model.int8.onnx")
                sv.language = "" // 自动检测语言
                sv.useInverseTextNormalization = true // 输出标点

                val modelConfig = OfflineModelConfig()
                modelConfig.senseVoice = sv
                modelConfig.tokens = modelPath("tokens.txt")
                modelConfig.numThreads = 4
                modelConfig.debug = false

                val config = OfflineRecognizerConfig()
                config.modelConfig = modelConfig

                // assetManager 传 null 时，sherpa-onnx 走 newFromFile（文件路径加载）
                recognizer = OfflineRecognizer(assets, config)
                cachedRecognizer = recognizer
                Log.i(TAG, "engine ready (loaded)")
            } else {
                Log.i(TAG, "engine ready (cached)")
            }
            ready = true
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
            ready = false
        }
    }

    fun newSession(): Session = Session()

    /** 一个识别会话：缓冲 PCM，结束时整段识别 */
    inner class Session {
        private val buffer = ByteArrayOutputStream()
        @Volatile
        private var hasData = false

        /** 缓冲一段 PCM16；离线模型不产出 partial，返回 null */
        @Synchronized
        fun accept(pcm16: ByteArray): String? {
            buffer.write(pcm16)
            hasData = true
            return null
        }

        /** 结束会话：整段识别，返回最终文本 */
        @Synchronized
        fun finish(): String? {
            val rec = recognizer ?: return null
            if (!ready || !hasData) return null
            return try {
                val samples = bytesToFloat(buffer.toByteArray())
                val stream = rec.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val text = rec.getResult(stream).text
                stream.release()
                text
            } catch (t: Throwable) {
                Log.e(TAG, "recognize failed", t)
                null
            }
        }

        @Synchronized
        fun release() {
            buffer.reset()
            hasData = false
        }
    }

    /** 模型/tokens 路径：文件模式用绝对目录，asset 模式用 assets 相对目录 */
    private fun modelPath(name: String): String =
        if (modelDir != null) "$modelDir/$name" else "$ASSET_MODEL_DIR/$name"

    private fun bytesToFloat(pcm16: ByteArray): FloatArray {
        val n = pcm16.size / 2
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val lo = pcm16[2 * i].toInt() and 0xFF
            val hi = pcm16[2 * i + 1].toInt() and 0xFF
            val v = (hi shl 8) or lo
            out[i] = if (v >= 32768) (v - 65536) / 32768.0f else v / 32768.0f
            i++
        }
        return out
    }
}
