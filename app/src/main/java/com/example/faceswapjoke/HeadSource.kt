package com.example.faceswapjoke

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetector

/**
 * Голова, которую «надеваем»: вырезка с прозрачным фоном
 * (волосы + лицо + уши + очки/шапка), её контуры лица и цвет кожи.
 */
class HeadSource(
    val cutout: Bitmap,
    val shape: FaceShape,
    val skin: FloatArray?,
    val thumbnail: Bitmap,
) {
    val mesh = HeadMesh(shape)

    companion object {

        /**
         * Вырезает голову с картинки. Вызывать НЕ из главного потока.
         * @return null, если лицо не найдено
         */
        fun build(bitmap: Bitmap, detector: FaceDetector, segmenter: Segmenter): HeadSource? {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
            val full = Faces.pickMain(faces)?.let { Faces.shapeOf(it) } ?: return null

            // Берём область с запасом под причёску
            val b = full.bounds()
            val fw = b[2] - b[0]
            val fh = b[3] - b[1]
            val l = (b[0] - fw * 1.1f).toInt().coerceIn(0, bitmap.width - 1)
            val t = (b[1] - fh * 1.3f).toInt().coerceIn(0, bitmap.height - 1)
            val r = (b[2] + fw * 1.1f).toInt().coerceIn(l + 1, bitmap.width)
            val btm = (b[3] + fh * 1.2f).toInt().coerceIn(t + 1, bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, l, t, r - l, btm - t)
                .copy(Bitmap.Config.ARGB_8888, false)
            val shape = full.transformed(1f, l.toFloat(), t.toFloat())

            val w = crop.width
            val h = crop.height
            val masks = segmenter.segment(crop)
            val inFace = Faces.polygonMask(shape.outline, w, h, 1f)

            // Ниже подбородка лицо/кожа плавно исчезают (шея остаётся своя), волосы — нет
            val chinY = shape.bounds()[3]
            val fadeFrom = chinY + fh * 0.03f
            val fadeTo = chinY + fh * 0.14f

            val px = IntArray(w * h)
            crop.getPixels(px, 0, w, 0, 0, w, h)
            for (y in 0 until h) {
                val fade = when {
                    y <= fadeFrom -> 1f
                    y >= fadeTo -> 0f
                    else -> 1f - (y - fadeFrom) / (fadeTo - fadeFrom)
                }
                for (x in 0 until w) {
                    val i = y * w + x
                    var a = if (masks != null) {
                        masks.sample(masks.hair, x, y, w, h) +
                            (masks.sample(masks.face, x, y, w, h) +
                                masks.sample(masks.others, x, y, w, h)) * fade
                    } else 0f
                    if (inFace[i] > 0f) a = maxOf(a, fade)
                    if (x == 0 || y == 0 || x == w - 1 || y == h - 1) a = 0f
                    val alpha = (a.coerceIn(0f, 1f) * 255f).toInt()
                    px[i] = (alpha shl 24) or (px[i] and 0xFFFFFF)
                }
            }
            val cutout = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val skin = Faces.meanSkinColor(crop, shape)

            // Миниатюра для угла экрана
            val tl = (b[0] - l - fw * 0.5f).toInt().coerceIn(0, w - 1)
            val tt = (b[1] - t - fh * 0.6f).toInt().coerceIn(0, h - 1)
            val tr = (b[2] - l + fw * 0.5f).toInt().coerceIn(tl + 1, w)
            val tb = (b[3] - t + fh * 0.2f).toInt().coerceIn(tt + 1, h)
            val thumb = Bitmap.createBitmap(crop, tl, tt, tr - tl, tb - tt)

            return HeadSource(cutout, shape, skin, thumb)
        }
    }
}
