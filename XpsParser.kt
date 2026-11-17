package com.example.xpsviewer

import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Bone(val name: String, val parent: Int, val pos: FloatArray)
/** verts: 16 floats per vertex: pos3 nrm3 uv2 boneIdx4 weight4 */
class XMesh(val name: String, val textures: List<String>, val verts: FloatArray, val idx: IntArray)
class Model(val bones: List<Bone>, val meshes: List<XMesh>)

object XpsParser {
    private fun str(b: ByteBuffer): String {
        var len = 0; var shift = 0
        while (true) {
            val x = b.get().toInt() and 0xFF
            len = len or ((x and 0x7F) shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        val a = ByteArray(len); b.get(a); return String(a, Charsets.UTF_8)
    }

    /** Works for binary .mesh and .xps (same layout; .xps only adds header data). */
    fun parse(bytes: ByteArray): Model {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || b.getInt(0) != 323232) return body(b, true)
        b.int
        val major = b.short.toInt(); val minor = b.short.toInt()
        str(b)
        val sl = b.int
        val p0 = b.position()
        val tangents = major < 2 || (major == 2 && minor <= 12)
        var last: Exception? = null
        // header layout differs slightly between versions: try a few strategies
        for (s in 0..2) {
            try {
                b.position(p0)
                when (s) {
                    0 -> b.position(p0 + sl * 4)
                    1 -> { str(b); str(b); str(b) }
                    2 -> { str(b); str(b); str(b); b.position(b.position() + sl * 4) }
                }
                return body(b, tangents)
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("Unsupported XPS header", last)
    }

    private fun body(b: ByteBuffer, tangents: Boolean): Model {
        val nb = b.int; require(nb in 0..5000)
        val bones = List(nb) {
            val n = str(b); val p = b.short.toInt()
            Bone(n, p, floatArrayOf(b.float, b.float, b.float))
        }
        val nm = b.int; require(nm in 0..2000)
        val meshes = ArrayList<XMesh>()
        repeat(nm) {
            val name = str(b)
            val uvc = b.int; require(uvc in 0..8)
            val tc = b.int; require(tc in 0..16)
            val tex = List(tc) { val f = str(b); b.int; f }
            val vc = b.int; require(vc in 0..2_000_000)
            val v = FloatArray(vc * 16)
            for (i in 0 until vc) {
                val o = i * 16
                for (k in 0 until 6) v[o + k] = b.float
                b.position(b.position() + 4)
                for (l in 0 until uvc) { val u = b.float; val w = b.float; if (l == 0) { v[o + 6] = u; v[o + 7] = w } }
                if (tangents) b.position(b.position() + uvc * 16)
                if (nb > 0) {
                    for (k in 0 until 4) v[o + 8 + k] = b.short.toInt().coerceIn(0, 127).toFloat()
                    for (k in 0 until 4) v[o + 12 + k] = b.float
                } else v[o + 12] = 1f
            }
            val fc = b.int; require(fc in 0..4_000_000)
            val idx = IntArray(fc * 3) { b.int }
            require(idx.all { it in 0 until vc })
            meshes.add(XMesh(name, tex, v, idx))
        }
        return Model(bones, meshes)
    }
}

/** .pose text: "boneName: rotX rotY rotZ posX posY posZ scaX scaY scaZ" (rotation in degrees) */
class Pose(val d: Map<String, FloatArray>) {
    companion object {
        fun parse(text: String): Pose {
            val m = HashMap<String, FloatArray>()
            text.lineSequence().forEach { l ->
                val i = l.lastIndexOf(':')
                if (i > 0) {
                    val v = l.substring(i + 1).trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
                    if (v.size >= 9) m[l.substring(0, i).trim()] = v.toFloatArray()
                }
            }
            return Pose(m)
        }
    }
}

object Skeleton {
    private val ID = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 1f)

    fun compute(m: Model, a: Pose?, b: Pose?, t: Float, out: FloatArray) {
        for (i in 0 until 128) Matrix.setIdentityM(out, i * 16)
        val n = m.bones.size
        val world = Array(n) { FloatArray(16) }
        val done = BooleanArray(n)
        val l = FloatArray(16); val inv = FloatArray(16)
        fun solve(i: Int) {
            if (done[i]) return
            done[i] = true
            val bn = m.bones[i]; val p = bn.parent
            val hasP = p in 0 until n && p != i
            if (hasP) solve(p)
            val da = a?.d?.get(bn.name) ?: ID
            val db = b?.d?.get(bn.name) ?: da
            val v = FloatArray(9) { da[it] + (db[it] - da[it]) * t }
            val pp = if (hasP) m.bones[p].pos else floatArrayOf(0f, 0f, 0f)
            Matrix.setIdentityM(l, 0)
            Matrix.translateM(l, 0, bn.pos[0] - pp[0], bn.pos[1] - pp[1], bn.pos[2] - pp[2])
            Matrix.translateM(l, 0, v[3], v[4], v[5])
            Matrix.rotateM(l, 0, v[2], 0f, 0f, 1f)
            Matrix.rotateM(l, 0, v[0], 1f, 0f, 0f)
            Matrix.rotateM(l, 0, v[1], 0f, 1f, 0f)
            Matrix.scaleM(l, 0, v[6], v[7], v[8])
            if (hasP) Matrix.multiplyMM(world[i], 0, world[p], 0, l, 0) else l.copyInto(world[i])
            if (i < 128) {
                Matrix.setIdentityM(inv, 0)
                Matrix.translateM(inv, 0, -bn.pos[0], -bn.pos[1], -bn.pos[2])
                Matrix.multiplyMM(out, i * 16, world[i], 0, inv, 0)
            }
        }
        for (i in 0 until n) solve(i)
    }
}
