package com.example.faceswapjoke

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/**
 * Нейросеть MediaPipe «selfie multiclass»: для каждого пикселя даёт вероятность
 * классов 0 фон, 1 волосы, 2 кожа тела, 3 кожа лица, 4 одежда, 5 прочее (очки, шапка).
 */
class Segmenter private constructor(private val segmenter: ImageSegmenter) {

    class Masks(
        val w: Int, val h: Int,
        val hair: FloatArray,
        val face: FloatArray,
        val others: FloatArray,
    ) {
        /** Значение маски в точке картинки размером imgW×imgH */
        fun sample(m: FloatArray, x: Int, y: Int, imgW: Int, imgH: Int): Float {
            val mx = if (imgW == w) x else (x * w / imgW).coerceIn(0, w - 1)
            val my = if (imgH == h) y else (y * h / imgH).coerceIn(0, h - 1)
            return m[my * w + mx]
        }
    }

    fun segment(bmp: Bitmap): Masks? {
        val result = segmenter.segment(BitmapImageBuilder(bmp).build())
        val masks = result.confidenceMasks().orElse(null) ?: return null
        try {
            if (masks.size < 6) return null
            val w = masks[0].width
            val h = masks[0].height
            return Masks(w, h, read(masks[1]), read(masks[3]), read(masks[5]))
        } finally {
            masks.forEach { it.close() }
        }
    }

    private fun read(img: MPImage): FloatArray {
        val buf = ByteBufferExtractor.extract(img)
        buf.order(ByteOrder.nativeOrder())
        buf.rewind()
        val fb = buf.asFloatBuffer()
        val out = FloatArray(img.width * img.height)
        fb.get(out, 0, minOf(out.size, fb.remaining()))
        return out
    }

    fun close() = segmenter.close()

    companion object {
        const val MODEL = "selfie_multiclass_256x256.tflite"

        /** Сначала пробуем видеокарту (быстрее), при ошибке — процессор */
        fun create(context: Context, preferGpu: Boolean): Segmenter {
            if (preferGpu) {
                try {
                    return Segmenter(build(context, Delegate.GPU))
                } catch (e: Throwable) {
                    Log.w("Segmenter", "GPU недоступен, работаем на CPU", e)
                }
            }
            return Segmenter(build(context, Delegate.CPU))
        }

        private fun build(context: Context, delegate: Delegate): ImageSegmenter {
            val base = BaseOptions.builder()
                .setModelAssetPath(MODEL)
                .setDelegate(delegate)
                .build()
            val options = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputCategoryMask(false)
                .setOutputConfidenceMasks(true)
                .build()
            return ImageSegmenter.createFromOptions(context, options)
        }
    }
}
