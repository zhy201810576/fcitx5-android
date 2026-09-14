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

/**
 * 语音引擎控制器（进程内）。
 *
 * 取代旧的 BridgePrewarmer（旧实现通过 bindService 拉起独立 asr-bridge 进程）。
 * 现在 SenseVoice 引擎内嵌进输入法进程，模型优先从已下载目录读取（在线更新），
 * 否则回退 asr 插件 APK 的 assets（出厂模型），因此不再有跨进程绑定，
 * 也彻底摆脱 HyperOS 链式启动管控。
 *
 * - [prewarm]：输入法启动时后台预加载模型，消除首次语音的 5 秒延迟；
 * - [ensureLoaded]：语音使用时调用，已就绪则直接返回引擎，否则触发后台加载；
 * - [reload]：下载新模型后清缓存并重新加载；
 * - [release]：释放引擎引用（进程级 recognizer 缓存仍保留，避免重复加载）。
 */
object AsrEngineController {

    private const val TAG = "AsrEngineCtrl"

    /** asr 插件包名（模型容器，随主程序在插件管理中注册） */
    const val PLUGIN_PACKAGE = "org.fcitx.fcitx5.android.plugin.asr"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var engine: SpeechEngine? = null

    @Volatile
    private var loading = false

    /** 输入法启动时调用：后台预加载模型（失败静默降级） */
    fun prewarm(context: Context) {
        load(context.applicationContext)
    }

    /** 语音使用时调用：引擎已就绪则返回；否则触发后台加载并返回 null */
    fun ensureLoaded(context: Context): SpeechEngine? {
        synchronized(lock) { engine?.takeIf { it.ready } }?.let { return it }
        load(context.applicationContext)
        return null
    }

    /** 引擎是否已就绪（供设置页展示） */
    fun isReady(): Boolean = synchronized(lock) { engine?.ready == true }

    /** 下载新模型后重新加载：清引擎引用 + 清 recognizer 缓存 */
    fun reload(context: Context) {
        synchronized(lock) { engine = null }
        SpeechEngine.invalidateCache()
        load(context.applicationContext)
    }

    /** 释放引擎引用（输入法服务销毁时调用；进程级模型缓存保留） */
    fun release() {
        synchronized(lock) { engine = null }
        loading = false
    }

    private fun load(app: Context) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                // VAD 模型固定从插件 assets 读取，故无论 SenseVoice 来源都先取插件 assets
                val assets = app.createPackageContext(PLUGIN_PACKAGE, 0).assets
                val e = if (AsrModelManager.hasDownloadedModel(app)) {
                    SpeechEngine.fromDir(AsrModelManager.modelDir(app).absolutePath, assets)
                } else {
                    SpeechEngine.fromAssets(assets)
                }
                e.init()
                synchronized(lock) { engine = e }
                Log.i(TAG, "engine ready=${e.ready}")
            } catch (t: Throwable) {
                Log.w(TAG, "load failed (asr plugin not installed?)", t)
            } finally {
                loading = false
            }
        }
    }
}
