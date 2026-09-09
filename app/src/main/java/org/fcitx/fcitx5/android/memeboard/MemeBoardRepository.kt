/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.content.Context
import java.io.File

/**
 * MemeBoard 数据访问层：封装 MT Photos 客户端调用、图库/标签/搜索的加载编排，
 * 以及收藏 / 最近使用的本地持久化。UI 层（MemeBoardWindow）只关心状态与展示。
 */
class MemeBoardRepository(private val context: Context) {

    fun hasConfig(): Boolean = MemeBoardPrefs.hasConfig(context)

    fun client(): MtPhotosClient =
        MtPhotosClient(MemeBoardPrefs.getServerUrl(context), MemeBoardPrefs.getApiKey(context))

    /** 获取图片鉴权码（跨实例缓存），供缩略图 URL 使用。 */
    suspend fun getAuthCode(): String = client().getAuthCode()

    fun invalidateAuthCode() = client().invalidateAuthCode()

    /** 标签列表（按 id 降序，排除隐藏）。 */
    suspend fun tagList(): List<MtTag> = client().tagList()

    /** 加载已选图库（未选则全部图库）的文件，按时间线。 */
    suspend fun loadGalleries(): List<MtFile> {
        val galleryIds = MemeBoardPrefs.getGalleryIds(context).toList()
        return client().galleryFiles(galleryIds)
    }

    /** 加载指定标签关联的文件。 */
    suspend fun loadTagFiles(tagId: Long): List<MtFile> {
        val selected = MemeBoardPrefs.getGalleryIds(context)
        // 未选图库时传 "all"（与官方前端一致），否则传下划线分隔的图库 id（前端 join("_")）
        val galleryParam = if (selected.isEmpty()) "all" else selected.joinToString("_")
        return client().tagFiles(tagId, galleryParam)
    }

    /** 搜索：优先 CLIP 以文搜图，不可用或空结果时回退综合搜索。 */
    suspend fun search(query: String): List<MtFile> {
        val galleryIds = MemeBoardPrefs.getGalleryIds(context).toList()
        val tagIds = MemeBoardPrefs.getTagIds(context).toList()
        return client().search(query, galleryIds, tagIds)
    }

    /** 搜索提示（人物/标签建议）。 */
    suspend fun searchTips(key: String): List<MtSearchTip> = client().searchTips(key)

    /** 下载预览图到本地缓存（md5 内容寻址命中复用）。 */
    suspend fun download(file: MtFile): File {
        val dir = File(context.cacheDir, "memeboard")
        return client().downloadPreview(file.id, file.md5, dir, file.fileName)
    }

    /* ================= 收藏 / 最近使用 ================= */

    fun favorites(): List<MtFile> = MemeBoardPrefs.getFavorites(context)

    fun recent(): List<MtFile> = MemeBoardPrefs.getRecent(context)

    fun isFavorite(md5: String): Boolean = MemeBoardPrefs.isFavorite(context, md5)

    fun toggleFavorite(file: MtFile): Boolean = MemeBoardPrefs.toggleFavorite(context, file)

    fun touchRecent(file: MtFile) = MemeBoardPrefs.touchRecent(context, file)
}
