/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.FcitxApplication
import timber.log.Timber
import java.io.File

/**
 * 把下载的表情包发布到系统相册（公开可读的 MediaStore），返回任意 App 都能读取的 content:// URI。
 *
 * 背景：FileProvider 的 URI 需要显式授权，剪贴板粘贴 / 系统分享在部分 ROM（如 HyperOS）上
 * 授权可能失效，导致 QQ 等 App 读不到图片。MediaStore 里的图片是公开媒体，无需授权即可读取，
 * 用作剪贴板与分享的来源最稳妥。
 */
object MemeBoardMediaStore {

    /** 临时发布到相册的图片的保留时长，超时后自动删除，避免污染相册。 */
    private const val CLEANUP_DELAY_MS = 60_000L

    fun publish(context: Context, file: File, mimeType: String): Uri? {
        val uri = runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MemeBoard")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                resolver.delete(uri, null, null)
                return@runCatching null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Timber.d("MemeBoardMediaStore published: %s (%s)", uri, mimeType)
            uri
        }.getOrNull()
        if (uri != null) scheduleCleanup(context, uri)
        return uri
    }

    /** 延迟删除临时发布的图片（留出目标 App 读取的时间）。 */
    private fun scheduleCleanup(context: Context, uri: Uri) {
        val appContext = context.applicationContext
        FcitxApplication.getInstance().coroutineScope.launch {
            delay(CLEANUP_DELAY_MS)
            runCatching {
                appContext.contentResolver.delete(uri, null, null)
                Timber.d("MemeBoardMediaStore cleanup: deleted %s", uri)
            }
        }
    }
}
