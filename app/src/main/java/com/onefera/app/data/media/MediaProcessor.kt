package com.onefera.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.onefera.app.data.backend.UserFacingException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** A local file ready to upload, plus what the UI needs to lay it out. */
data class PreparedMedia(val file: File, val aspectRatio: Float, val thumbnail: File? = null, val durationMs: Long = 0L)

/**
 * Prepares picked media for upload: images are downscaled to at most 1440 px and re-encoded as
 * JPEG (keeps uploads small on mobile data); videos are size/length-checked and get a poster frame.
 */
@Singleton
class MediaProcessor @Inject constructor(@ApplicationContext private val context: Context) {

    private val workDir: File get() = File(context.cacheDir, "upload").apply { mkdirs() }

    suspend fun prepareImage(uri: Uri, maxSide: Int = 1440): PreparedMedia = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // 1) Read the size only, 2) decode with a power-of-two sample size, 3) scale and rotate exactly.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UserFacingException("Couldn't open that photo.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw UserFacingException("Couldn't open that photo.")
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = (maxSide.toFloat() / max(decoded.width, decoded.height)).coerceAtMost(1f)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
        val out = File(workDir, "${UUID.randomUUID()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        val aspect = bitmap.width.toFloat() / bitmap.height
        bitmap.recycle()
        PreparedMedia(out, aspect.coerceIn(0.5f, 2f))
    }

    suspend fun prepareVideo(uri: Uri): PreparedMedia = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration > MAX_VIDEO_MS) throw UserFacingException("Keep videos under ${MAX_VIDEO_MS / 1000} seconds.")
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (size > MAX_VIDEO_BYTES) throw UserFacingException("That video is too large (max ${MAX_VIDEO_BYTES / 1_000_000} MB).")
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 9
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 16
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) width = height.also { height = width }
            val frame = retriever.getFrameAtTime(0)
            val thumb = frame?.let { bmp ->
                File(workDir, "${UUID.randomUUID()}.jpg").also { f ->
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    bmp.recycle()
                }
            }
            val copy = File(workDir, "${UUID.randomUUID()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw UserFacingException("Couldn't read that video.")
            PreparedMedia(copy, (width.toFloat() / height).coerceIn(0.5f, 2f), thumb, duration)
        } catch (e: UserFacingException) {
            throw e
        } catch (e: Exception) {
            throw UserFacingException("Couldn't open that video.", e)
        } finally {
            retriever.release()
        }
    }

    /** Deletes temporary upload files. */
    fun cleanUp(vararg media: PreparedMedia?) {
        media.filterNotNull().forEach {
            it.file.delete()
            it.thumbnail?.delete()
        }
    }

    companion object {
        const val MAX_VIDEO_MS = 90_000L
        const val MAX_VIDEO_BYTES = 100_000_000L
        const val MAX_IMAGES = 10
    }
}
