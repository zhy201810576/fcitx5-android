/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.link

import android.util.Log

/**
 * 端侧 LLM 推理引擎（Qwen2.5-1.5B GGUF，经 llama.cpp 运行）。
 *
 * 模型文件从 llm 插件 assets 拷贝到 filesDir 后，由 [load] 以文件路径加载；
 * 进程级缓存单例，输入法进程存活期间复用，避免重复加载约 1.3GB 的模型。
 *
 * 线程安全：所有入口 [load]/[generate]/[release] 均在 [lock] 上串行——
 * llama.cpp 的 context 不是并发安全的，串行化避免竞争。
 */
object LlmEngine {

    private const val TAG = "LlmEngine"

    @Volatile
    var ready: Boolean = false
        private set

    private val lock = Any()
    private var handle: Long = 0L

    /** 加载模型（耗时，需在后台线程调用）；返回是否就绪。 */
    fun load(modelPath: String): Boolean = synchronized(lock) {
        if (ready) return true
        return try {
            val h = nativeLoad(modelPath)
            if (h != 0L) {
                handle = h
                ready = true
                Log.i(TAG, "engine ready (loaded)")
                true
            } else {
                Log.e(TAG, "nativeLoad returned 0")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "load failed", t)
            false
        }
    }

    /** 贪心生成一段文本；未就绪/失败返回 null。 */
    fun generate(prompt: String, maxTokens: Int): String? = synchronized(lock) {
        if (!ready || handle == 0L) return null
        return try {
            nativeGenerate(handle, prompt, maxTokens)
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t)
            null
        }
    }

    /** 释放模型（输入法服务销毁时调用）。 */
    fun release() = synchronized(lock) {
        if (handle != 0L) {
            try {
                nativeFree(handle)
            } catch (_: Throwable) {
            }
            handle = 0L
        }
        ready = false
    }

    private external fun nativeLoad(modelPath: String): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String?
}
