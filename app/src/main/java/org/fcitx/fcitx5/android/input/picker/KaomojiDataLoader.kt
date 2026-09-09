/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.input.picker

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.IOException

/**
 * 颜文字数据加载器
 * 从 assets/kaomoji_data.json 加载颜文字数据，支持搜索。
 */
object KaomojiDataLoader {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class KaomojiCategory(
        val name: String,
        val preview: String = "",
        val items: List<String> = emptyList()
    )

    @Serializable
    data class KaomojiData(
        val version: Int = 0,
        val total_count: Int = 0,
        val category_count: Int = 0,
        val categories: List<KaomojiCategory> = emptyList(),
        val search_index: Map<String, List<String>> = emptyMap()
    )

    @Volatile
    private var cached: KaomojiData? = null

    /**
     * 获取颜文字数据（懒加载，线程安全）
     */
    fun getData(): KaomojiData? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            cached = loadDataFromAssets()
            return cached
        }
    }

    /**
     * 从 assets 加载 JSON 数据
     */
    private fun loadDataFromAssets(): KaomojiData? {
        return try {
            val jsonStr = appContext.assets.open("kaomoji_data.json").bufferedReader().use { it.readText() }
            json.decodeFromString<KaomojiData>(jsonStr)
        } catch (e: IOException) {
            Timber.e(e, "无法加载颜文字数据文件")
            null
        } catch (e: Exception) {
            Timber.e(e, "解析颜文字数据失败")
            null
        }
    }

    // 展示分类：🔍 搜索入口 + 12 个核心情绪（标签栏可滑动）
    private val EMOTION_CATEGORIES = setOf(
        "开心", "爱心", "害羞", "悲伤", "哭泣",
        "生气", "亲亲", "激动", "惊讶", "烦恼",
        "坏笑", "其他"
    )

    fun getEmoticonData(): List<Pair<PickerData.Category, Array<String>>>? {
        val data = getData() ?: return null
        val result = mutableListOf<Pair<PickerData.Category, Array<String>>>()
        // 第一个标签固定为搜索入口
        result.add(PickerData.Category(SEARCH_TAB_LABEL) to emptyArray())
        for (catName in EMOTION_CATEGORIES) {
            val cat = data.categories.find { it.name == catName }
            if (cat != null && cat.items.isNotEmpty()) {
                result.add(PickerData.Category(catName) to cat.items.toTypedArray())
            }
        }
        return result
    }

    const val SEARCH_TAB_LABEL = "🔍"

    /**
     * 搜索颜文字
     * @param query 搜索关键词（中文分类名或英文标签）
     * @return 匹配的颜文字列表
     */
    fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val data = getData() ?: return emptyList()
        val queryLower = query.lowercase().trim()

        val results = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // 1. 按分类名匹配
        for (cat in data.categories) {
            if (cat.name.contains(query) || cat.name.equals(query, ignoreCase = true)) {
                for (item in cat.items) {
                    if (item !in seen) {
                        seen.add(item)
                        results.add(item)
                    }
                }
            }
        }

        // 2. 按搜索索引中的标签匹配
        for ((kaomoji, tags) in data.search_index) {
            for (tag in tags) {
                if (tag.contains(queryLower) || tag.equals(queryLower, ignoreCase = true)) {
                    if (kaomoji !in seen) {
                        seen.add(kaomoji)
                        results.add(kaomoji)
                    }
                    break
                }
            }
        }

        // 3. 直接颜文字包含搜索词
        for (cat in data.categories) {
            for (item in cat.items) {
                if (item.contains(query) && item !in seen) {
                    seen.add(item)
                    results.add(item)
                }
            }
        }

        return results
    }

    /**
     * 清除缓存（用于重新加载数据）
     */
    fun clearCache() {
        synchronized(this) {
            cached = null
        }
    }
}
