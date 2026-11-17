package com.example.xpsviewer

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import kotlin.concurrent.thread

class XpsGLView(c: android.content.Context, val r: XpsRenderer) : GLSurfaceView(c) {
    private var lx = 0f; private var ly = 0f
    private val sd = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { r.zoom = (r.zoom * d.scaleFactor).coerceIn(0.2f, 8f); return true }
    })
    init { setEGLContextClientVersion(3); setEGLConfigChooser(8, 8, 8, 8, 16, 0); setRenderer(r) }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        if (e.pointerCount == 1 && !sd.isInProgress) {
            if (e.action == MotionEvent.ACTION_MOVE) { r.rotY += (e.x - lx) * 0.4f; r.rotX += (e.y - ly) * 0.4f }
            lx = e.x; ly = e.y
        } else { lx = e.x; ly = e.y }
        return true
    }
}

class MainActivity : ComponentActivity() {
    private val renderer = XpsRenderer()
    private lateinit var gl: XpsGLView
    private var pickingPoseB = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::scanFolder) }
    private val pickPose = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val txt = contentResolver.openInputStream(uri)!!.use { String(it.readBytes()) }
            val p = Pose.parse(txt)
            if (pickingPoseB) renderer.poseB = p else renderer.poseA = p
            toast("Pose: ${p.d.size} bones")
        } catch (e: Exception) { toast("Pose error: ${e.message}") }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        gl = XpsGLView(this, renderer)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 12f; setOnClickListener { f() } }
        val play = btn("Play") {}
        play.setOnClickListener { renderer.playing = !renderer.playing; play.text = if (renderer.playing) "Stop" else "Play" }
        bar.addView(btn("Folder") { pickFolder.launch(null) })
        bar.addView(btn("Pose A") { pickingPoseB = false; pickPose.launch(arrayOf("*/*")) })
        bar.addView(btn("Pose B") { pickingPoseB = true; pickPose.launch(arrayOf("*/*")) })
        bar.addView(play)
        val root = FrameLayout(this)
        root.addView(gl)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        toast("Tap Folder and choose the model folder (.mesh / .xps + textures)")
    }

    override fun onResume() { super.onResume(); gl.onResume() }
    override fun onPause() { super.onPause(); gl.onPause() }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun scanFolder(uri: Uri) {
        val dir = DocumentFile.fromTreeUri(this, uri) ?: return
        val files = dir.listFiles().filter { it.isFile }
        val models = files.filter { val n = it.name?.lowercase() ?: ""; n.endsWith(".mesh") || n.endsWith(".xps") }
        when (models.size) {
            0 -> toast("No .mesh or .xps file in this folder")
            1 -> load(models[0], files)
            else -> AlertDialog.Builder(this).setTitle("Choose model")
                .setItems(models.map { it.name }.toTypedArray()) { _, i -> load(models[i], files) }.show()
        }
    }

    private fun load(f: DocumentFile, siblings: List<DocumentFile>) {
        toast("Loading ${f.name}...")
        thread {
            try {
                val model = XpsParser.parse(contentResolver.openInputStream(f.uri)!!.use { it.readBytes() })
                val bmps = model.meshes.map { m -> m.textures.firstOrNull()?.let { findBitmap(it, siblings) } }
                gl.queueEvent { renderer.setModel(model, bmps) }
                toast("${model.meshes.size} meshes, ${model.bones.size} bones")
            } catch (e: Exception) { toast("Load error: ${e.message}") }
        }
    }

    private fun findBitmap(name: String, files: List<DocumentFile>): Bitmap? {
        val clean = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        val base = clean.substringBeforeLast('.')
        val cand = files.firstOrNull { it.name?.lowercase() == clean }
            ?: files.firstOrNull { val n = it.name?.lowercase() ?: ""; n.substringBeforeLast('.') == base && n.substringAfterLast('.') in setOf("png", "jpg", "jpeg", "tga", "bmp", "webp") }
            ?: return null
        val bytes = contentResolver.openInputStream(cand.uri)!!.use { it.readBytes() }
        return if (cand.name!!.lowercase().endsWith(".tga")) Tga.decode(bytes)
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
