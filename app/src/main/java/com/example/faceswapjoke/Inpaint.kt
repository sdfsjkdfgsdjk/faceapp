package com.example.faceswapjoke

/**
 * Быстрая «дорисовка» фона (push-pull): заполняет неизвестные пиксели
 * плавно размытыми цветами соседних известных. Так стирается своя причёска —
 * на её месте появляется продолжение стены/фона.
 * Работает на маленькой картинке (~160×280), занимает пару миллисекунд.
 */
object Inpaint {

    /**
     * @param rgb цвета 0xAARRGGBB (альфа игнорируется)
     * @param known 1 — пиксель известен, 0 — нужно дорисовать
     * @return новые цвета (известные пиксели почти не меняются)
     */
    fun pushPull(rgb: IntArray, known: FloatArray, w: Int, h: Int): IntArray {
        val ws = ArrayList<Int>(); val hs = ArrayList<Int>()
        val rs = ArrayList<FloatArray>(); val gs = ArrayList<FloatArray>()
        val bs = ArrayList<FloatArray>(); val wts = ArrayList<FloatArray>()

        // Уровень 0
        val n = w * h
        val r0 = FloatArray(n); val g0 = FloatArray(n); val b0 = FloatArray(n); val w0 = FloatArray(n)
        for (i in 0 until n) {
            val c = rgb[i]
            r0[i] = ((c shr 16) and 255).toFloat()
            g0[i] = ((c shr 8) and 255).toFloat()
            b0[i] = (c and 255).toFloat()
            w0[i] = known[i].coerceIn(0f, 1f)
        }
        ws.add(w); hs.add(h); rs.add(r0); gs.add(g0); bs.add(b0); wts.add(w0)

        // Push: уменьшаем, усредняя только известные пиксели
        while (ws.last() > 1 || hs.last() > 1) {
            val pw = ws.last(); val ph = hs.last()
            val nw = (pw + 1) / 2; val nh = (ph + 1) / 2
            val pr = rs.last(); val pg = gs.last(); val pb = bs.last(); val pwt = wts.last()
            val r = FloatArray(nw * nh); val g = FloatArray(nw * nh)
            val b = FloatArray(nw * nh); val wt = FloatArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in 0..1) for (dx in 0..1) {
                    val xx = 2 * x + dx; val yy = 2 * y + dy
                    if (xx >= pw || yy >= ph) continue
                    val j = yy * pw + xx
                    val q = pwt[j]
                    sr += pr[j] * q; sg += pg[j] * q; sb += pb[j] * q; sw += q
                }
                val i = y * nw + x
                if (sw > 1e-4f) { r[i] = sr / sw; g[i] = sg / sw; b[i] = sb / sw }
                wt[i] = minOf(1f, sw)
            }
            ws.add(nw); hs.add(nh); rs.add(r); gs.add(g); bs.add(b); wts.add(wt)
        }

        // Pull: заполняем пробелы с более грубого уровня (билинейно)
        for (lvl in ws.size - 2 downTo 0) {
            val cw = ws[lvl]; val ch = hs[lvl]
            val pw = ws[lvl + 1]; val ph = hs[lvl + 1]
            val r = rs[lvl]; val g = gs[lvl]; val b = bs[lvl]; val wt = wts[lvl]
            val cr = rs[lvl + 1]; val cg = gs[lvl + 1]; val cb = bs[lvl + 1]
            for (y in 0 until ch) {
                val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, (ph - 1).toFloat())
                val y0 = fy.toInt(); val y1 = minOf(y0 + 1, ph - 1); val ty = fy - y0
                for (x in 0 until cw) {
                    val i = y * cw + x
                    val q = wt[i]
                    if (q >= 1f) continue
                    val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, (pw - 1).toFloat())
                    val x0 = fx.toInt(); val x1 = minOf(x0 + 1, pw - 1); val tx = fx - x0
                    val i00 = y0 * pw + x0; val i01 = y0 * pw + x1
                    val i10 = y1 * pw + x0; val i11 = y1 * pw + x1
                    fun bil(a: FloatArray) =
                        (a[i00] * (1 - tx) + a[i01] * tx) * (1 - ty) + (a[i10] * (1 - tx) + a[i11] * tx) * ty
                    r[i] = r[i] * q + bil(cr) * (1 - q)
                    g[i] = g[i] * q + bil(cg) * (1 - q)
                    b[i] = b[i] * q + bil(cb) * (1 - q)
                    wt[i] = 1f
                }
            }
        }

        return IntArray(n) { i ->
            val rr = r0[i].toInt().coerceIn(0, 255)
            val gg = g0[i].toInt().coerceIn(0, 255)
            val bb = b0[i].toInt().coerceIn(0, 255)
            (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /** Расширение маски (максимум по квадрату radius) */
    fun dilate(m: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return m
        val tmp = FloatArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0f
            for (dx in -radius..radius) {
                val xx = x + dx
                if (xx in 0 until w) v = maxOf(v, m[y * w + xx])
            }
            tmp[y * w + x] = v
        }
        val out = FloatArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0f
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy in 0 until h) v = maxOf(v, tmp[yy * w + x])
            }
            out[y * w + x] = v
        }
        return out
    }

    /** Размытие маски (коробочный фильтр radius) */
    fun blur(m: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius <= 0) return m
        val tmp = FloatArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dx in -radius..radius) {
                val xx = x + dx
                if (xx in 0 until w) { s += m[y * w + xx]; c++ }
            }
            tmp[y * w + x] = s / c
        }
        val out = FloatArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (dy in -radius..radius) {
                val yy = y + dy
                if (yy in 0 until h) { s += tmp[yy * w + x]; c++ }
            }
            out[y * w + x] = s / c
        }
        return out
    }
}
