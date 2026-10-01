package com.example.faceswapjoke

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: FaceOverlayView
    private lateinit var hint: TextView
    private lateinit var thumb: ImageView
    private lateinit var flash: View
    private lateinit var btnLive: TextView

    private var controller: LifecycleCameraController? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Быстрый детектор для видео с камеры */
    private val liveDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    /** Точный детектор для выбранного фото */
    private val photoDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
    }

    private var lastToneSample = 0L
    private var busy = false

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else toast(R.string.need_camera)
        }

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) loadSourceFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        hint = findViewById(R.id.hint)
        thumb = findViewById(R.id.thumb)
        thumb.clipToOutline = true
        flash = findViewById(R.id.flash)
        btnLive = findViewById(R.id.btnLive)

        // TextureView-режим: нужен, чтобы снимать кадр превью для фото
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<View>(R.id.btnGallery).setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.btnGrab).setOnClickListener { grabFaceFromFrame() }
        findViewById<View>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.btnFlip).setOnClickListener { flipCamera() }
        findViewById<View>(R.id.btnClear).setOnClickListener { setSource(null) }
        btnLive.setOnClickListener {
            overlay.liveFeatures = !overlay.liveFeatures
            btnLive.alpha = if (overlay.liveFeatures) 1f else 0.4f
            toast(if (overlay.liveFeatures) R.string.live_on else R.string.live_off)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
        updateHint()
    }

    // ---------- Камера ----------

    private fun startCamera() {
        val c = LifecycleCameraController(this)
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        // MlKitAnalyzer сам переводит точки лица в координаты экрана (с учётом зеркала и поворота)
        c.setImageAnalysisAnalyzer(
            analysisExecutor,
            MlKitAnalyzer(
                listOf(liveDetector),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED,
                ContextCompat.getMainExecutor(this),
            ) { result ->
                onFaces(result.getValue(liveDetector))
            }
        )
        c.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        c.bindToLifecycle(this)
        previewView.controller = c
        controller = c
    }

    private fun flipCamera() {
        val c = controller ?: return
        val next = if (c.cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val available = try { c.hasCamera(next) } catch (e: IllegalStateException) { false }
        if (available) {
            overlay.setTarget(null)
            c.cameraSelector = next
        }
    }

    private fun onFaces(faces: List<Face>?) {
        val shape = faces?.let { Faces.pickMain(it) }?.let { Faces.shapeOf(it) }
        overlay.setTarget(shape)
        updateHint()
        if (shape != null) sampleSkinTone(shape)
    }

    /** Пару раз в секунду сравниваем цвет кожи в кадре и на фото */
    private fun sampleSkinTone(target: FaceShape) {
        val srcSkin = overlay.source?.skin ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastToneSample < 600) return
        lastToneSample = now
        val frame = previewView.bitmap ?: return
        if (previewView.width == 0) return
        val s = frame.width / previewView.width.toFloat()
        val tgtSkin = Faces.meanSkinColor(frame, target, s)
        frame.recycle()
        if (tgtSkin == null) return
        fun gain(i: Int): Float {
            val ratio = (tgtSkin[i] + 1f) / (srcSkin[i] + 1f)
            return (1f + (ratio - 1f) * 0.8f).coerceIn(0.6f, 1.6f)
        }
        overlay.setColorGains(gain(0), gain(1), gain(2))
    }

    // ---------- Выбор лица ----------

    private fun setSource(src: FaceSource?) {
        overlay.source = src
        if (src == null) {
            thumb.visibility = View.GONE
            thumb.setImageDrawable(null)
        } else {
            thumb.setImageBitmap(src.thumbnail())
            thumb.visibility = View.VISIBLE
        }
        lastToneSample = 0L
        updateHint()
    }

    private fun loadSourceFromUri(uri: Uri) {
        if (busy) return
        busy = true
        bgExecutor.execute {
            val bmp = try {
                decodeScaled(uri, 1280)
            } catch (e: Exception) {
                null
            }
            if (bmp == null) {
                runOnUiThread { busy = false; toast(R.string.no_face_in_photo) }
                return@execute
            }
            photoDetector.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { faces ->
                    busy = false
                    val shape = Faces.pickMain(faces)?.let { Faces.shapeOf(it) }
                    if (shape == null) toast(R.string.no_face_in_photo)
                    else setSource(FaceSource(bmp, shape))
                }
                .addOnFailureListener {
                    busy = false
                    toast(R.string.no_face_in_photo)
                }
        }
    }

    private fun decodeScaled(uri: Uri, maxSide: Int): Bitmap {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val k = min(1f, maxSide.toFloat() / max(w, h))
            if (k < 1f) {
                decoder.setTargetSize(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Забрать лицо человека, который сейчас в кадре */
    private fun grabFaceFromFrame() {
        val shape = overlay.target
        val frame = previewView.bitmap
        if (shape == null || frame == null || previewView.width == 0) {
            toast(R.string.no_face_in_frame)
            return
        }
        val s = frame.width / previewView.width.toFloat()
        val b = shape.bounds()
        val pad = (b[2] - b[0]) * 0.25f
        val l = ((b[0] - pad) * s).toInt().coerceIn(0, frame.width - 1)
        val t = ((b[1] - pad) * s).toInt().coerceIn(0, frame.height - 1)
        val r = ((b[2] + pad) * s).toInt().coerceIn(l + 1, frame.width)
        val btm = ((b[3] + pad) * s).toInt().coerceIn(t + 1, frame.height)
        val crop = Bitmap.createBitmap(frame, l, t, r - l, btm - t)
            .copy(Bitmap.Config.ARGB_8888, false)
        frame.recycle()
        setSource(FaceSource(crop, shape.transformed(s, l.toFloat(), t.toFloat())))
        toast(R.string.face_taken)
    }

    // ---------- Фото ----------

    private fun takePhoto() {
        val frame = previewView.bitmap ?: return
        if (overlay.width == 0) return
        val out = frame.copy(Bitmap.Config.ARGB_8888, true)
        frame.recycle()
        val canvas = Canvas(out)
        canvas.scale(out.width / overlay.width.toFloat(), out.height / overlay.height.toFloat())
        overlay.draw(canvas)

        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(250).start()

        bgExecutor.execute {
            val ok = saveToGallery(out)
            out.recycle()
            runOnUiThread { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    private fun saveToGallery(bmp: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "faceswap_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FaceSwapJoke")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: throw IllegalStateException("no stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    // ---------- Разное ----------

    private fun updateHint() {
        val text = when {
            overlay.source == null -> getString(R.string.hint_pick)
            overlay.target == null -> getString(R.string.hint_show_face)
            else -> null
        }
        if (text == null) {
            hint.visibility = View.GONE
        } else {
            if (hint.text != text) hint.text = text
            hint.visibility = View.VISIBLE
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        bgExecutor.shutdown()
        liveDetector.close()
        photoDetector.close()
    }
}
