/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 *
 * GIF LZW 压缩编码器。源自 Kevin Weiner 的公开领域实现，翻译为 Kotlin。
 */
package org.fcitx.fcitx5.android.memeboard.gif

import java.io.IOException
import java.io.OutputStream

internal class LZWEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixAry: ByteArray,
    colorDepth: Int,
) {
    private val initCodeSize = maxOf(2, colorDepth)
    private var remaining = 0
    private var curPixel = 0

    private var nBits = 0
    private var maxcode = 0
    private var freeEnt = 0
    private var clearFlg = false

    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0

    private var curAccum = 0
    private var curBits = 0

    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)

    private var aCount = 0
    private val accum = ByteArray(256)

    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0)
    }

    @Throws(IOException::class)
    private fun compress(initBits: Int, outs: OutputStream) {
        var fcode: Int
        var i: Int
        var c: Int
        var ent: Int
        var disp: Int
        val hsizeReg = HSIZE
        var hshift: Int

        gInitBits = initBits
        clearFlg = false
        nBits = gInitBits
        maxcode = MAXCODE(nBits)

        clearCode = 1 shl (initBits - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2

        charInit()

        ent = nextPixel()

        hshift = 0
        fcode = hsizeReg
        while (fcode < 65536) {
            hshift++
            fcode *= 2
        }
        hshift = 8 - hshift

        clHash(hsizeReg)

        output(clearCode, outs)

        outer@ while (true) {
            c = nextPixel()
            if (c == EOF) break
            fcode = (c shl MAXBITS) + ent
            i = (c shl hshift) xor ent

            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                disp = hsizeReg - i
                if (i == 0) disp = 1
                while (true) {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        continue@outer
                    }
                    if (htab[i] < 0) break
                }
            }
            output(ent, outs)
            ent = c
            if (freeEnt < MAXMAXCODE) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                clBlock(outs)
            }
        }
        output(ent, outs)
        output(eofCode, outs)
    }

    @Throws(IOException::class)
    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and MASKS[curBits]

        if (curBits > 0) {
            curAccum = curAccum or (code shl curBits)
        } else {
            curAccum = code
        }

        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum shr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = gInitBits
                maxcode = MAXCODE(nBits)
                clearFlg = false
            } else {
                nBits++
                maxcode = if (nBits == MAXBITS) MAXMAXCODE else MAXCODE(nBits)
            }
        }

        if (code == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }

    @Throws(IOException::class)
    private fun clBlock(outs: OutputStream) {
        clHash(HSIZE)
        freeEnt = clearCode + 2
        clearFlg = true
        output(clearCode, outs)
    }

    private fun clHash(size: Int) {
        for (i in 0 until size) {
            htab[i] = -1
        }
    }

    private fun charInit() {
        aCount = 0
    }

    @Throws(IOException::class)
    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) {
            flushChar(outs)
        }
    }

    @Throws(IOException::class)
    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }

    private fun nextPixel(): Int {
        if (remaining == 0) return EOF
        remaining--
        val pix = pixAry[curPixel++]
        return pix.toInt() and 0xff
    }

    companion object {
        private const val EOF = -1
        private const val BITS = 12
        private const val HSIZE = 5003
        private const val MAXBITS = BITS
        private const val MAXMAXCODE = 1 shl BITS

        private val MASKS = intArrayOf(
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
            0x001F, 0x003F, 0x007F, 0x00FF,
            0x01FF, 0x03FF, 0x07FF, 0x0FFF,
            0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF,
        )

        private fun MAXCODE(nBits: Int): Int = (1 shl nBits) - 1
    }
}
