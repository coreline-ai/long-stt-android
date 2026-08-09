package com.stt.benchmark.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.StatFs
import androidx.core.content.ContextCompat

object RecorderController {
    fun preconditions(context: Context): RecorderPreconditions.Result = RecorderPreconditions.evaluate(
        permissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED,
        usableInput = hasUsableAudioInput(context),
        availableBytes = StatFs(context.filesDir.path).availableBytes,
    )

    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
        )
    }

    fun hasUsableAudioInput(context: Context): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val hasDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).any { it.isSource }
        val minBuffer = AudioRecord.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return hasDevice && minBuffer > 0 &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}
