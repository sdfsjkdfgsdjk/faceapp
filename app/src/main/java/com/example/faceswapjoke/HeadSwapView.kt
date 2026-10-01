package com.example.faceswapjoke

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** Показывает готовые кадры на весь экран (с обрезкой по краям, без полос) */
class HeadSwapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val m = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun show(b: Bitmap) {
        bitmap = b
        invalidate()
    }

    fun clear() {
        bitmap = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val s = max(width / b.width.toFloat(), height / b.height.toFloat())
        m.setScale(s, s)
        m.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        canvas.drawBitmap(b, m, paint)
    }
}
