/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 MemeBoard Contributors
 *
 * NeuQuant 神经网络颜色量化，用于把 RGB 图像降到 GIF 的 256 色调色板。
 * 算法源自 Anthony Dekker 的 NeuQuant，Kevin Weiner 移植到 Java（公开领域实现），
 * 这里翻译为 Kotlin，仅在 MemeBoard 内部用于动图重编码。
 */
package org.fcitx.fcitx5.android.memeboard.gif

internal class NeuQuant(pixels: ByteArray, len: Int, sample: Int) {

    companion object {
        /** 调色板颜色数 */
        private const val NETSIZE = 256

        /** 四个接近 500 的质数，用于打散采样步长 */
        private const val PRIME1 = 499
        private const val PRIME2 = 491
        private const val PRIME3 = 487
        private const val PRIME4 = 503

        private const val MINPICTUREBYTES = 3 * PRIME4

        private const val MAXNETPOS = NETSIZE - 1
        private const val NETBIASSHIFT = 4
        private const val NCYCLES = 100
        private const val INTBIASSHIFT = 16
        private const val INTBIAS = 1 shl INTBIASSHIFT
        private const val GAMMASHIFT = 10
        private const val BETASHIFT = 10
        private const val BETA = INTBIAS shr BETASHIFT
        private const val BETAGAMMA = INTBIAS shl (GAMMASHIFT - BETASHIFT)
        private const val INITRAD = NETSIZE shr 3
        private const val RADIUSBIASSHIFT = 6
        private const val RADIUSBIAS = 1 shl RADIUSBIASSHIFT
        private const val INITRADIUS = INITRAD * RADIUSBIAS
        private const val RADIUSDEC = 30
        private const val ALPHABIASSHIFT = 10
        private const val INITALPHA = 1 shl ALPHABIASSHIFT
        private const val RADBIASSHIFT = 8
        private const val RADBIAS = 1 shl RADBIASSHIFT
        private const val ALPHARADBSHIFT = ALPHABIASSHIFT + RADBIASSHIFT
        private const val ALPHARADBIAS = 1 shl ALPHARADBSHIFT
    }

    private val thepicture: ByteArray = pixels
    private val lengthcount: Int = len
    private var samplefac: Int = sample

    /** 神经网络：每个神经元 [B, G, R, 编号] */
    private val network: Array<IntArray> = Array(NETSIZE) {
        val v = (it shl (NETBIASSHIFT + 8)) / NETSIZE
        intArrayOf(v, v, v, 0)
    }

    private val netindex = IntArray(256)
    private val bias = IntArray(NETSIZE)
    private val freq = IntArray(NETSIZE) { INTBIAS / NETSIZE }
    private val radpower = IntArray(INITRAD)

    private var alphadec = 0

    /** 输出 256×3 的 BGR 调色板。 */
    fun colorMap(): ByteArray {
        val map = ByteArray(3 * NETSIZE)
        val index = IntArray(NETSIZE)
        for (i in 0 until NETSIZE) {
            index[network[i][3]] = i
        }
        var k = 0
        for (i in 0 until NETSIZE) {
            val j = index[i]
            map[k++] = network[j][0].toByte()
            map[k++] = network[j][1].toByte()
            map[k++] = network[j][2].toByte()
        }
        return map
    }

