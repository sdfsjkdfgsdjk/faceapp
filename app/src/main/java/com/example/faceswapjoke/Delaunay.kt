package com.example.faceswapjoke

/**
 * Простая триангуляция Делоне (алгоритм Боуэра–Ватсона).
 * Точек на лице ~120, так что квадратичной сложности хватает с запасом,
 * а считается она один раз на выбранное фото.
 */
object Delaunay {

    private class Tri(val a: Int, val b: Int, val c: Int, px: DoubleArray, py: DoubleArray) {
        val cx: Double
        val cy: Double
        val r2: Double

        init {
            val ax = px[a]; val ay = py[a]
            val bx = px[b]; val by = py[b]
            val qx = px[c]; val qy = py[c]
            val d = 2.0 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by))
            if (kotlin.math.abs(d) < 1e-12) {
                // Вырожденный треугольник: окружность «бесконечная», его точно перестроят
                cx = 0.0; cy = 0.0; r2 = Double.MAX_VALUE
            } else {
                val a2 = ax * ax + ay * ay
                val b2 = bx * bx + by * by
                val c2 = qx * qx + qy * qy
                cx = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / d
                cy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / d
                r2 = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy)
            }
        }

        fun inCircle(x: Double, y: Double): Boolean {
            if (r2 == Double.MAX_VALUE) return true
            val dx = x - cx; val dy = y - cy
            return dx * dx + dy * dy < r2 * (1 - 1e-12)
        }
    }

    /** @return индексы вершин треугольников, по 3 на треугольник */
    fun triangulate(xs: FloatArray, ys: FloatArray): IntArray {
        val n = xs.size
        if (n < 3) return IntArray(0)

        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, xs[i].toDouble()); maxX = maxOf(maxX, xs[i].toDouble())
            minY = minOf(minY, ys[i].toDouble()); maxY = maxOf(maxY, ys[i].toDouble())
        }
        val d = maxOf(maxX - minX, maxY - minY, 1.0)
        val midX = (minX + maxX) / 2
        val midY = (minY + maxY) / 2

        val px = DoubleArray(n + 3)
        val py = DoubleArray(n + 3)
        for (i in 0 until n) { px[i] = xs[i].toDouble(); py[i] = ys[i].toDouble() }
        // Огромный «супер-треугольник», внутри которого все точки
        px[n] = midX - 20 * d; py[n] = midY - d
        px[n + 1] = midX; py[n + 1] = midY + 20 * d
        px[n + 2] = midX + 20 * d; py[n + 2] = midY - d

        var tris = ArrayList<Tri>()
        tris.add(Tri(n, n + 1, n + 2, px, py))

        for (i in 0 until n) {
            val x = px[i]; val y = py[i]
            val keep = ArrayList<Tri>(tris.size + 4)
            val edgeCount = HashMap<Long, Int>()
            for (t in tris) {
                if (t.inCircle(x, y)) {
                    addEdge(edgeCount, t.a, t.b)
                    addEdge(edgeCount, t.b, t.c)
                    addEdge(edgeCount, t.c, t.a)
                } else {
                    keep.add(t)
                }
            }
            for ((key, count) in edgeCount) {
                if (count == 1) {
                    val e1 = (key ushr 32).toInt()
                    val e2 = (key and 0xffffffffL).toInt()
                    keep.add(Tri(e1, e2, i, px, py))
                }
            }
            tris = keep
        }

        val out = ArrayList<Int>()
        for (t in tris) {
            if (t.a >= n || t.b >= n || t.c >= n) continue
            out.add(t.a); out.add(t.b); out.add(t.c)
        }
        return out.toIntArray()
    }

    private fun addEdge(map: HashMap<Long, Int>, a: Int, b: Int) {
        val lo = minOf(a, b).toLong()
        val hi = maxOf(a, b).toLong()
        val key = (lo shl 32) or hi
        map[key] = (map[key] ?: 0) + 1
    }

    /** Точка внутри многоугольника (метод луча). poly = x0,y0,x1,y1,... */
    fun pointInPolygon(x: Float, y: Float, poly: FloatArray): Boolean {
        var inside = false
        val n = poly.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[2 * i]; val yi = poly[2 * i + 1]
            val xj = poly[2 * j]; val yj = poly[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
}
