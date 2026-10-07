package com.onefera.app.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Records a voice note to an AAC (.m4a) file with the platform MediaRecorder: no extra
 * libraries, mono 44.1 kHz at 64 kbps (about 0.5 MB a minute). The caller holds RECORD_AUDIO.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Elapsed time of the current recording, in milliseconds. */
    fun elapsedMs(): Long = if (recorder == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Starts recording; returns false (and cleans up) if the microphone can't be opened. */
    fun start(): Boolean {
        if (recorder != null) return true
        val out = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(MAX_DURATION_MS.toInt())
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start recording", e)
            r.release()
            out.delete()
            false
        }
    }

    /** Stops and returns the recording with its length, or null if it was too short or failed. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val out = file
        val duration = elapsedMs()
        recorder = null
        file = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stop() follows start() too quickly: nothing usable was recorded.
            false
        } finally {
            r.release()
        }
        if (!ok || out == null || duration < MIN_DURATION_MS) {
            out?.delete()
            return null
        }
        return out to duration
    }

    fun cancel() {
        stop()?.first?.delete()
    }

    companion object {
        private const val TAG = "OneFeraVoice"
        const val MIN_DURATION_MS = 700L
        const val MAX_DURATION_MS = 2 * 60 * 1000L
    }
}
