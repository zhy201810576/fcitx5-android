/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 *
 * Android 版 AnimatedGifEncoder：把逐帧 Bitmap 编码为 GIF89a 动图。
 * 算法源自 Kevin Weiner 的公开领域实现，配合本包的 NeuQuant / LZWEncoder 使用。
 */
package org.fcitx.fcitx5.android.memeboard.gif

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

internal class AnimatedGifEncoder {

    private var width = 0
    private var height = 0
    private var repeat = -1
    private var delay = 0

    private var started = false
    private var out: OutputStream? = null
    private var closeStream = false

    private var image: Bitmap? = null
    private var pixels: ByteArray? = null
    private var transparentMask: BooleanArray? = null
    private var indexedPixels: ByteArray? = null
    private var colorDepth = 0
    private var colorTab: ByteArray? = null
    private val usedEntry = BooleanArray(256)

    private var palSize = 7
    private var dispose = -1
    private var firstFrame = true
    private var sizeSet = false
    private var sample = 10

    private var hasTransparent = false
    private var transIndex = 0

    /** 设置帧间延迟（毫秒）。 */
    fun setDelay(ms: Int) {
        delay = (ms / 10.0f).toInt().coerceAtLeast(0)
    }

    /** 设置 disposal code。 */
    fun setDispose(code: Int) {
        if (code >= 0) dispose = code
    }

    /** 设置循环次数：0 = 无限循环。 */
    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    /** 设置量化质量（sample 间隔），越小颜色越准但越慢。 */
    fun setQuality(quality: Int) {
        sample = quality.coerceAtLeast(1)
    }

    /** 设置帧率，等价于 setDelay(1000/fps)。 */
    fun setFrameRate(fps: Float) {
        if (fps > 0f) delay = (100f / fps).toInt().coerceAtLeast(1)
    }

    fun setSize(w: Int, h: Int) {
        if (started && !firstFrame) return
        width = if (w < 1) 320 else w
        height = if (h < 1) 240 else h
        sizeSet = true
    }

    fun start(os: OutputStream): Boolean {
        if (started) return false
        closeStream = false
        out = os
        var ok = true
        try {
            writeString("GIF89a")
        } catch (e: IOException) {
            ok = false
        }
        started = ok
        return started
    }

