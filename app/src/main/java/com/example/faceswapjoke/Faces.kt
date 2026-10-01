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

    /** Средний цвет кожи: щёки и нос, без волос и фона */
    fun meanSkinColor(bmp: Bitmap, shape: FaceShape): FloatArray? {
        val b = shape.bounds()
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        val cx = shape.centerX()
        val cy = shape.centerY()
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

    /** Точка внутри многоугольника для всех пикселей прямоугольника — маска 0/1 */
    fun polygonMask(poly: FloatArray, w: Int, h: Int, scale: Float): FloatArray {
        val m = FloatArray(w * h)
        val n = poly.size / 2
        val xs = FloatArray(n) { poly[2 * it] * scale }
        val ys = FloatArray(n) { poly[2 * it + 1] * scale }
        val cross = FloatArray(n)
        for (y in 0 until h) {
            val py = y + 0.5f
            var k = 0
            var j = n - 1
            for (i in 0 until n) {
                if ((ys[i] > py) != (ys[j] > py)) {
                    cross[k++] = (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]
                }
                j = i
            }
            if (k < 2) continue
            java.util.Arrays.sort(cross, 0, k)
            var p = 0
            while (p + 1 < k) {
                val from = maxOf(0, (cross[p] - 0.5f).toInt() + 1).coerceAtLeast(0)
                val to = minOf(w - 1, (cross[p + 1] - 0.5f).toInt())
                for (x in from..to) m[y * w + x] = 1f
                p += 2
            }
        }
        return m
    }
}
