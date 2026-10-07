package com.onefera.app.feature.story

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/** A finger-drawn line; points are fractions (0..1) of the story frame. */
data class StoryStroke(val points: List<Pair<Float, Float>>, val color: Int, val widthFraction: Float = 0.012f)

enum class OverlayKind { Text, Sticker, Mention }

/**
 * Text, an emoji sticker or an @mention on the story. [x]/[y] are the centre as fractions of the
 * frame and [scale] multiplies the kind's base size, so the editor and the renderer agree at any
 * resolution.
 */
data class StoryOverlay(
    val id: Long,
    val kind: OverlayKind,
    val text: String,
    val color: Int,
    val x: Float = 0.5f,
    val y: Float = 0.45f,
    val scale: Float = 1f,
    /** For mentions: the tagged user's uid. */
    val uid: String? = null,
) {
    /** Text height as a fraction of the frame width. */
    val baseSizeFraction: Float get() = when (kind) {
        OverlayKind.Text -> 0.07f
        OverlayKind.Sticker -> 0.16f
        OverlayKind.Mention -> 0.055f
    }
}

/** Photo filters as 4x5 colour matrices (shared by the live preview and the export). */
enum class StoryFilter(val label: String, val matrix: FloatArray?) {
    Original("Original", null),
    Vivid("Vivid", saturation(1.45f)),
    Warm("Warm", floatArrayOf(1.1f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, 0.88f, 0f, -8f, 0f, 0f, 0f, 1f, 0f)),
    Cool("Cool", floatArrayOf(0.9f, 0f, 0f, 0f, -6f, 0f, 1.0f, 0f, 0f, 2f, 0f, 0f, 1.12f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)),
    Fade("Fade", floatArrayOf(0.85f, 0f, 0f, 0f, 28f, 0f, 0.85f, 0f, 0f, 28f, 0f, 0f, 0.85f, 0f, 28f, 0f, 0f, 0f, 1f, 0f)),
    Mono("Mono", saturation(0f)),
    Noir("Noir", contrast(saturation(0f), 1.35f)),
}

private fun saturation(s: Float): FloatArray {
    val r = 0.213f * (1 - s)
    val g = 0.715f * (1 - s)
    val b = 0.072f * (1 - s)
    return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
}

private fun contrast(m: FloatArray, c: Float): FloatArray {
    val t = (1 - c) * 128f
    return FloatArray(20) { i ->
        val row = i / 5
        val col = i % 5
        when {
            row == 3 -> m[i]
            col == 4 -> m[i] * c + t
            else -> m[i] * c
        }
    }
}

/** Draws the edited story into a 1080x1920 JPEG. Runs off the main thread. */
object StoryRenderer {
    const val WIDTH = 1080
    const val HEIGHT = 1920

    fun render(source: Bitmap, filter: StoryFilter, strokes: List<StoryStroke>, overlays: List<StoryOverlay>, out: File): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)

        // Centre-crop the photo to the 9:16 frame, as the editor shows it.
        val scale = maxOf(WIDTH / source.width.toFloat(), HEIGHT / source.height.toFloat())
        val w = source.width * scale
        val h = source.height * scale
        val dst = RectF((WIDTH - w) / 2f, (HEIGHT - h) / 2f, (WIDTH + w) / 2f, (HEIGHT + h) / 2f)
        val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            filter.matrix?.let { colorFilter = ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
        }
        canvas.drawBitmap(source, null, dst, photoPaint)

        strokes.forEach { stroke ->
            if (stroke.points.size < 2) return@forEach
            val path = Path()
            stroke.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * WIDTH, y * HEIGHT) else path.lineTo(x * WIDTH, y * HEIGHT) }
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.color
                    style = Paint.Style.STROKE
                    strokeWidth = stroke.widthFraction * WIDTH
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }

        overlays.forEach { o ->
            val size = o.baseSizeFraction * WIDTH * o.scale
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                color = o.color
                textAlign = Paint.Align.CENTER
            }
            val cx = o.x * WIDTH
            val cy = o.y * HEIGHT
            val baseline = cy - (paint.descent() + paint.ascent()) / 2f
            if (o.kind == OverlayKind.Mention) {
                val textWidth = paint.measureText(o.text)
                val padX = size * 0.5f
                val padY = size * 0.3f
                val pill = RectF(cx - textWidth / 2 - padX, cy - size / 2 - padY, cx + textWidth / 2 + padX, cy + size / 2 + padY)
                canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
            } else if (o.kind == OverlayKind.Text) {
                // A soft shadow keeps text readable on any photo.
                paint.setShadowLayer(size * 0.12f, 0f, size * 0.04f, 0x99000000.toInt())
            }
            canvas.drawText(o.text, cx, baseline, paint)
        }

        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return out
    }
}
