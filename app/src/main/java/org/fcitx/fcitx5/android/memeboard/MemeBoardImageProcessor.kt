/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 */
package org.fcitx.fcitx5.android.memeboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.memeboard.gif.AnimatedGifEncoder
import pl.droidsonroids.gif.GifDrawable
import timber.log.Timber
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 发送前的图片归一化处理：
 * - 静图：等比缩放到最长边 240px，压缩到 ≤500KB（透明图转 WebP，不透明图转 JPEG）。
 * - 动图：等比缩放到最长边 240px，重编码到 ≤1MB（必要时降帧率/降色）。
 *
 * 处理失败时优雅降级为原图，保证发送流程不中断。
 */
object MemeBoardImageProcessor {

    const val TARGET_SIZE = 240
    const val MAX_STILL_BYTES = 500 * 1024
    const val MAX_GIF_BYTES = 1024 * 1024

    /** GIF 处理的最大帧数，超出时均匀抽帧，避免耗时/内存爆炸。 */
    private const val MAX_GIF_FRAMES = 60

    private data class GifFrame(val bitmap: Bitmap, val delayMs: Int)

    /**
     * 把原始文件处理成统一尺寸/体积的发送文件。
     * @return (处理后的文件, 新的 mimeType)
     */
    suspend fun prepare(file: File, mimeType: String): Pair<File, String> =
        withContext(Dispatchers.IO) {
            try {
                if (mimeType == "image/gif") {
                    prepareGif(file)
                } else {
                    prepareStill(file, mimeType)
                }
            } catch (e: Exception) {
                Timber.w(e, "MemeBoard image prepare failed, fallback to original")
                file to mimeType
            }
        }

    /* ================= 静图 ================= */

    private fun prepareStill(file: File, mimeType: String): Pair<File, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        if (srcW <= 0 || srcH <= 0) {
            return file to mimeType
        }

        // 快速路径：已达标则原样返回，避免无谓重编码
        if (max(srcW, srcH) <= TARGET_SIZE && file.length() <= MAX_STILL_BYTES) {
            return file to mimeType
        }

        // 2 的幂降采样，避免解码超大图时 OOM
        var sampleSize = 1
        while (max(srcW, srcH) / (sampleSize * 2) >= TARGET_SIZE * 2) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val src = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: return file to mimeType

        val scale = min(1f, TARGET_SIZE.toFloat() / max(src.width, src.height))
        val nw = max(1, (src.width * scale).toInt())
        val nh = max(1, (src.height * scale).toInt())
        val scaled = if (nw == src.width && nh == src.height) {
            src
        } else {
            Bitmap.createScaledBitmap(src, nw, nh, true).also { src.recycle() }
        }

        return try {
            if (scaled.hasAlpha()) {
                val out = sendFile(file, "webp")
                compressToSize(scaled, out, Bitmap.CompressFormat.WEBP, MAX_STILL_BYTES)
                out to "image/webp"
            } else {
                val out = sendFile(file, "jpg")
                compressToSize(scaled, out, Bitmap.CompressFormat.JPEG, MAX_STILL_BYTES)
                out to "image/jpeg"
            }
        } finally {
            scaled.recycle()
        }
    }

    /** 质量循环压缩，直到体积达标或到达质量下限。 */
    private fun compressToSize(
        bitmap: Bitmap,
        out: File,
        format: Bitmap.CompressFormat,
        maxBytes: Int,
    ) {
        var quality = 90
        while (quality >= 30) {
            out.outputStream().use { os -> bitmap.compress(format, quality, os) }
            if (out.length() <= maxBytes) return
            quality -= 15
        }
        // 质量已到下限仍超限时，以最低质量兜底写入（240×240 图基本不会走到这里）
        out.outputStream().use { os -> bitmap.compress(format, 30, os) }
    }

    /* ================= 动图 ================= */

    private fun prepareGif(file: File): Pair<File, String> {
        val gif = try {
            GifDrawable(file)
        } catch (e: Exception) {
            Timber.w(e, "MemeBoard: not a real GIF, treat as still")
            return prepareStill(file, "image/gif")
        }

        try {
            val w = gif.intrinsicWidth
            val h = gif.intrinsicHeight
            if (w <= 0 || h <= 0) return file to "image/gif"

            // 快速路径
            if (max(w, h) <= TARGET_SIZE && file.length() <= MAX_GIF_BYTES) {
                return file to "image/gif"
            }

            val scale = min(1f, TARGET_SIZE.toFloat() / max(w, h))
            val nw = max(1, (w * scale).toInt())
            val nh = max(1, (h * scale).toInt())

            val out = sendFile(file, "gif")
            val frames = decodeFrames(gif, nw, nh)

            // 第一轮：全帧 + 标准量化
            encodeGif(frames, nw, nh, out, sample = 10)
            if (out.length() <= MAX_GIF_BYTES) return out to "image/gif"

            // 第二轮：降帧率（每 2 帧取 1）
            val half = frames.filterIndexed { i, _ -> i % 2 == 0 }
            if (half.size >= 2) {
                encodeGif(half, nw, nh, out, sample = 10)
                if (out.length() <= MAX_GIF_BYTES) return out to "image/gif"
            }

            // 第三轮：降帧率 + 降色（增大 sample 减少量化精度）
            if (half.size >= 2) {
                encodeGif(half, nw, nh, out, sample = 30)
            }

            return out to "image/gif"
        } finally {
            gif.recycle()
        }
    }

    private fun decodeFrames(gif: GifDrawable, nw: Int, nh: Int): List<GifFrame> {
        val total = gif.numberOfFrames
        val step = if (total <= MAX_GIF_FRAMES) {
            1
        } else {
            (total + MAX_GIF_FRAMES - 1) / MAX_GIF_FRAMES
        }
        val frames = ArrayList<GifFrame>(min(total, MAX_GIF_FRAMES))
        var i = 0
        while (i < total && frames.size < MAX_GIF_FRAMES) {
            gif.seekToFrame(i)
            val src = gif.currentFrame ?: break
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            val delay = gif.getFrameDuration(i).coerceIn(20, 1000)
            frames.add(GifFrame(scaled, delay))
            i += step
        }
        return frames
    }

    private fun encodeGif(
        frames: List<GifFrame>,
        w: Int,
        h: Int,
        out: File,
        sample: Int,
    ) {
        if (out.exists()) out.delete()
        val enc = AnimatedGifEncoder()
        if (!enc.start(out.absolutePath)) return
        enc.setRepeat(0)
        enc.setSize(w, h)
        enc.setQuality(sample)
        try {
            for (f in frames) {
                enc.setDelay(f.delayMs)
                enc.addFrame(f.bitmap)
            }
        } finally {
            enc.finish()
            frames.forEach { it.bitmap.recycle() }
        }
    }

    /** 生成发送用临时文件路径（与原缓存目录同层，避免污染原图缓存）。 */
    private fun sendFile(original: File, ext: String): File {
        val base = original.nameWithoutExtension
        return File(original.parentFile, "send_${base}.${ext}")
    }
}
