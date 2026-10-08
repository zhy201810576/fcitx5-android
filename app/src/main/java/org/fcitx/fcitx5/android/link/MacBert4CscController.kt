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
import org.fcitx.fcitx5.android.memeboard.MemeBoardPrefs
import java.io.File
import java.io.FileOutputStream

/**
 * 端侧 CSC（MacBERT4CSC）引擎控制器（进程内）。
 *
 * 仿 [AsrEngineController]：INT8 ONNX 模型与 BERT vocab.txt 固定从 csc 插件 APK 的 assets 读取，
 * 因 ONNX Runtime 需要真实文件路径，首次加载时一次性拷贝到 app filesDir
 * （版本标记避免重复拷贝），之后进程级缓存复用。
 *
 * - [prewarm]：输入法启动时后台预加载，消除首次纠错加载延迟；
 * - [ensureLoaded]：纠错时调用，已就绪则返回引擎，否则触发后台加载并返回 null（降级为不纠错）；
 * - [release]：释放模型。
 */
object MacBert4CscController {

    private const val TAG = "MacBert4CscCtrl"

    /** csc 插件包名（模型容器） */
    const val PLUGIN_PACKAGE = "org.fcitx.fcitx5.android.plugin.csc"

    /** assets 内模型与词表相对路径 */
    private const val ASSET_MODEL = "csc/macbert4csc-base-int8.onnx"
    private const val ASSET_VOCAB = "csc/vocab.txt"

    /** 拷贝缓存版本标记：换模型/量化参数时递增，触发重新拷贝 */
    private const val CACHE_VERSION = "1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loading = false

    /** 输入法启动时调用：纠错开关开启时后台预加载模型（失败静默降级；开关关闭时不占用内存）。 */
    fun prewarm(context: Context) {
        if (!MemeBoardPrefs.getCscCorrectEnabled(context)) return
        load(context.applicationContext)
    }

    /** 纠错时调用：已就绪则返回引擎；否则触发后台加载并返回 null（降级为不纠错） */
    fun ensureLoaded(context: Context): MacBert4CscEngine? {
        if (MacBert4CscEngine.ready) return MacBert4CscEngine
        load(context.applicationContext)
        return null
    }

    /** 引擎是否已就绪 */
    fun isReady(): Boolean = MacBert4CscEngine.ready

    /** 释放模型（输入法服务销毁时调用） */
    fun release() {
        MacBert4CscEngine.release()
        loading = false
    }

    private fun load(app: Context) {
        if (loading || MacBert4CscEngine.ready) return
        loading = true
        scope.launch {
            try {
                val modelFile = copyAssetToFilesDir(app, ASSET_MODEL, "macbert4csc-base-int8.onnx")
                val vocabFile = copyAssetToFilesDir(app, ASSET_VOCAB, "vocab.txt")
                if (modelFile != null && vocabFile != null) {
                    val ortLib = app.applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
                    MacBert4CscEngine.load(modelFile.absolutePath, vocabFile.absolutePath, ortLib)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "load failed (csc plugin not installed?)", t)
            } finally {
                loading = false
            }
        }
    }

    /** 把单个文件从 csc 插件 assets 一次性拷贝到 filesDir/csc；失败返回 null。 */
    private fun copyAssetToFilesDir(app: Context, assetPath: String, fileName: String): File? {
        val dir = File(app.filesDir, "csc")
        val dest = File(dir, fileName)
        val marker = File(dir, ".version-$CACHE_VERSION")
        if (dest.exists() && marker.exists()) return dest
        return try {
            dir.mkdirs()
            val assets = app.createPackageContext(PLUGIN_PACKAGE, 0).assets
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            marker.writeText(CACHE_VERSION)
            Log.i(TAG, "asset copied to ${dest.absolutePath}")
            dest
        } catch (t: Throwable) {
            Log.w(TAG, "copy asset failed: $assetPath", t)
            null
        }
    }
}
