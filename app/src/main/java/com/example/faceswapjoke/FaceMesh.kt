package com.example.faceswapjoke

import kotlin.math.hypot

/**
 * Контуры лица: тип контура ML Kit -> массив координат x0,y0,x1,y1,...
 * Без зависимостей от Android, чтобы логику можно было проверить отдельно.
 */
class FaceShape(val contours: Map<Int, FloatArray>, val outlineType: Int) {

    val outline: FloatArray get() = contours.getValue(outlineType)

    fun centerX(): Float = average(outline, 0)
    fun centerY(): Float = average(outline, 1)

    fun width(): Float {
        val o = outline
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in o.indices step 2) { mn = minOf(mn, o[i]); mx = maxOf(mx, o[i]) }
        return mx - mn
    }

    /** Границы контура: [left, top, right, bottom] */
    fun bounds(): FloatArray {
        val o = outline
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in o.indices step 2) {
            l = minOf(l, o[i]); r = maxOf(r, o[i])
            t = minOf(t, o[i + 1]); b = maxOf(b, o[i + 1])
        }
        return floatArrayOf(l, t, r, b)
    }

    fun sameStructure(other: FaceShape): Boolean =
        contours.size == other.contours.size &&
            contours.all { (k, v) -> other.contours[k]?.size == v.size }

    /** Плавный переход к новому кадру — убирает дрожание точек */
    fun blendTowards(next: FaceShape, t: Float): FaceShape {
        if (!sameStructure(next)) return next
        val jump = hypot(next.centerX() - centerX(), next.centerY() - centerY())
        if (jump > width() * 0.35f) return next // лицо резко сменилось — не сглаживаем
        val out = HashMap<Int, FloatArray>()
        for ((k, a) in contours) {
            val b = next.contours.getValue(k)
            out[k] = FloatArray(a.size) { i -> a[i] + (b[i] - a[i]) * t }
        }
        return FaceShape(out, outlineType)
    }

    /** x' = x * s - dx, y' = y * s - dy */
    fun transformed(s: Float, dx: Float = 0f, dy: Float = 0f): FaceShape =
        FaceShape(
            contours.mapValues { (_, a) ->
                FloatArray(a.size) { i -> a[i] * s - (if (i % 2 == 0) dx else dy) }
            },
            outlineType,
        )

    companion object {
        private fun average(a: FloatArray, off: Int): Float {
            var s = 0f
            var n = 0
            var i = off
            while (i < a.size) { s += a[i]; n++; i += 2 }
            return if (n == 0) 0f else s / n
        }

        /** Многоугольник, растянутый/сжатый относительно своего центра */
        fun scalePolygon(p: FloatArray, k: Float): FloatArray {
            val cx = average(p, 0); val cy = average(p, 1)
            return FloatArray(p.size) { i ->
                if (i % 2 == 0) cx + (p[i] - cx) * k else cy + (p[i] - cy) * k
            }
        }
    }
}

/**
 * Сетка треугольников: точки лица на фото (текстурные координаты)
 * сопоставляются с точками лица в камере (координаты на экране).
 */
class FaceMesh(private val source: FaceShape) {

    private var cacheKey = ""
    private var refType = IntArray(0)
    private var refIdx = IntArray(0)

    var texs = FloatArray(0); private set
    var verts = FloatArray(0); private set
    var indices = ShortArray(0); private set

    /** Заполняет [verts] по лицу из камеры. false — если сопоставить не получилось */
    fun update(target: FaceShape): Boolean {
        val types = source.contours.keys
            .filter { t -> target.contours[t]?.size == source.contours.getValue(t).size }
            .sorted()
        if (source.outlineType !in types) return false

        val key = types.joinToString(",")
        if (key != cacheKey) {
            rebuild(types)
            cacheKey = key
        }
        if (indices.isEmpty()) return false

        if (verts.size != refType.size * 2) verts = FloatArray(refType.size * 2)
        for (i in refType.indices) {
            val c = target.contours.getValue(refType[i])
            verts[2 * i] = c[2 * refIdx[i]]
            verts[2 * i + 1] = c[2 * refIdx[i] + 1]
        }
        return true
    }

    private fun rebuild(types: List<Int>) {
        val minDist = source.width() * 0.012f
        val tList = ArrayList<Int>()
        val iList = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()

        // Сначала контур лица — его точки нужны всегда
        val ordered = listOf(source.outlineType) + types.filter { it != source.outlineType }
        for (t in ordered) {
            val c = source.contours.getValue(t)
            for (j in 0 until c.size / 2) {
                val x = c[2 * j]; val y = c[2 * j + 1]
                var dup = false
                for (k in xs.indices) {
                    if (hypot(xs[k] - x, ys[k] - y) < minDist) { dup = true; break }
                }
                if (dup) continue
                tList.add(t); iList.add(j); xs.add(x); ys.add(y)
            }
        }

        val xa = xs.toFloatArray()
        val ya = ys.toFloatArray()
        val tri = Delaunay.triangulate(xa, ya)
        val outline = source.outline

        // Убираем треугольники, вылезающие за контур лица (вогнутые участки)
        val idx = ArrayList<Short>()
        var k = 0
        while (k < tri.size) {
            val a = tri[k]; val b = tri[k + 1]; val c = tri[k + 2]
            val mx = (xa[a] + xa[b] + xa[c]) / 3f
            val my = (ya[a] + ya[b] + ya[c]) / 3f
            if (Delaunay.pointInPolygon(mx, my, outline)) {
                idx.add(a.toShort()); idx.add(b.toShort()); idx.add(c.toShort())
            }
            k += 3
        }

        refType = tList.toIntArray()
        refIdx = iList.toIntArray()
        texs = FloatArray(xa.size * 2) { i -> if (i % 2 == 0) xa[i / 2] else ya[i / 2] }
        indices = idx.toShortArray()
        verts = FloatArray(xa.size * 2)
    }
}
