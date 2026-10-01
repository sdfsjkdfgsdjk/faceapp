package com.example.faceswapjoke

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour

object Faces {

    /** Какие контуры ML Kit используем для сетки */
    private val TYPES = intArrayOf(
        FaceContour.FACE,
        FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
        FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
        FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
        FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
        FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
        FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM,
    )

    /** Контуры приходят только у самого крупного лица — его и берём */
    fun pickMain(faces: List<Face>): Face? =
        faces.filter { (it.getContour(FaceContour.FACE)?.points?.size ?: 0) >= 10 }
            .maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }

    fun shapeOf(face: Face): FaceShape? {
        val map = HashMap<Int, FloatArray>()
        for (t in TYPES) {
            val pts = face.getContour(t)?.points ?: continue
            if (pts.isEmpty()) continue
            val arr = FloatArray(pts.size * 2)
            pts.forEachIndexed { i, p -> arr[2 * i] = p.x; arr[2 * i + 1] = p.y }
            map[t] = arr
        }
        if ((map[FaceContour.FACE]?.size ?: 0) < 20) return null
        return FaceShape(map, FaceContour.FACE)
    }

    /**
     * Средний цвет кожи: щёки и нос, без волос и фона.
     * [scale] — перевод координат лица в координаты битмапа.
     */
    fun meanSkinColor(bmp: Bitmap, shape: FaceShape, scale: Float = 1f): FloatArray? {
        val b = shape.bounds()
        val w = (b[2] - b[0]) * scale
        val h = (b[3] - b[1]) * scale
        val cx = shape.centerX() * scale
        val cy = shape.centerY() * scale
        val x0 = cx - w * 0.28f; val x1 = cx + w * 0.28f
        val y0 = cy - h * 0.05f; val y1 = cy + h * 0.22f

        var r = 0.0; var g = 0.0; var bl = 0.0; var n = 0
        val steps = 16
        for (i in 0..steps) for (j in 0..steps) {
            val x = (x0 + (x1 - x0) * i / steps).toInt()
            val y = (y0 + (y1 - y0) * j / steps).toInt()
            if (x !in 0 until bmp.width || y !in 0 until bmp.height) continue
            val c = bmp.getPixel(x, y)
            r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
        }
        if (n < 20) return null
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (bl / n).toFloat())
    }
}

/** Лицо, которое «надеваем»: картинка + его контуры на ней */
class FaceSource(val bitmap: Bitmap, val shape: FaceShape) {
    val mesh = FaceMesh(shape)
    val skin: FloatArray? = Faces.meanSkinColor(bitmap, shape)

    fun thumbnail(): Bitmap {
        val b = shape.bounds()
        val pad = (b[2] - b[0]) * 0.1f
        val l = (b[0] - pad).toInt().coerceIn(0, bitmap.width - 1)
        val t = (b[1] - pad).toInt().coerceIn(0, bitmap.height - 1)
        val r = (b[2] + pad).toInt().coerceIn(l + 1, bitmap.width)
        val btm = (b[3] + pad).toInt().coerceIn(t + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, l, t, r - l, btm - t)
    }
}
