package com.example.faceswapjoke

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Обработка каждого кадра камеры (в фоновом потоке):
 * 1. Кадр поворачивается в вертикальное положение (и зеркалится для фронталки).
 * 2. ML Kit находит контуры лица.
 * 3. MediaPipe находит СВОИ волосы и голову, они стираются — фон дорисовывается.
 * 4. Чужая голова (волосы + лицо) натягивается сеткой и подгоняется по цвету.
 * 5. Поверх возвращаются свои настоящие рот (и глаза, если включено).
 */
class HeadSwapProcessor(private val context: Context) {

    interface Listener {
        /** Готовый кадр для экрана (вызывается в фоновом потоке) */
        fun onFrame(bitmap: Bitmap, faceFound: Boolean)
        /** Копия чистого кадра — для «забрать лицо из кадра» */
        fun onGrab(frame: Bitmap)
        /** Копия готового кадра — для сохранения фото */
        fun onCapture(photo: Bitmap)
    }

    var listener: Listener? = null

    @Volatile var source: HeadSource? = null
    @Volatile var liveEyes = false
    @Volatile var liveMouth = true
    @Volatile var grabRequested = false
    @Volatile var captureRequested = false
    @Volatile private var resetRequested = false

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
    )
    private var segmenter: Segmenter? = null
    private var segmenterFailed = false
    private var triedCpu = false

    private var raw: Bitmap? = null
    private var frame: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var half: Bitmap? = null
    private var halfCanvas: Canvas? = null
    private var small: Bitmap? = null
    private var smallCanvas: Canvas? = null
    private var smallPx = IntArray(0)
    private var patch: Bitmap? = null
    private val outs = arrayOfNulls<Bitmap>(4)

    /** Кадр, который сейчас на экране — его не перезаписываем */
    @Volatile var shown: Bitmap? = null
    private var outIdx = 0

    private val matrix = Matrix()
    private val tmpRect = RectF()
    private val dstRect = Rect()
    private val dstRectF = RectF()
    private val path = Path()

    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val headPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val revealPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var headShaderFor: HeadSource? = null
    private var revealShaderFor: Bitmap? = null
    private var revealBlurPx = -1

    private var prevShape: FaceShape? = null
    private val gains = floatArrayOf(1f, 1f, 1f)
    private var gainsFor: HeadSource? = null
    private var frameNo = 0

    fun reset() { resetRequested = true }

    fun close() {
        detector.close()
        segmenter?.close()
    }

    /** Вызывается из анализатора камеры. ImageProxy закрывает вызывающий. */
    fun process(image: ImageProxy, mirror: Boolean) {
        if (resetRequested) { resetRequested = false; prevShape = null }
        frameNo++
        val f = toUpright(image, mirror)

        // --- Лицо: ищем на кадре половинного размера, так быстрее
        val h = half!!
        val faces = try {
            Tasks.await(detector.process(InputImage.fromBitmap(h, 0)))
        } catch (e: Exception) {
            emptyList()
        }
        var shape = Faces.pickMain(faces)?.let { Faces.shapeOf(it) }
            ?.transformed(f.width.toFloat() / h.width)
        val prev = prevShape
        if (shape != null && prev != null) shape = prev.blendTowards(shape, 0.65f)
        prevShape = shape

        if (grabRequested) {
            grabRequested = false
            listener?.onGrab(f.copy(Bitmap.Config.ARGB_8888, false))
        }

        // --- Сборка кадра
        val out = nextOut(f.width, f.height)
        val c = Canvas(out)
        c.drawBitmap(f, 0f, 0f, null)
        val src = source
        if (src != null && shape != null) {
            try {
                compose(c, f, shape, src)
            } catch (e: Exception) {
                Log.e("HeadSwap", "Ошибка обработки кадра", e)
            }
        }

        if (captureRequested) {
            captureRequested = false
            listener?.onCapture(out.copy(Bitmap.Config.ARGB_8888, false))
        }
        listener?.onFrame(out, shape != null)
    }

    // ---------------------------------------------------------------------

    private fun toUpright(image: ImageProxy, mirror: Boolean): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val rw = plane.rowStride / 4
        val buf = plane.buffer
        buf.rewind()

        val srcBmp: Bitmap = if (plane.pixelStride == 4 && buf.remaining() >= rw * h * 4) {
            var r = raw
            if (r == null || r.width != rw || r.height != h) {
                r = Bitmap.createBitmap(rw, h, Bitmap.Config.ARGB_8888)
                raw = r
            }
            r.copyPixelsFromBuffer(buf)
            r
        } else {
            image.toBitmap()
        }

        val rot = image.imageInfo.rotationDegrees
        val uw = if (rot % 180 == 90) h else w
        val uh = if (rot % 180 == 90) w else h

        var f = frame
        if (f == null || f.width != uw || f.height != uh) {
            f = Bitmap.createBitmap(uw, uh, Bitmap.Config.ARGB_8888)
            frame = f
            frameCanvas = Canvas(f)
            val hb = Bitmap.createBitmap(max(1, uw / 2), max(1, uh / 2), Bitmap.Config.ARGB_8888)
            half = hb
            halfCanvas = Canvas(hb)
        }

        matrix.reset()
        matrix.postRotate(rot.toFloat())
        tmpRect.set(0f, 0f, w.toFloat(), h.toFloat())
        matrix.mapRect(tmpRect)
        matrix.postTranslate(-tmpRect.left, -tmpRect.top)
        if (mirror) {
            matrix.postScale(-1f, 1f)
            matrix.postTranslate(uw.toFloat(), 0f)
        }
        val fc = frameCanvas!!
        fc.save()
        fc.concat(matrix)
        fc.clipRect(0, 0, w, h)
        fc.drawBitmap(srcBmp, 0f, 0f, null)
        fc.restore()

        val hb = half!!
        dstRect.set(0, 0, hb.width, hb.height)
        halfCanvas!!.drawBitmap(f, null, dstRect, filterPaint)
        return f
    }

    private fun nextOut(w: Int, h: Int): Bitmap {
        outIdx = (outIdx + 1) % outs.size
        if (outs[outIdx] != null && outs[outIdx] === shown) outIdx = (outIdx + 1) % outs.size
        var o = outs[outIdx]
        if (o == null || o.width != w || o.height != h) {
            o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outs[outIdx] = o
        }
        return o
    }

    private fun compose(c: Canvas, f: Bitmap, shape: FaceShape, src: HeadSource) {
        val fw = shape.width()

        // 1. Стираем свою голову и причёску
        eraseOwnHead(c, f, shape)

        // 2. Цвет кожи под освещение кадра
        if (gainsFor !== src) {
            gainsFor = src
            gains[0] = 1f; gains[1] = 1f; gains[2] = 1f
            updateGains(f, shape, src, 1f)
        } else if (frameNo % 6 == 0) {
            updateGains(f, shape, src, 0.3f)
        }

        // 3. Чужая голова
        val mesh = src.mesh
        if (!mesh.update(shape)) return
        if (headShaderFor !== src) {
            headShaderFor = src
            headPaint.shader = BitmapShader(src.cutout, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        headPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setScale(gains[0], gains[1], gains[2], 1f) }
        )
        c.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            mesh.verts.size, mesh.verts, 0,
            mesh.texs, 0,
            null, 0,
            mesh.indices, 0, mesh.indices.size,
            headPaint,
        )

        // 4. Свои настоящие рот и глаза поверх — живая мимика
        if (!liveMouth && !liveEyes) return
        if (revealShaderFor !== f) {
            revealShaderFor = f
            revealPaint.shader = BitmapShader(f, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val blur = max(1, (fw * 0.018f).roundToInt())
        if (blur != revealBlurPx) {
            revealBlurPx = blur
            revealPaint.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        if (liveMouth) {
            val up = shape.contours[FaceContour.UPPER_LIP_BOTTOM]
            val lo = shape.contours[FaceContour.LOWER_LIP_TOP]
            if (up != null && lo != null) fill(c, FaceShape.scalePolygon(convexHull(up + lo), 1.1f))
        }
        if (liveEyes) {
            shape.contours[FaceContour.LEFT_EYE]?.let { fill(c, FaceShape.scalePolygon(convexHull(it), 1.35f)) }
            shape.contours[FaceContour.RIGHT_EYE]?.let { fill(c, FaceShape.scalePolygon(convexHull(it), 1.35f)) }
        }
    }

    private fun fill(c: Canvas, poly: FloatArray) {
        if (poly.size < 6) return
        path.reset()
        path.moveTo(poly[0], poly[1])
        var i = 2
        while (i < poly.size) { path.lineTo(poly[i], poly[i + 1]); i += 2 }
        path.close()
        c.drawPath(path, revealPaint)
    }

    private fun eraseOwnHead(c: Canvas, f: Bitmap, shape: FaceShape) {
        val sw = SMALL_W
        val sh = max(1, (f.height * sw.toFloat() / f.width).roundToInt())
        var sb = small
        if (sb == null || sb.width != sw || sb.height != sh) {
            sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            small = sb
            smallCanvas = Canvas(sb)
            smallPx = IntArray(sw * sh)
            patch = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        }
        dstRect.set(0, 0, sw, sh)
        smallCanvas!!.drawBitmap(f, null, dstRect, filterPaint)
        sb.getPixels(smallPx, 0, sw, 0, 0, sw, sh)

        val masks = segmentSafe(sb)
        val s = sw / f.width.toFloat()
        val b = shape.bounds()
        val fw = b[2] - b[0]
        val fh = b[3] - b[1]
        val x0 = ((b[0] - fw * 0.9f) * s).toInt().coerceIn(0, sw)
        val x1 = ((b[2] + fw * 0.9f) * s).toInt().coerceIn(0, sw)
        val y0 = ((b[1] - fh * 1.1f) * s).toInt().coerceIn(0, sh)
        val y1 = ((b[3] + fh * 0.8f) * s).toInt().coerceIn(0, sh)
        val chin = (b[3] + fh * 0.03f) * s

        val inFace = Faces.polygonMask(FaceShape.scalePolygon(shape.outline, 1.06f), sw, sh, s)
        val m = FloatArray(sw * sh)
        for (y in y0 until y1) for (x in x0 until x1) {
            val i = y * sw + x
            var v = inFace[i]
            if (masks != null) {
                var seg = masks.sample(masks.hair, x, y, sw, sh)
                if (y <= chin) {
                    seg += masks.sample(masks.face, x, y, sw, sh) + masks.sample(masks.others, x, y, sw, sh)
                }
                v = maxOf(v, seg)
            }
            m[i] = v.coerceIn(0f, 1f)
        }

        val grown = Inpaint.dilate(m, sw, sh, 2)
        val known = FloatArray(sw * sh) { if (grown[it] > 0.3f) 0f else 1f }
        val filled = Inpaint.pushPull(smallPx, known, sw, sh)
        val alpha = Inpaint.blur(grown, sw, sh, 2)
        for (i in filled.indices) {
            val a = (alpha[i].coerceIn(0f, 1f) * 255f).toInt()
            filled[i] = (a shl 24) or (filled[i] and 0xFFFFFF)
        }
        val p = patch!!
        p.setPixels(filled, 0, sw, 0, 0, sw, sh)
        dstRectF.set(0f, 0f, f.width.toFloat(), f.height.toFloat())
        c.drawBitmap(p, null, dstRectF, filterPaint)
    }

    private fun segmentSafe(bmp: Bitmap): Segmenter.Masks? {
        if (segmenterFailed) return null
        try {
            val s = segmenter ?: Segmenter.create(context, preferGpu = !triedCpu).also { segmenter = it }
            return s.segment(bmp)
        } catch (e: Throwable) {
            Log.e("HeadSwap", "Сегментация не работает", e)
            try { segmenter?.close() } catch (_: Throwable) {}
            segmenter = null
            // Видеокарта подвела — один раз пробуем на процессоре
            if (!triedCpu) {
                triedCpu = true
                return try {
                    Segmenter.create(context, preferGpu = false).also { segmenter = it }.segment(bmp)
                } catch (e2: Throwable) {
                    segmenterFailed = true
                    null
                }
            }
            segmenterFailed = true
            return null
        }
    }

    private fun updateGains(f: Bitmap, shape: FaceShape, src: HeadSource, k: Float) {
        val s = src.skin ?: return
        val t = Faces.meanSkinColor(f, shape) ?: return
        for (i in 0..2) {
            val ratio = (t[i] + 1f) / (s[i] + 1f)
            val g = (1f + (ratio - 1f) * 0.85f).coerceIn(0.6f, 1.6f)
            gains[i] += (g - gains[i]) * k
        }
    }

    /** Выпуклая оболочка (Эндрю) — не зависит от порядка точек */
    private fun convexHull(p: FloatArray): FloatArray {
        val n = p.size / 2
        if (n < 3) return p
        val idx = (0 until n).sortedWith(compareBy({ p[2 * it] }, { p[2 * it + 1] }))
        fun cross(o: Int, a: Int, b: Int): Float =
            (p[2 * a] - p[2 * o]) * (p[2 * b + 1] - p[2 * o + 1]) -
                (p[2 * a + 1] - p[2 * o + 1]) * (p[2 * b] - p[2 * o])

        val hull = IntArray(2 * n)
        var k = 0
        for (i in idx) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
            hull[k++] = i
        }
        val lowerSize = k + 1
        for (j in n - 2 downTo 0) {
            val i = idx[j]
            while (k >= lowerSize && cross(hull[k - 2], hull[k - 1], i) <= 0) k--
            hull[k++] = i
        }
        val m = k - 1
        return FloatArray(m * 2) { j -> p[2 * hull[j / 2] + (j % 2)] }
    }

    companion object {
        /** Ширина маленькой копии кадра для сегментации и дорисовки фона */
        const val SMALL_W = 160
    }
}
