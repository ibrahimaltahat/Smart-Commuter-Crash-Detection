package com.smartcommuter.data.hardware

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

class AudioBlackboxRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null

    /**
     * تسجيل مقطع صوتي محيطي لمدة محددة (مثلاً 10 ثوانٍ) وإرجاع ملف الصوت الناتج
     */
    suspend fun recordAmbientClip(incidentId: String, clipIndex: Int, durationMs: Long = 10_000L): File? {
        val outputDir = File(context.filesDir, "blackbox_audio").apply { if (!exists()) mkdirs() }
        val outputFile = File(outputDir, "sos_${incidentId}_clip_$clipIndex.m4a")

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder

            // الانتظار طوال مدة التسجيل بشكل غير متزامن (Non-blocking Coroutine delay)
            delay(durationMs)

            stopAndRelease()
            outputFile
        } catch (e: Exception) {
            stopAndRelease()
            null
        }
    }

    fun stopAndRelease() {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }
}