    fun start(file: String): Boolean {
        var ok = true
        try {
            out = BufferedOutputStream(FileOutputStream(file))
            ok = start(out!!)
            closeStream = true
        } catch (e: IOException) {
            ok = false
        }
        return started
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!started) return false
        var ok = true
        try {
            if (!sizeSet) {
                setSize(im.width, im.height)
            }
            image = im
            getImagePixels()
            analyzePixels()
            if (firstFrame) {
                writeLSD()
                writePalette()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame) {
                writePalette()
            }
            writePixels()
            firstFrame = false
        } catch (e: IOException) {
            ok = false
        }
        return ok
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3b) // GIF trailer
            out?.flush()
            if (closeStream) {
                out?.close()
            }
        } catch (e: IOException) {
            ok = false
        }

        out = null
        image = null
        pixels = null
        transparentMask = null
        indexedPixels = null
        colorTab = null
        closeStream = false
        firstFrame = true
        transIndex = 0
        hasTransparent = false

        return ok
    }

    /* ================= 分析 ================= */

    private fun getImagePixels() {
        val im = image ?: return
        val w = im.width
        val h = im.height
        val argb = IntArray(w * h)
        im.getPixels(argb, 0, w, 0, 0, w, h)
        val bgr = ByteArray(w * h * 3)
        val mask = BooleanArray(w * h)
        var hasT = false
        var k = 0
        var t = 0
        for (p in argb) {
            val a = (p ushr 24) and 0xff
            val transparent = a < 128
            if (transparent) hasT = true
            mask[t++] = transparent
            bgr[k++] = if (transparent) 0 else (p and 0xff).toByte()
            bgr[k++] = if (transparent) 0 else ((p shr 8) and 0xff).toByte()
            bgr[k++] = if (transparent) 0 else ((p shr 16) and 0xff).toByte()
        }
        pixels = bgr
        transparentMask = mask
        hasTransparent = hasT
    }

    private fun analyzePixels() {
        val pix = pixels ?: return
        val len = pix.size
        val nPix = len / 3
        val indexed = ByteArray(nPix)
        val nq = NeuQuant(pix, len, sample)
        colorTab = nq.process()

        val tab = colorTab!!
        // BGR -> RGB（GIF 调色板要求 RGB 顺序）
        var i = 0
        while (i < tab.size) {
            val temp = tab[i]
            tab[i] = tab[i + 2]
            tab[i + 2] = temp
            usedEntry[i / 3] = false
            i += 3
        }

        var k = 0
        for (j in 0 until nPix) {
            val index = nq.map(
                pix[k++].toInt() and 0xff,
                pix[k++].toInt() and 0xff,
                pix[k++].toInt() and 0xff,
            )
            usedEntry[index] = true
            indexed[j] = index.toByte()
        }

        if (hasTransparent) {
            transIndex = 0
            usedEntry[0] = true
            val mask = transparentMask
            if (mask != null) {
                for (j in 0 until nPix) {
                    if (mask[j]) indexed[j] = 0
                }
            }
        }

        indexedPixels = indexed
        pixels = null
        transparentMask = null
        colorDepth = 8
        palSize = 7
    }

    /* ================= 写入 ================= */

    @Throws(IOException::class)
    private fun writeGraphicCtrlExt() {
        val os = out!!
        os.write(0x21) // extension introducer
        os.write(0xf9) // GCE label
        os.write(4) // data block size
        var transp = 0
        var disp = 0
        if (hasTransparent) {
            transp = 1
            disp = 2 // 有透明时默认 restore to background
        }
        if (dispose >= 0) {
            disp = dispose and 7
        }
        disp = disp shl 2

        os.write(
            0 or // 1:3 reserved
                disp or // 4:6 disposal
                0 or // 7 user input = none
                transp, // 8 transparency flag
        )

        writeShort(delay)
        os.write(transIndex)
        os.write(0) // block terminator
    }

    @Throws(IOException::class)
    private fun writeImageDesc() {
        val os = out!!
        os.write(0x2c) // image separator
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        if (firstFrame) {
            os.write(0) // no LCT, use GCT
        } else {
            os.write(
                0x80 or // 1 local color table = yes
                    0 or // 2 interlace = no
                    0 or // 3 sorted = no
                    0 or // 4-5 reserved
                    palSize, // 6-8 color table size
            )
        }
    }

    @Throws(IOException::class)
    private fun writeLSD() {
        val os = out!!
        writeShort(width)
        writeShort(height)
        os.write(
            0x80 or // global color table = yes
                0x70 or // color resolution = 7
                0x00 or // gct sort = no
                palSize,
        )
        os.write(0) // background color index
        os.write(0) // pixel aspect ratio
    }

    @Throws(IOException::class)
    private fun writeNetscapeExt() {
        val os = out!!
        os.write(0x21)
        os.write(0xff)
        os.write(11)
        writeString("NETSCAPE2.0")
        os.write(3)
        os.write(1)
        writeShort(repeat)
        os.write(0)
    }

    @Throws(IOException::class)
    private fun writePalette() {
        val os = out!!
        val tab = colorTab ?: return
        os.write(tab, 0, tab.size)
        val n = 3 * 256 - tab.size
        for (i in 0 until n) {
            os.write(0)
        }
    }

    @Throws(IOException::class)
    private fun writePixels() {
        val os = out!!
        val enc = LZWEncoder(width, height, indexedPixels!!, colorDepth)
        enc.encode(os)
    }

    @Throws(IOException::class)
    private fun writeShort(value: Int) {
        val os = out!!
        os.write(value and 0xff)
        os.write((value shr 8) and 0xff)
    }

    @Throws(IOException::class)
    private fun writeString(s: String) {
        val os = out!!
        for (i in s.indices) {
            os.write(s[i].code)
        }
    }
}
