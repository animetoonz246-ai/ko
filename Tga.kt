package com.example.xpsviewer

import android.graphics.Bitmap

object Tga {
    fun decode(d: ByteArray): Bitmap? {
        try {
            fun u(i: Int) = d[i].toInt() and 255
            val type = u(2); val w = u(12) or (u(13) shl 8); val h = u(14) or (u(15) shl 8)
            val by = u(16) / 8; val desc = u(17)
            if ((by != 3 && by != 4) || (type != 2 && type != 10) || w <= 0 || h <= 0) return null
            var p = 18 + u(0) + (u(5) or (u(6) shl 8)) * (u(7) / 8)
            val px = IntArray(w * h)
            fun rd(): Int {
                val c = (if (by == 4) u(p + 3) else 255 shl 0) shl 24 or (u(p + 2) shl 16) or (u(p + 1) shl 8) or u(p)
                p += by; return c
            }
            var i = 0
            if (type == 2) while (i < w * h) px[i++] = rd()
            else while (i < w * h) {
                val hd = u(p++); val n = (hd and 127) + 1
                if (hd >= 128) { val c = rd(); repeat(n) { if (i < w * h) px[i++] = c } }
                else repeat(n) { if (i < w * h) px[i++] = rd() }
            }
            if (desc and 0x20 == 0) {
                val f = IntArray(w * h)
                for (y in 0 until h) System.arraycopy(px, (h - 1 - y) * w, f, y * w, w)
                return Bitmap.createBitmap(f, w, h, Bitmap.Config.ARGB_8888)
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) { return null }
    }
}
