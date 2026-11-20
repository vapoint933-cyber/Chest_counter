package com.chestcounter.app

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Шаблон: бинарная маска белых пикселей надписи "+600 ОП / СУНДУК". */
class Template(val w: Int, val h: Int, val data: ByteArray) {
    val onCount: Int
    val px: IntArray
    val py: IntArray

    init {
        var c = 0
        for (b in data) if (b.toInt() != 0) c++
        onCount = c
        val step = if (c > 500) (c + 499) / 500 else 1
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var k = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (data[y * w + x].toInt() != 0) {
                    if (k % step == 0) { xs.add(x); ys.add(y) }
                    k++
                }
            }
        }
        px = xs.toIntArray()
        py = ys.toIntArray()
    }

    fun save(f: File) {
        DataOutputStream(f.outputStream().buffered()).use {
            it.writeInt(w); it.writeInt(h); it.write(data)
        }
    }

    companion object {
        fun load(f: File): Template? {
            return try {
                DataInputStream(f.inputStream().buffered()).use {
                    val w = it.readInt()
                    val h = it.readInt()
                    val d = ByteArray(w * h)
                    it.readFully(d)
                    Template(w, h, d)
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Вырезает область маски и обрезает по границам белых пикселей. */
        fun fromMask(mask: ByteArray, W: Int, x0: Int, y0: Int, x1: Int, y1: Int): Template? {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
            for (y in y0 until y1) for (x in x0 until x1) {
                if (mask[y * W + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) return null
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val d = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) d[y * w + x] = mask[(minY + y) * W + minX + x]
            val t = Template(w, h, d)
            return if (t.onCount < 30) null else t
        }
    }
}

object Matcher {
    const val WHITE_MIN = 200

    fun maskFromBitmap(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val m = ByteArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            if (((p shr 16) and 255) >= WHITE_MIN && ((p shr 8) and 255) >= WHITE_MIN && (p and 255) >= WHITE_MIN) m[i] = 1
        }
        return m
    }

    /** Возвращает лучшую долю совпавших точек шаблона (0..1). */
    fun find(mask: ByteArray, W: Int, H: Int, t: Template): Float {
        if (W < t.w || H < t.h || t.px.isEmpty()) return 0f
        val stride = W + 1
        val integral = IntArray(stride * (H + 1))
        for (y in 1..H) {
            var row = 0
            for (x in 1..W) {
                row += mask[(y - 1) * W + (x - 1)].toInt()
                integral[y * stride + x] = integral[(y - 1) * stride + x] + row
            }
        }
        val lo = t.onCount * 0.6f
        val hi = t.onCount * 2.5f
        val n = t.px.size
        var best = 0f
        for (y in 0..(H - t.h)) {
            for (x in 0..(W - t.w)) {
                val white = integral[(y + t.h) * stride + x + t.w] - integral[y * stride + x + t.w] -
                        integral[(y + t.h) * stride + x] + integral[y * stride + x]
                if (white < lo || white > hi) continue
                var hits = 0
                for (i in 0 until n) {
                    if (mask[(y + t.py[i]) * W + x + t.px[i]].toInt() != 0) hits++
                }
                val s = hits.toFloat() / n
                if (s > best) best = s
            }
        }
        return best
    }
}
