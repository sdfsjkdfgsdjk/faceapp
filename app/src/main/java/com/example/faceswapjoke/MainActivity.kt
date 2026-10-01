package com.example.faceswapjoke

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), HeadSwapProcessor.Listener {

    private lateinit var screen: HeadSwapView
    private lateinit var hint: TextView
    private lateinit var thumb: ImageView
    private lateinit var flash: View
    private lateinit var btnLive: TextView
    private lateinit var busyView: View

    private lateinit var processor: HeadSwapProcessor
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFront = true

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /** Точный детектор и сегментатор — для подготовки «надеваемой» головы */
    @Volatile private var photoDetectorCreated = false
    private val photoDetector: FaceDetector by lazy {
        photoDetectorCreated = true
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .build()
        )
    }
    private var photoSegmenter: Segmenter? = null

    private var faceVisible = false
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

        screen = findViewById(R.id.screen)
        hint = findViewById(R.id.hint)
        thumb = findViewById(R.id.thumb)
        thumb.clipToOutline = true
        flash = findViewById(R.id.flash)
        btnLive = findViewById(R.id.btnLive)
        busyView = findViewById(R.id.busy)

        processor = HeadSwapProcessor(applicationContext)
        processor.listener = this
        btnLive.alpha = if (processor.liveEyes) 1f else 0.4f

        val controls = findViewById<View>(R.id.controls)
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        findViewById<View>(R.id.btnGallery).setOnClickListener {
            if (!busy) pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<View>(R.id.btnGrab).setOnClickListener {
            if (!faceVisible) toast(R.string.no_face_in_frame)
            else if (!busy) processor.grabRequested = true
        }
        findViewById<View>(R.id.btnShutter).setOnClickListener {
            processor.captureRequested = true
            flash.alpha = 0.85f
            flash.animate().alpha(0f).setDuration(250).start()
        }
        findViewById<View>(R.id.btnFlip).setOnClickListener { flipCamera() }
        findViewById<View>(R.id.btnClear).setOnClickListener { setSource(null) }
        btnLive.setOnClickListener {
            processor.liveEyes = !processor.liveEyes
            btnLive.alpha = if (processor.liveEyes) 1f else 0.4f
            toast(if (processor.liveEyes) R.string.live_on else R.string.live_off)
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        var selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            useFront = !useFront
            selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        )
                    )
                    .build()
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val mirror = useFront
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                processor.process(image, mirror)
            } catch (e: Exception) {
                Log.e("HeadSwap", "Кадр пропущен", e)
            } finally {
                image.close()
            }
        }

        processor.reset()
        provider.unbindAll()
        try {
            provider.bindToLifecycle(this, selector, analysis)
        } catch (e: Exception) {
            Log.e("HeadSwap", "Камера не запустилась", e)
            toast(R.string.need_camera)
        }
    }

    private fun flipCamera() {
        useFront = !useFront
        bindCamera()
    }

    // ---------- Колбэки обработчика (фоновый поток) ----------

    override fun onFrame(bitmap: Bitmap, faceFound: Boolean) {
        runOnUiThread {
            processor.shown = bitmap
            screen.show(bitmap)
            if (faceFound != faceVisible) {
                faceVisible = faceFound
                updateHint()
            }
        }
    }

    override fun onGrab(frame: Bitmap) {
        runOnUiThread { buildSource(frame, R.string.no_face_in_frame, R.string.face_taken) }
    }

    override fun onCapture(photo: Bitmap) {
        bgExecutor.execute {
            val ok = saveToGallery(photo)
            runOnUiThread { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    // ---------- Выбор головы ----------

    private fun setSource(src: HeadSource?) {
        processor.source = src
        if (src == null) {
            thumb.visibility = View.GONE
            thumb.setImageDrawable(null)
        } else {
            thumb.setImageBitmap(src.thumbnail)
            thumb.visibility = View.VISIBLE
        }
        updateHint()
    }

    private fun loadSourceFromUri(uri: Uri) {
        if (busy) return
        setBusy(true)
        bgExecutor.execute {
            val bmp = try { decodeScaled(uri, 1600) } catch (e: Exception) { null }
            runOnUiThread {
                setBusy(false)
                if (bmp == null) toast(R.string.no_face_in_photo)
                else buildSource(bmp, R.string.no_face_in_photo, null)
            }
        }
    }

    /** Вырезает голову в фоне: распознавание + нейросеть волос (1–2 секунды) */
    private fun buildSource(bmp: Bitmap, failMsg: Int, okMsg: Int?) {
        if (busy) return
        setBusy(true)
        bgExecutor.execute {
            val src = try {
                val seg = photoSegmenter ?: Segmenter.create(applicationContext, preferGpu = false)
                    .also { photoSegmenter = it }
                HeadSource.build(bmp, photoDetector, seg)
            } catch (e: Throwable) {
                Log.e("HeadSwap", "Не удалось вырезать голову", e)
                null
            }
            runOnUiThread {
                setBusy(false)
                if (src == null) toast(failMsg)
                else {
                    setSource(src)
                    okMsg?.let { toast(it) }
                }
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

    private fun setBusy(b: Boolean) {
        busy = b
        busyView.visibility = if (b) View.VISIBLE else View.GONE
    }

    // ---------- Фото ----------

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
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
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
            processor.source == null -> getString(R.string.hint_pick)
            !faceVisible -> getString(R.string.hint_show_face)
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
        cameraProvider?.unbindAll()
        analysisExecutor.execute { processor.close() }
        analysisExecutor.shutdown()
        bgExecutor.execute {
            photoSegmenter?.close()
            if (photoDetectorCreated) photoDetector.close()
        }
        bgExecutor.shutdown()
    }
}
