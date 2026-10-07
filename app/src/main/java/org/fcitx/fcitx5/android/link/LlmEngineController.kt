/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * 端侧 LLM 引擎控制器（进程内）。
 *
 * 仿 [AsrEngineController]：Qwen2.5-1.5B GGUF 模型固定从 llm 插件 APK 的 assets 读取，
 * 因 llama.cpp 需要真实文件路径，首次加载时把 gguf 一次性拷贝到 app filesDir
 * （版本标记避免重复拷贝），之后进程级缓存复用。
 *
 * - [prewarm]：输入法启动时后台预加载模型，消除首次纠错的加载延迟；
 * - [ensureLoaded]：纠错时调用，已就绪则返回引擎，否则触发后台加载并返回 null（降级为不纠错）；
 * - [release]：释放模型。
 */
object LlmEngineController {

    private const val TAG = "LlmEngineCtrl"

    /** llm 插件包名（模型容器） */
    const val PLUGIN_PACKAGE = "org.fcitx.fcitx5.android.plugin.llm"

    /** assets 内模型相对路径 */
    private const val ASSET_MODEL = "llm/qwen2.5-1.5b-instruct-q4_k_m.gguf"

    /** 拷贝缓存版本标记：换模型/参数时递增，触发重新拷贝 */
    private const val CACHE_VERSION = "1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loading = false

    /** 输入法启动时调用：后台预加载模型（失败静默降级） */
    fun prewarm(context: Context) {
        load(context.applicationContext)
    }

    /** 纠错时调用：已就绪则返回引擎；否则触发后台加载并返回 null（降级为不纠错） */
    fun ensureLoaded(context: Context): LlmEngine? {
        if (LlmEngine.ready) return LlmEngine
        load(context.applicationContext)
        return null
    }

    /** 引擎是否已就绪 */
    fun isReady(): Boolean = LlmEngine.ready

    /** 释放模型（输入法服务销毁时调用） */
    fun release() {
        LlmEngine.release()
        loading = false
    }

    private fun load(app: Context) {
        if (loading || LlmEngine.ready) return
        loading = true
        scope.launch {
            try {
                val modelFile = copyModelToFilesDir(app)
                if (modelFile != null) {
                    LlmEngine.load(modelFile.absolutePath)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "load failed (llm plugin not installed?)", t)
            } finally {
                loading = false
            }
        }
    }

    /** 把 gguf 从 llm 插件 assets 一次性拷贝到 filesDir；失败返回 null。 */
    private fun copyModelToFilesDir(app: Context): File? {
        val dir = File(app.filesDir, "llm")
        val dest = File(dir, "qwen2.5-1.5b-instruct-q4_k_m.gguf")
        val marker = File(dir, ".version-$CACHE_VERSION")
        if (dest.exists() && marker.exists()) return dest
        return try {
            dir.mkdirs()
            val assets = app.createPackageContext(PLUGIN_PACKAGE, 0).assets
            assets.open(ASSET_MODEL).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            marker.writeText(CACHE_VERSION)
            Log.i(TAG, "model copied to ${dest.absolutePath}")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "copy model failed", t)
            null
        }
    }
}
