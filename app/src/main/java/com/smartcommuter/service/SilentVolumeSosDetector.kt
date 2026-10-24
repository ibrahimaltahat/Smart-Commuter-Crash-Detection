package com.smartcommuter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

class SilentVolumeSosDetector(
    private val context: Context,
    private val onSilentSosTriggered: () -> Unit
) {

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val REQUIRED_PRESS_COUNT = 4
        private const val TIME_WINDOW_MS = 3000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pressTimestamps = mutableListOf<Long>()
    private var initialVolumeLevel: Int = -1
    private var isRegistered = false
    private var isIgnoringSelfChange = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != VOLUME_CHANGED_ACTION || isIgnoringSelfChange) return

            val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
            if (streamType == AudioManager.STREAM_MUSIC ||
                streamType == AudioManager.STREAM_RING ||
                streamType == AudioManager.STREAM_NOTIFICATION
            ) {
                handleVolumeButtonInteraction(streamType)
            }
        }
    }

    fun startListening() {
        if (isRegistered) return
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(volumeReceiver, filter)
        }
        isRegistered = true
    }

    fun stopListening() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {
        }
        isRegistered = false
        pressTimestamps.clear()
    }

    private fun handleVolumeButtonInteraction(streamType: Int) {
        val now = System.currentTimeMillis()
        pressTimestamps.removeAll { now - it > TIME_WINDOW_MS }

        if (pressTimestamps.isEmpty()) {
            initialVolumeLevel = audioManager.getStreamVolume(streamType)
        }

        pressTimestamps.add(now)
        Log.d("SilentSOS", "Volume button press detected: ${pressTimestamps.size}/$REQUIRED_PRESS_COUNT")

        val currentVol = audioManager.getStreamVolume(streamType)
        if (currentVol == 0 && pressTimestamps.size < REQUIRED_PRESS_COUNT) {
            isIgnoringSelfChange = true
            audioManager.setStreamVolume(streamType, 1, 0)
            isIgnoringSelfChange = false
        }

        if (pressTimestamps.size >= REQUIRED_PRESS_COUNT) {
            pressTimestamps.clear()

            if (initialVolumeLevel != -1) {
                isIgnoringSelfChange = true
                audioManager.setStreamVolume(streamType, initialVolumeLevel, 0)
                isIgnoringSelfChange = false
            }

            triggerStealthHapticConfirmation()
            onSilentSosTriggered()
        }
    }

    private fun triggerStealthHapticConfirmation() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 60, 80, 60)
            val amplitudes = intArrayOf(0, 80, 0, 80)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 60, 80, 60), -1)
        }
    }
}