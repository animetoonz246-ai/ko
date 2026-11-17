package com.example.xpsviewer

import android.graphics.Bitmap
import android.opengl.GLES30 as G
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max

class XpsRenderer : GLSurfaceView.Renderer {
    private class GlMesh(val vao: Int, val count: Int, val tex: Int)

    private var prog = 0
    private val meshes = ArrayList<GlMesh>()
    private var model: Model? = null
    @Volatile var poseA: Pose? = null
    @Volatile var poseB: Pose? = null
    @Volatile var playing = false
    @Volatile var rotX = 10f
    @Volatile var rotY = 0f
    @Volatile var zoom = 1f
    private var center = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f
    private var phase = 0f
    private var last = 0L
    private var aspect = 1f
    private val proj = FloatArray(16); private val view = FloatArray(16)
    private val vp = FloatArray(16); private val mm = FloatArray(16)
    private val skin = FloatArray(128 * 16)
    private var whiteTex = 0

    /** Must run on the GL thread (use GLSurfaceView.queueEvent). */
    fun setModel(m: Model, bitmaps: List<Bitmap?>) {
        meshes.forEach { G.glDeleteVertexArrays(1, intArrayOf(it.vao), 0) }
        meshes.clear()
        model = m
        var mn = floatArrayOf(1e9f, 1e9f, 1e9f); var mx = floatArrayOf(-1e9f, -1e9f, -1e9f)
        m.meshes.forEachIndexed { mi, me ->
            val n = me.verts.size / 16
            for (i in 0 until n) for (k in 0..2) {
                val v = me.verts[i * 16 + k]; if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v
            }
            val ids = IntArray(3); G.glGenVertexArrays(1, ids, 0); G.glGenBuffers(2, ids, 1)
            G.glBindVertexArray(ids[0])
            val vb = ByteBuffer.allocateDirect(me.verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(me.verts).also { it.position(0) }
            G.glBindBuffer(G.GL_ARRAY_BUFFER, ids[1])
            G.glBufferData(G.GL_ARRAY_BUFFER, me.verts.size * 4, vb, G.GL_STATIC_DRAW)
            val ib = ByteBuffer.allocateDirect(me.idx.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(me.idx).also { it.position(0) }
            G.glBindBuffer(G.GL_ELEMENT_ARRAY_BUFFER, ids[2])
            G.glBufferData(G.GL_ELEMENT_ARRAY_BUFFER, me.idx.size * 4, ib, G.GL_STATIC_DRAW)
            val sizes = intArrayOf(3, 3, 2, 4, 4); val offs = intArrayOf(0, 12, 24, 32, 48)
            for (a in 0..4) { G.glEnableVertexAttribArray(a); G.glVertexAttribPointer(a, sizes[a], G.GL_FLOAT, false, 64, offs[a]) }
            G.glBindVertexArray(0)
            val bmp = bitmaps.getOrNull(mi)
            meshes.add(GlMesh(ids[0], me.idx.size, if (bmp != null) makeTex(bmp) else whiteTex))
        }
        center = floatArrayOf((mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2)
        radius = max(0.01f, max(mx[0] - mn[0], max(mx[1] - mn[1], mx[2] - mn[2])) / 2)
        zoom = 1f
    }

    private fun makeTex(b: Bitmap): Int {
        val t = IntArray(1); G.glGenTextures(1, t, 0)
        G.glBindTexture(G.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(G.GL_TEXTURE_2D, 0, b, 0)
        G.glGenerateMipmap(G.GL_TEXTURE_2D)
        G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_MIN_FILTER, G.GL_LINEAR_MIPMAP_LINEAR)
        G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_S, G.GL_REPEAT)
        G.glTexParameteri(G.GL_TEXTURE_2D, G.GL_TEXTURE_WRAP_T, G.GL_REPEAT)
        return t[0]
    }

    private fun shader(type: Int, src: String): Int {
        val s = G.glCreateShader(type); G.glShaderSource(s, src); G.glCompileShader(s)
        val ok = IntArray(1); G.glGetShaderiv(s, G.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException(G.glGetShaderInfoLog(s))
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, c: EGLConfig?) {
        val vs = """#version 300 es
uniform mat4 uVP; uniform mat4 uModel; uniform mat4 uBones[128];
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNrm; layout(location=2) in vec2 aUv;
layout(location=3) in vec4 aIdx; layout(location=4) in vec4 aW;
out vec3 vN; out vec2 vUv;
void main(){
  mat4 s = uBones[int(aIdx.x)]*aW.x + uBones[int(aIdx.y)]*aW.y + uBones[int(aIdx.z)]*aW.z + uBones[int(aIdx.w)]*aW.w;
  if (dot(aW, vec4(1.0)) < 0.001) s = mat4(1.0);
  mat4 m = uModel * s;
  vN = mat3(m) * aNrm; vUv = aUv;
  gl_Position = uVP * m * vec4(aPos, 1.0);
}"""
        val fs = """#version 300 es
precision mediump float;
uniform sampler2D uTex; in vec3 vN; in vec2 vUv; out vec4 o;
void main(){
  vec4 c = texture(uTex, vUv);
  if (c.a < 0.05) discard;
  float l = 0.4 + 0.6 * abs(dot(normalize(vN), normalize(vec3(0.3, 0.5, 1.0))));
  o = vec4(c.rgb * l, 1.0);
}"""
        prog = G.glCreateProgram()
        G.glAttachShader(prog, shader(G.GL_VERTEX_SHADER, vs)); G.glAttachShader(prog, shader(G.GL_FRAGMENT_SHADER, fs))
        G.glLinkProgram(prog)
        G.glEnable(G.GL_DEPTH_TEST)
        val w = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); w.eraseColor(-1)
        whiteTex = makeTex(w)
        meshes.clear(); model = null   // GL context lost: caller re-sends the model
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { G.glViewport(0, 0, w, h); aspect = w.toFloat() / h }

    override fun onDrawFrame(gl: GL10?) {
        G.glClearColor(0.14f, 0.14f, 0.17f, 1f)
        G.glClear(G.GL_COLOR_BUFFER_BIT or G.GL_DEPTH_BUFFER_BIT)
        val m = model ?: return
        val now = System.nanoTime(); if (last != 0L && playing) phase += (now - last) / 1e9f; last = now
        val t = if (playing) 0.5f - 0.5f * cos(phase * 2.5f) else 0f
        Skeleton.compute(m, poseA, poseB ?: poseA, t, skin)
        val dist = radius * 2.8f / zoom
        Matrix.perspectiveM(proj, 0, 45f, aspect, dist * 0.05f, dist * 10f)
        Matrix.setLookAtM(view, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.setIdentityM(mm, 0)
        Matrix.rotateM(mm, 0, rotX, 1f, 0f, 0f); Matrix.rotateM(mm, 0, rotY, 0f, 1f, 0f)
        Matrix.translateM(mm, 0, -center[0], -center[1], -center[2])
        G.glUseProgram(prog)
        G.glUniformMatrix4fv(G.glGetUniformLocation(prog, "uVP"), 1, false, vp, 0)
        G.glUniformMatrix4fv(G.glGetUniformLocation(prog, "uModel"), 1, false, mm, 0)
        G.glUniformMatrix4fv(G.glGetUniformLocation(prog, "uBones"), 128, false, skin, 0)
        G.glActiveTexture(G.GL_TEXTURE0)
        G.glUniform1i(G.glGetUniformLocation(prog, "uTex"), 0)
        for (me in meshes) {
            G.glBindTexture(G.GL_TEXTURE_2D, me.tex)
            G.glBindVertexArray(me.vao)
            G.glDrawElements(G.GL_TRIANGLES, me.count, G.GL_UNSIGNED_INT, 0)
        }
    }
}