    /** 插入索引树，按 network[i][0]（blue）排序并构建 netindex 查找表。 */
    fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until NETSIZE) {
            var smallpos = i
            var smallval = network[i][0]
            for (j in i + 1 until NETSIZE) {
                if (network[j][0] < smallval) {
                    smallpos = j
                    smallval = network[j][0]
                }
            }
            val q = network[smallpos][0]
            if (i != smallpos) {
                swapRows(i, smallpos)
            }
            if (q != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in previouscol + 1 until q) {
                    netindex[j] = i
                }
                previouscol = q
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + MAXNETPOS) shr 1
        for (j in previouscol + 1 until NETSIZE) {
            netindex[j] = MAXNETPOS
        }
    }

    private fun swapRows(a: Int, b: Int) {
        for (c in 0 until 4) {
            val t = network[a][c]
            network[a][c] = network[b][c]
            network[b][c] = t
        }
    }

    /** 主学习循环。 */
    fun learn() {
        var b: Int
        var g: Int
        var r: Int
        var radius: Int
        var rad: Int
        var alpha: Int
        var step: Int
        var delta: Int
        var samplepixels: Int
        var pix: Int
        var lim: Int

        if (lengthcount < MINPICTUREBYTES) {
            samplefac = 1
        }
        alphadec = 30 + ((samplefac - 1) / 3)
        pix = 0
        lim = lengthcount
        samplepixels = lengthcount / (3 * samplefac)
        delta = samplepixels / NCYCLES
        alpha = INITALPHA
        radius = INITRADIUS

        rad = radius shr RADIUSBIASSHIFT
        if (rad <= 1) {
            rad = 0
        }
        for (i in 0 until rad) {
            radpower[i] = alpha * (((rad * rad - i * i) * RADBIAS) / (rad * rad))
        }

        step = when {
            lengthcount < MINPICTUREBYTES -> 3
            lengthcount % PRIME1 != 0 -> 3 * PRIME1
            lengthcount % PRIME2 != 0 -> 3 * PRIME2
            lengthcount % PRIME3 != 0 -> 3 * PRIME3
            else -> 3 * PRIME4
        }

        var i = 0
        while (i < samplepixels) {
            b = (thepicture[pix].toInt() and 0xff) shl NETBIASSHIFT
            g = (thepicture[pix + 1].toInt() and 0xff) shl NETBIASSHIFT
            r = (thepicture[pix + 2].toInt() and 0xff) shl NETBIASSHIFT
            val j = contest(b, g, r)

            altersingle(alpha, j, b, g, r)
            if (rad != 0) {
                alterneigh(rad, j, b, g, r)
            }

            pix += step
            if (pix >= lim) {
                pix -= lengthcount
            }

            i++
            if (delta == 0) {
                delta = 1
            }
            if (i % delta == 0) {
                alpha -= alpha / alphadec
                radius -= radius / RADIUSDEC
                rad = radius shr RADIUSBIASSHIFT
                if (rad <= 1) {
                    rad = 0
                }
                for (k in 0 until rad) {
                    radpower[k] =
                        alpha * (((rad * rad - k * k) * RADBIAS) / (rad * rad))
                }
            }
        }
    }

    /** 找出与 (b,g,r) 最近的神经元位置（非颜色编号）。 */
    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = Int.MAX_VALUE
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos

        for (i in 0 until NETSIZE) {
            val n = network[i]
            val dist = kotlin.math.abs(n[0] - b) +
                kotlin.math.abs(n[1] - g) +
                kotlin.math.abs(n[2] - r)
            if (dist < bestd) {
                bestd = dist
                bestpos = i
            }
            val biasdist = dist - ((bias[i]) shr (INTBIASSHIFT - NETBIASSHIFT))
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist
                bestbiaspos = i
            }
            val betafreq = (freq[i]) shr BETASHIFT
            freq[i] -= betafreq
            bias[i] += betafreq shl GAMMASHIFT
        }
        freq[bestpos] += BETA
        bias[bestpos] -= BETAGAMMA
        return bestbiaspos
    }

    /** 移动单个神经元向 (b,g,r) 靠近。 */
    private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        val n = network[i]
        n[0] -= (alpha * (n[0] - b)) / INITALPHA
        n[1] -= (alpha * (n[1] - g)) / INITALPHA
        n[2] -= (alpha * (n[2] - r)) / INITALPHA
    }

    /** 移动邻居神经元向 (b,g,r) 靠近，影响随距离衰减。 */
    private fun alterneigh(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        val lo = if (i - rad < -1) -1 else i - rad
        val hi = if (i + rad > NETSIZE) NETSIZE else i + rad
        var j = i + 1
        var k = i - 1
        var q = 0
        while (j < hi || k > lo) {
            val a = radpower[q++]
            if (j < hi) {
                val p = network[j]
                p[0] -= (a * (p[0] - b)) / ALPHARADBIAS
                p[1] -= (a * (p[1] - g)) / ALPHARADBIAS
                p[2] -= (a * (p[2] - r)) / ALPHARADBIAS
                j++
            }
            if (k > lo) {
                val p = network[k]
                p[0] -= (a * (p[0] - b)) / ALPHARADBIAS
                p[1] -= (a * (p[1] - g)) / ALPHARADBIAS
                p[2] -= (a * (p[2] - r)) / ALPHARADBIAS
                k--
            }
        }
    }

    /** 去掉网络偏置。 */
    fun unbiasnet() {
        for (i in 0 until NETSIZE) {
            network[i][0] = network[i][0] shr NETBIASSHIFT
            network[i][1] = network[i][1] shr NETBIASSHIFT
            network[i][2] = network[i][2] shr NETBIASSHIFT
            network[i][3] = i
            bias[i] = 0
        }
    }

    /** 把 (b,g,r) 映射到最近的颜色编号（0..255）。 */
    fun map(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netindex[g]
        var j = i - 1

        while (i < NETSIZE || j >= 0) {
            if (i < NETSIZE) {
                val p = network[i]
                var dist = p[1] - g
                if (dist >= bestd) {
                    i = NETSIZE
                } else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = g - p[1]
                if (dist >= bestd) {
                    j = -1
                } else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    /** 执行完整量化流程，返回 BGR 调色板。 */
    fun process(): ByteArray {
        learn()
        unbiasnet()
        inxbuild()
        return colorMap()
    }
}
