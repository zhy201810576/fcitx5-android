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
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.ByteArrayOutputStream

/**
 * SenseVoice 离线语音识别引擎（进程内运行），前置 Silero VAD 做「是否有语音」门控。
 *
 * SenseVoice 模型双来源：
 *  - [fromAssets]：从 asr 插件 APK 的 assets 加载（出厂兜底）；
 *  - [fromDir]：从本地目录加载（在线下载的新模型，见 [AsrModelManager]）。
 *
 * VAD（Silero）仅用于门控：判断整段录音里是否存在语音，存在才送 SenseVoice 识别，
 * 从而消除「纯静音/噪声也脑补出文本」的问题；识别时仍用完整原始 PCM（不切段），
 * 避免 VAD 切段把语音开头的几个字截断。VAD 加载失败时降级为无条件整段识别。
 */
class SpeechEngine private constructor(
    private val assets: AssetManager?,
    private val modelDir: String?,
    private val vadAssets: AssetManager?,
) {

    companion object {
        private const val TAG = "AsrEngine"
        private const val ASSET_MODEL_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
        private const val ASSET_VAD_MODEL = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW_SIZE = 512

        // 进程级模型缓存：输入法进程存活期间复用，避免重复加载 230MB 模型
        @Volatile
        private var cachedRecognizer: OfflineRecognizer? = null

        fun fromAssets(assets: AssetManager) = SpeechEngine(assets, null, assets)

        fun fromDir(dir: String, vadAssets: AssetManager) = SpeechEngine(null, dir, vadAssets)

        /** 模型来源切换（如下载新模型）后清空缓存，强制重新加载 */
        fun invalidateCache() {
            cachedRecognizer = null
        }
    }

    private var recognizer: OfflineRecognizer? = cachedRecognizer

    /** VAD 配置（null 表示不可用：无 assets 源），Session 据此创建 VAD 实例 */
    private var vadConfig: VadModelConfig? = null

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
            vadConfig = buildVadConfig()
            ready = true
        } catch (t: Throwable) {
            Log.e(TAG, "engine init failed", t)
            ready = false
        }
    }

    /** 构建 Silero VAD 配置；无 assets 源时返回 null（禁用 VAD） */
    private fun buildVadConfig(): VadModelConfig? {
        if (vadAssets == null) return null
        val silero = SileroVadModelConfig()
        silero.model = ASSET_VAD_MODEL
        // threshold 0.5 对轻声/弱音太严会漏识别；降到 0.3 提升小声识别，仍有 minSpeechDuration 兜底滤噪
        silero.threshold = 0.3f
        silero.minSilenceDuration = 0.5f
        // 0.25s 会丢弃轻声短句；降到 0.15s 让轻声也能通过
        silero.minSpeechDuration = 0.15f
        silero.windowSize = VAD_WINDOW_SIZE
        silero.maxSpeechDuration = 20f

        val config = VadModelConfig()
        config.sileroVadModelConfig = silero
        config.sampleRate = SAMPLE_RATE
        config.numThreads = 1
        config.debug = false
        return config
    }

    fun newSession(): Session = Session()

    /**
     * 一个识别会话：录音期间始终缓冲完整 PCM，同时用 VAD 判断是否存在语音；
     * 结束时若有语音则整段识别（避免切段截断开头），否则返回 null 不上屏。
     */
    inner class Session {
        // 完整原始 PCM（整段识别用）
        private val buffer = ByteArrayOutputStream()
        // VAD 输入对齐缓冲：累积到 512 样本再喂
        private val vadBuffer = ByteArrayOutputStream()
        private var vad: Vad? = null

        // VAD 是否检测到过语音（门控：决定是否整段识别）
        @Volatile
        private var speechDetected = false

        @Volatile
        private var hasData = false

        init {
            val cfg = vadConfig
            if (cfg != null && vadAssets != null) {
                vad = try {
                    Vad(vadAssets, cfg)
                } catch (t: Throwable) {
                    Log.e(TAG, "VAD init failed, fallback to full decode", t)
                    null
                }
            }
        }

        /** 缓冲一段 PCM16；离线模型不产出 partial，返回 null */
        @Synchronized
        fun accept(pcm16: ByteArray): String? {
            hasData = true
            buffer.write(pcm16)
            if (vad != null) {
                vadBuffer.write(pcm16)
                drainVadBlocks()
            }
            return null
        }

        /** 结束会话：有语音则整段识别；全程无语音返回 null（不上屏） */
        @Synchronized
        fun finish(): String? {
            val rec = recognizer ?: return null
            if (!ready || !hasData) return null
            return try {
                val v = vad
                if (v != null) {
                    flushVadRemainder()
                    v.flush()
                    // flush 后仍有已完成的段则视为存在语音
                    while (!v.empty()) {
                        if (v.front().samples.isNotEmpty()) speechDetected = true
                        v.pop()
                    }
                    if (!speechDetected) return null // 全程无语音：不上屏
                }
                recognize(rec, bytesToFloat(buffer.toByteArray()))
            } catch (t: Throwable) {
                Log.e(TAG, "recognize failed", t)
                null
            }
        }

        @Synchronized
        fun release() {
            buffer.reset()
            vadBuffer.reset()
            hasData = false
            speechDetected = false
            try {
                vad?.release()
            } catch (_: Throwable) {
            }
            vad = null
        }

        /** 把 vadBuffer 里完整的 512 样本块取出喂给 VAD，并更新语音检测状态 */
        private fun drainVadBlocks() {
            val v = vad ?: return
            val blockBytes = VAD_WINDOW_SIZE * 2
            val raw = vadBuffer.toByteArray()
            val fullBytes = (raw.size / blockBytes) * blockBytes
            if (fullBytes == 0) return
            vadBuffer.reset()
            vadBuffer.write(raw, fullBytes, raw.size - fullBytes)
            var offset = 0
            while (offset < fullBytes) {
                v.acceptWaveform(bytesToFloat(raw.copyOfRange(offset, offset + blockBytes)))
                offset += blockBytes
            }
            if (v.isSpeechDetected()) speechDetected = true
        }

        /** 松手时处理 vadBuffer 里不足 512 样本的剩余（补零喂入） */
        private fun flushVadRemainder() {
            val v = vad ?: return
            val raw = vadBuffer.toByteArray()
            vadBuffer.reset()
            if (raw.size < 2) return
            val samples = bytesToFloat(raw)
            val padded = FloatArray(VAD_WINDOW_SIZE)
            System.arraycopy(samples, 0, padded, 0, minOf(samples.size, padded.size))
            v.acceptWaveform(padded)
        }
    }

    /** 整段识别：喂入样本、解码、返回文本 */
    private fun recognize(rec: OfflineRecognizer, samples: FloatArray): String {
        val stream = rec.createStream()
        stream.acceptWaveform(samples, SAMPLE_RATE)
        rec.decode(stream)
        val text = rec.getResult(stream).text
        stream.release()
        return text
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
