package com.example.faceswapjoke

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.face.FaceContour
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Рисует лицо с фото поверх лица в камере.
 *
 * Как это работает:
 * 1. Сетка треугольников натягивает текстуру лица с фото на точки лица в кадре.
 * 2. Маска с размытыми краями обрезает лицо по контуру, чтобы не было резкой границы.
 * 3. В режиме «живой мимики» в маске вырезаются глаза и рот — видно настоящие.
 * 4. Цвет подгоняется под освещение в кадре.
 *
 * Всё рисуется в полразрешения в отдельный битмап, а потом растягивается на экран.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var source: FaceSource? = null
        set(value) {
            field = value
            texPaint.shader = value?.let {
                BitmapShader(it.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            gains[0] = 1f; gains[1] = 1f; gains[2] = 1f
            updateColorFilter()
            invalidate()
        }

    var liveFeatures = true
        set(value) { field = value; invalidate() }

    /** Текущее лицо в кадре (в координатах этого View) */
    var target: FaceShape? = null
        private set

    private val scale = 0.5f
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val dstRect = Rect()

    private val texPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val dstInPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private val outPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var maskBlurPx = -1
    private var cutBlurPx = -1
    private val path = Path()

    private val gains = floatArrayOf(1f, 1f, 1f)

    fun setTarget(shape: FaceShape?) {
        val prev = target
        target = if (prev != null && shape != null) prev.blendTowards(shape, 0.6f) else shape
        invalidate()
    }

    /** Множители R, G, B для подгонки цвета лица под кадр (плавно) */
    fun setColorGains(r: Float, g: Float, b: Float) {
        val k = 0.3f
        gains[0] += (r - gains[0]) * k
        gains[1] += (g - gains[1]) * k
        gains[2] += (b - gains[2]) * k
        updateColorFilter()
        invalidate()
    }

    private fun updateColorFilter() {
        outPaint.colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setScale(gains[0], gains[1], gains[2], 1f) }
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layer?.recycle(); mask?.recycle()
        layer = null; mask = null
        if (w <= 0 || h <= 0) return
        val lw = max(1, (w * scale).roundToInt())
        val lh = max(1, (h * scale).roundToInt())
        layer = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888).also { layerCanvas = Canvas(it) }
        mask = Bitmap.createBitmap(lw, lh, Bitmap.Config.ALPHA_8).also { maskCanvas = Canvas(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val src = source ?: return
        val t = target ?: return
        val layer = layer ?: return
        val lc = layerCanvas ?: return
        val mask = mask ?: return
        val mc = maskCanvas ?: return

        val mesh = src.mesh
        if (!mesh.update(t)) return

        // 1. Натягиваем лицо с фото на сетку
        layer.eraseColor(0)
        lc.save()
        lc.scale(scale, scale)
        lc.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            mesh.verts.size, mesh.verts, 0,
            mesh.texs, 0,
            null, 0,
            mesh.indices, 0, mesh.indices.size,
            texPaint,
        )
        lc.restore()

        // 2. Маска с мягкими краями
        mask.eraseColor(0)
        val faceW = t.width() * scale
        val blur = max(2, (faceW * 0.06f).roundToInt())
        if (blur != maskBlurPx) {
            maskBlurPx = blur
            maskPaint.maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
        }
        fillPolygon(mc, FaceShape.scalePolygon(t.outline, 0.92f), maskPaint)

        // 3. Живая мимика: показываем настоящие глаза и рот
        if (liveFeatures) {
            val cb = max(1, (faceW * 0.025f).roundToInt())
            if (cb != cutBlurPx) {
                cutBlurPx = cb
                cutPaint.maskFilter = BlurMaskFilter(cb.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            t.contours[FaceContour.LEFT_EYE]?.let {
                fillPolygon(mc, FaceShape.scalePolygon(convexHull(it), 1.35f), cutPaint)
            }
            t.contours[FaceContour.RIGHT_EYE]?.let {
                fillPolygon(mc, FaceShape.scalePolygon(convexHull(it), 1.35f), cutPaint)
            }
            val upper = t.contours[FaceContour.UPPER_LIP_BOTTOM]
            val lower = t.contours[FaceContour.LOWER_LIP_TOP]
            if (upper != null && lower != null) {
                fillPolygon(mc, FaceShape.scalePolygon(convexHull(upper + lower), 1.15f), cutPaint)
            }
        }
        lc.drawBitmap(mask, 0f, 0f, dstInPaint)

        // 4. На экран, с подгонкой цвета
        dstRect.set(0, 0, width, height)
        canvas.drawBitmap(layer, null, dstRect, outPaint)
    }

    /** Многоугольник в координатах View -> заливка на холсте половинного размера */
    private fun fillPolygon(c: Canvas, poly: FloatArray, paint: Paint) {
        if (poly.size < 6) return
        path.reset()
        path.moveTo(poly[0] * scale, poly[1] * scale)
        var i = 2
        while (i < poly.size) {
            path.lineTo(poly[i] * scale, poly[i + 1] * scale)
            i += 2
        }
        path.close()
        c.drawPath(path, paint)
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
}
