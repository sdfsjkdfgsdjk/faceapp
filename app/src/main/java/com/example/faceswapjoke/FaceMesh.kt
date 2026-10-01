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

    fun width(): Float { val b = bounds(); return b[2] - b[0] }
    fun height(): Float { val b = bounds(); return b[3] - b[1] }

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
        fun average(a: FloatArray, off: Int): Float {
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
 * Сетка треугольников на ВСЮ голову.
 *
 * Внутри лица точки берутся из контуров ML Kit (глаза, брови, нос, губы) —
 * так чужое лицо повторяет мимику. Снаружи лица строятся кольца точек:
 *  - ближнее кольцо (лоб, скулы, уши) тянется вслед за контуром лица;
 *  - дальние кольца (причёска) двигаются жёстко — поворот + масштаб + сдвиг,
 *    подобранные по контуру лица. Так волосы не дрожат и не мнутся.
 */
class HeadMesh(private val source: FaceShape) {

    private var cacheKey = ""
    // Ссылка на точку: тип контура (>=0) + индекс, либо кольцо (тип = -1 - номер кольца)
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

        val sim = Similarity.fit(source.outline, target.outline)
        val tOut = target.outline
        val tcx = target.centerX(); val tcy = target.centerY()
        val sOut = source.outline
        val scx = source.centerX(); val scy = source.centerY()

        if (verts.size != refType.size * 2) verts = FloatArray(refType.size * 2)
        for (i in refType.indices) {
            val t = refType[i]
            val j = refIdx[i]
            if (t >= 0) {
                val c = target.contours.getValue(t)
                verts[2 * i] = c[2 * j]
                verts[2 * i + 1] = c[2 * j + 1]
            } else {
                val ring = -1 - t
                val k = RINGS[ring]
                if (ring < RADIAL_RINGS) {
                    verts[2 * i] = tcx + (tOut[2 * j] - tcx) * k
                    verts[2 * i + 1] = tcy + (tOut[2 * j + 1] - tcy) * k
                } else {
                    val sx = scx + (sOut[2 * j] - scx) * k
                    val sy = scy + (sOut[2 * j + 1] - scy) * k
                    verts[2 * i] = sim.mapX(sx, sy)
                    verts[2 * i + 1] = sim.mapY(sx, sy)
                }
            }
        }
        return true
    }

    private fun rebuild(types: List<Int>) {
        val minDist = source.width() * 0.012f
        val tList = ArrayList<Int>()
        val iList = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()

        fun add(t: Int, j: Int, x: Float, y: Float) {
            for (k in xs.indices) {
                if (hypot(xs[k] - x, ys[k] - y) < minDist) return
            }
            tList.add(t); iList.add(j); xs.add(x); ys.add(y)
        }

        val ordered = listOf(source.outlineType) + types.filter { it != source.outlineType }
        for (t in ordered) {
            val c = source.contours.getValue(t)
            for (j in 0 until c.size / 2) add(t, j, c[2 * j], c[2 * j + 1])
        }

        // Кольца вокруг лица (через одну точку контура — 18 точек на кольцо)
        val o = source.outline
        val cx = source.centerX(); val cy = source.centerY()
        for (r in RINGS.indices) {
            val k = RINGS[r]
            for (j in 0 until o.size / 2 step 2) {
                add(-1 - r, j, cx + (o[2 * j] - cx) * k, cy + (o[2 * j + 1] - cy) * k)
            }
        }

        val xa = xs.toFloatArray()
        val ya = ys.toFloatArray()
        val tri = Delaunay.triangulate(xa, ya)

        // Граница сетки — внешнее кольцо
        val outer = FloatArray(o.size)
        for (j in 0 until o.size / 2) {
            outer[2 * j] = cx + (o[2 * j] - cx) * RINGS.last()
            outer[2 * j + 1] = cy + (o[2 * j + 1] - cy) * RINGS.last()
        }

        val idx = ArrayList<Short>()
        var k = 0
        while (k < tri.size) {
            val a = tri[k]; val b = tri[k + 1]; val c = tri[k + 2]
            val mx = (xa[a] + xa[b] + xa[c]) / 3f
            val my = (ya[a] + ya[b] + ya[c]) / 3f
            if (Delaunay.pointInPolygon(mx, my, outer)) {
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

    companion object {
        /** Множители колец относительно контура лица */
        val RINGS = floatArrayOf(1.22f, 1.75f, 2.4f, 3.3f)
        /** Сколько первых колец тянутся за контуром (остальные — жёстко) */
        const val RADIAL_RINGS = 1
    }
}

/** Подобие (поворот + масштаб + сдвиг), подобранное методом наименьших квадратов */
class Similarity(
    private val a: Float, private val b: Float,
    private val sx: Float, private val sy: Float,
    private val tx: Float, private val ty: Float,
) {
    fun mapX(x: Float, y: Float) = a * (x - sx) - b * (y - sy) + tx
    fun mapY(x: Float, y: Float) = b * (x - sx) + a * (y - sy) + ty

    companion object {
        fun fit(src: FloatArray, dst: FloatArray): Similarity {
            val scx = FaceShape.average(src, 0); val scy = FaceShape.average(src, 1)
            val dcx = FaceShape.average(dst, 0); val dcy = FaceShape.average(dst, 1)
            var num1 = 0.0; var num2 = 0.0; var den = 0.0
            for (i in 0 until src.size / 2) {
                val px = (src[2 * i] - scx).toDouble(); val py = (src[2 * i + 1] - scy).toDouble()
                val qx = (dst[2 * i] - dcx).toDouble(); val qy = (dst[2 * i + 1] - dcy).toDouble()
                num1 += px * qx + py * qy
                num2 += px * qy - py * qx
                den += px * px + py * py
            }
            if (den < 1e-9) return Similarity(1f, 0f, scx, scy, dcx, dcy)
            return Similarity((num1 / den).toFloat(), (num2 / den).toFloat(), scx, scy, dcx, dcy)
        }
    }
}
