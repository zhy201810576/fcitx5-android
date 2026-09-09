/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ML Kit 数字墨水手写识别客户端（中文 / 日语）。
 *
 * 使用 Google ML Kit Digital Ink Recognition，一个引擎同时覆盖中文与日语手写，
 * 完全设备端识别、笔迹不出设备；模型由 [RemoteModelManager] 运行时按需下载
 * （每种语言约 20MB，走 Google Play Services），首次使用需联网 + 设备具备 GMS。
 *
 * 已取代原 gpen 搜狗手写桥（闭源 SDK + 包名绑定 + 服务器计数授权 + arm64-only）。
 */
object MlKitHandwritingClient {

    private const val TAG = "MlKitInk"

    /** 支持的手写语言。tag 为 ML Kit 的 BCP-47 语言码（见 base-models 页面）。 */
    enum class Language(val tag: String) {
        /** 中文（汉字，中国简体）。 */
        CHINESE("zh-Hani-CN"),

        /** 日语。 */
        JAPANESE("ja")
    }

    private val recognizers = ConcurrentHashMap<String, DigitalInkRecognizer>()
    private val models = ConcurrentHashMap<String, DigitalInkRecognitionModel>()
    private val readyFlags = ConcurrentHashMap<String, Boolean>()

    /** 指定语言的识别器与模型是否已就绪（可安全调用 [recognize]）。 */
    fun isReady(language: Language): Boolean =
        recognizers[language.tag] != null && readyFlags[language.tag] == true

    /**
     * 初始化指定语言的识别器，并检查/下载对应模型。
     * 幂等：已就绪时直接回调 "ready"。回调在 GMS Task 线程（默认主线程）。
     */
    fun prepare(language: Language, onStatus: (String) -> Unit) {
        val tag = language.tag
        if (isReady(language)) {
            onStatus("ready")
            return
        }
        val identifier = try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
        } catch (e: MlKitException) {
            onStatus("invalid_lang_tag: ${e.message}")
            return
        }
        if (identifier == null) {
            onStatus("no_model_for_lang_tag: $tag")
            return
        }
        val model = DigitalInkRecognitionModel.builder(identifier).build()
        models[tag] = model
        recognizers[tag] = DigitalInkRecognition.getClient(
            DigitalInkRecognizerOptions.builder(model).build()
        )
        val manager = RemoteModelManager.getInstance()
        manager.isModelDownloaded(model)
            .addOnSuccessListener { downloaded ->
                if (downloaded) {
                    readyFlags[tag] = true
                    onStatus("ready")
                } else {
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            readyFlags[tag] = true
                            onStatus("downloaded")
                        }
                        .addOnFailureListener {
                            readyFlags[tag] = false
                            onStatus("download_failed: ${it.message}")
                        }
                }
            }
            .addOnFailureListener {
                readyFlags[tag] = false
                onStatus("check_failed: ${it.message}")
            }
    }

    /**
     * 识别笔画点，返回候选文本列表（按置信度降序，可能为空）。
     *
     * [points] 为手写板采集的编码：x,y 成对，每笔以 `-1,0` 分隔；此处按分隔符
     * 拆成 stroke，重建为 ML Kit 的 [Ink]。原始数据无时间戳，故用递增时间戳
     * 表达笔画顺序（对单字识别影响很小）。
     */
    suspend fun recognize(language: Language, points: IntArray): List<String> =
        withContext(Dispatchers.IO) {
            val rec = recognizers[language.tag] ?: return@withContext emptyList()
            val ink = buildInk(points) ?: return@withContext emptyList()
            try {
                val result = Tasks.await(rec.recognize(ink))
                result.candidates.map { it.text }
            } catch (t: Throwable) {
                Log.w(TAG, "recognize failed", t)
                emptyList()
            }
        }

    private fun buildInk(points: IntArray): Ink? {
        val inkBuilder = Ink.builder()
        var stroke: Ink.Stroke.Builder? = null
        var hasPoint = false
        var t = 0L
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x == -1 && y == 0) {
                stroke?.let { inkBuilder.addStroke(it.build()) }
                stroke = null
            } else {
                if (stroke == null) stroke = Ink.Stroke.builder()
                stroke.addPoint(Ink.Point.create(x.toFloat(), y.toFloat(), t))
                t += 10
                hasPoint = true
            }
            i += 2
        }
        stroke?.let { inkBuilder.addStroke(it.build()) }
        return if (hasPoint) inkBuilder.build() else null
    }
}
