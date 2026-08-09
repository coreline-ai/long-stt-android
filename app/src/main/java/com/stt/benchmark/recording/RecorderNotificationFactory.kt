package com.stt.benchmark.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.stt.benchmark.MainActivity
import com.stt.benchmark.R

internal class RecorderNotificationFactory(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "긴 음성 녹음",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "장시간 마이크 녹음 상태와 정지 동작"
                setSound(null, null)
            }
        )
    }

    fun foreground(phase: RecordingPhase, elapsedMs: Long = 0L): Notification {
        val content = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, RecorderService::class.java).setAction(RecorderService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (phase) {
            RecordingPhase.PREPARING -> "마이크와 저장공간을 확인하는 중입니다."
            RecordingPhase.ROLLING_OVER -> "현재 청크를 안전하게 마감하는 중입니다."
            RecordingPhase.FINALIZING -> "녹음 파일을 검사하고 저장하는 중입니다."
            else -> "녹음 중 · ${formatElapsed(elapsedMs)}"
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("Long STT 녹음")
            .setContentText(text)
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "녹음 정지", stop)
            .build()
    }

    fun terminal(saved: Boolean): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_mic_notification)
        .setContentTitle(if (saved) "녹음이 저장되었습니다" else "녹음을 확인해 주세요")
        .setContentText(if (saved) "확정된 오디오 파일을 보관했습니다." else "안전하게 확정하지 못한 파일은 격리했습니다.")
        .setAutoCancel(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()

    fun notifyTerminal(saved: Boolean) {
        manager.notify(NOTIFICATION_ID, terminal(saved))
    }

    fun notifyForeground(phase: RecordingPhase, elapsedMs: Long) {
        manager.notify(NOTIFICATION_ID, foreground(phase, elapsedMs))
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1_000L).coerceAtLeast(0L)
        return "%02d:%02d:%02d".format(seconds / 3_600, (seconds / 60) % 60, seconds % 60)
    }

    companion object {
        const val CHANNEL_ID = "long_stt_recording"
        const val NOTIFICATION_ID = 2_202
    }
}
