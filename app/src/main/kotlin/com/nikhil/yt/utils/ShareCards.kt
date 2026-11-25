package com.nikhil.yt.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.nikhil.yt.R
import com.nikhil.yt.together.TogetherRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shareable 4:5 images in Spectra's look: the song's own colours, Poppins, the ghost mark.
 * Drawn on a Canvas (no view needed), so they can be made from anywhere.
 */
object ShareCards {
    private const val W = 1080
    private const val H = 1350
    private const val GUTTER = 80f

    suspend fun nowPlaying(
        context: Context,
        songId: String,
        title: String,
        artist: String,
        coverUrl: String?,
        accent: Int,
        waveform: FloatArray?,
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = createBitmap(W, H)
        val canvas = Canvas(bitmap)
        background(canvas, accent)

        val art = 760f
        val artRect = RectF((W - art) / 2f, 110f, (W + art) / 2f, 110f + art)
        drawCover(context, canvas, coverUrl, artRect, radius = 44f, fallback = accent)

        var y = artRect.bottom + 112f
        y = drawLine(canvas, title, font(context, R.font.poppins_bold), 64f, 0xFFFFFFFF.toInt(), y)
        drawLine(canvas, artist, font(context, R.font.poppins_medium), 38f, 0xCCFFFFFF.toInt(), y + 10f)

        val shape = waveform?.takeIf { s -> s.any { it > 0.05f } } ?: pseudoShape(songId)
        drawWaveform(canvas, shape, RectF(GUTTER, y + 64f, W - GUTTER, y + 150f), lighten(accent))

        footer(context, canvas, "https://music.youtube.com/watch?v=$songId")
        bitmap
    }

    suspend fun recap(context: Context, recap: TogetherRecap, accent: Int): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = createBitmap(W, H)
        val canvas = Canvas(bitmap)
        background(canvas, accent)
        val bold = font(context, R.font.poppins_bold)
        val medium = font(context, R.font.poppins_medium)

        var y = 190f
        y = drawLine(canvas, context.getString(R.string.together_recap_title), bold, 72f, 0xFFFFFFFF.toInt(), y)
        if (recap.people.isNotEmpty()) {
            y = drawLine(canvas, context.getString(R.string.together_recap_with, recap.people.joinToString(", ")), medium, 40f, 0xD9FFFFFF.toInt(), y + 12f)
        }
        y = drawLine(
            canvas,
            context.getString(R.string.together_recap_stats, recap.tracks.size, recap.minutes.coerceAtLeast(1), recap.reactions),
            medium, 34f, 0xB3FFFFFF.toInt(), y + 8f,
        )

        // Up to six covers from the session, in the order they played.
        val covers = recap.tracks.take(6)
        val cell = (W - GUTTER * 2 - 2 * 28f) / 3f
        covers.forEachIndexed { i, track ->
            val left = GUTTER + (i % 3) * (cell + 28f)
            val top = y + 80f + (i / 3) * (cell + 28f)
            drawCover(context, canvas, track.thumbnailUrl, RectF(left, top, left + cell, top + cell), 28f, accent)
        }
        val rows = (covers.size + 2) / 3
        y += 80f + rows * (cell + 28f) + 40f

        recap.topTrack?.let { top ->
            y = drawLine(canvas, context.getString(R.string.together_recap_top), medium, 30f, 0xB3FFFFFF.toInt(), y + 20f)
            drawLine(canvas, top.title, bold, 44f, 0xFFFFFFFF.toInt(), y + 6f)
        }
        footer(context, canvas, qr = null)
        bitmap
    }

    /** Saves [bitmap] and opens the system share sheet. */
    fun share(context: Context, bitmap: Bitmap, name: String) {
        val uri = ComposeToImage.saveBitmapAsFile(context, bitmap, name)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun background(canvas: Canvas, accent: Int) {
        val top = ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.35f)
        val bottom = ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.88f)
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
    }

    private suspend fun drawCover(context: Context, canvas: Canvas, url: String?, rect: RectF, radius: Float, fallback: Int) {
        val art = url?.let { loadBitmap(context, it, rect.width().toInt()) }
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.withClip(clip) {
            if (art != null) {
                val scaled = Bitmap.createScaledBitmap(art, rect.width().toInt(), rect.height().toInt(), true)
                drawBitmap(scaled, rect.left, rect.top, null)
            } else {
                drawRect(rect, Paint().apply { color = ColorUtils.blendARGB(fallback, 0xFF000000.toInt(), 0.5f) })
            }
        }
    }

    /** Draws one centred, ellipsized line; returns the baseline-to-next-line y. */
    private fun drawLine(canvas: Canvas, text: String, typeface: Typeface, size: Float, color: Int, top: Float): Float {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size
            this.color = color
            textAlign = Paint.Align.CENTER
        }
        val line = TextUtils.ellipsize(text, paint, W - GUTTER * 2, TextUtils.TruncateAt.END).toString()
        val baseline = top + size
        canvas.drawText(line, W / 2f, baseline, paint)
        return baseline + size * 0.35f
    }

    private fun drawWaveform(canvas: Canvas, shape: FloatArray, area: RectF, color: Int) {
        val bars = 48
        val step = area.width() / bars
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND; strokeWidth = step * 0.45f }
        for (i in 0 until bars) {
            val v = shape[(i * shape.size / bars).coerceIn(0, shape.lastIndex)].coerceIn(0.08f, 1f)
            val x = area.left + step * (i + 0.5f)
            val half = area.height() / 2f * v
            canvas.drawLine(x, area.centerY() - half, x, area.centerY() + half, paint)
        }
    }

    private fun footer(context: Context, canvas: Canvas, qr: String?) {
        val ghostSize = 72
        val bottom = H - 90f
        ContextCompat.getDrawable(context, R.drawable.ic_spectra_ghost)?.let { ghost ->
            ghost.setTint(0xFFFFFFFF.toInt())
            ghost.setBounds(GUTTER.toInt(), (bottom - ghostSize).toInt(), GUTTER.toInt() + ghostSize, bottom.toInt())
            ghost.draw(canvas)
        }
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.poppins_semibold); textSize = 40f; color = 0xFFFFFFFF.toInt()
        }
        canvas.drawText(context.getString(R.string.app_name), GUTTER + ghostSize + 20f, bottom - 22f, name)

        if (qr == null) return
        val size = 190
        val code = runCatching { BarcodeEncoder().encodeBitmap(qr, BarcodeFormat.QR_CODE, size, size) }.getOrNull() ?: return
        val box = RectF(W - GUTTER - size - 24f, bottom - size - 24f, W - GUTTER, bottom)
        canvas.drawRoundRect(box, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        canvas.drawBitmap(code, box.left + 12f, box.top + 12f, null)
        val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.poppins_medium); textSize = 26f; color = 0xB3FFFFFF.toInt(); textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(context.getString(R.string.scan_to_play), box.left - 24f, bottom - 26f, hint)
    }

    private fun lighten(color: Int) = ColorUtils.blendARGB(color, 0xFFFFFFFF.toInt(), 0.55f)

    /** A stable, song-specific shape for songs never played far enough to learn the real one. */
    private fun pseudoShape(id: String): FloatArray {
        var seed = id.hashCode().toLong()
        return FloatArray(48) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            0.3f + ((seed ushr 33) % 1000) / 1000f * 0.7f
        }
    }

    private fun font(context: Context, id: Int): Typeface = ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD

    private suspend fun loadBitmap(context: Context, url: String, size: Int): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build()
        ImageLoader(context).execute(request).image?.toBitmap()
    }.getOrNull()
}
