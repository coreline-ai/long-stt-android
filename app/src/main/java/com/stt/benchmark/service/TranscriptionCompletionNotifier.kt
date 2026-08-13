package com.stt.benchmark.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stt.benchmark.MainActivity
import com.stt.benchmark.R
import com.stt.benchmark.data.CompletedResultLaunchContract
import com.stt.benchmark.data.CompletedResultTargetStore

/** 완료 결과 type/ID만 담은 알림을 게시하고 동일 identity로 정리한다. */
internal class TranscriptionCompletionNotifier @JvmOverloads constructor(
    context: Context,
    private val canPost: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private val appContext = context.applicationContext

    fun post(target: CompletedResultTargetStore.Target): Boolean {
        if (!canPost()) return false
        val launchIntent = CompletedResultLaunchContract.write(
            Intent(appContext, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            target,
        )
        val contentIntent = PendingIntent.getActivity(
            appContext,
            CompletedResultLaunchContract.requestCode(target),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, TranscriptionService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("전사 완료")
            .setContentText("보관함에서 완료 결과를 확인하세요.")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        appContext.getSystemService(NotificationManager::class.java)
            .notify(TranscriptionService.COMPLETION_NOTIFICATION_ID, notification)
        return true
    }

    fun cancel() {
        appContext.getSystemService(NotificationManager::class.java)
            .cancel(TranscriptionService.COMPLETION_NOTIFICATION_ID)
    }
}
