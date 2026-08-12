package com.stt.benchmark

import android.app.Application
import com.stt.benchmark.core.AppLog
import com.stt.benchmark.recording.RecordingRecoveryCoordinator
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.export.TranscriptFileShareFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 애플리케이션 진입점.
 * 네이티브 라이브러리 로드는 WhisperEngine 초기화 시점에 수행 (지연 로딩).
 */
class SttApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val processStartedAtMs = System.currentTimeMillis()
        AppLog.i(TAG, "STT Benchmark 앱 시작 - ${BuildConfig.VERSION_NAME}")
        applicationScope.launch {
            runCatching {
                RecordingRecoveryCoordinator(this@SttApp).reconcile(
                    nowMs = processStartedAtMs,
                    startupCutoffExclusiveMs = processStartedAtMs,
                )
            }
                .onSuccess { report ->
                    if (report.reconciledSessionIds.isNotEmpty() || report.actions.isNotEmpty()) {
                        // 세션 ID·파일 경로·녹음 내용은 로그에 남기지 않는다.
                        AppLog.i(
                            TAG,
                            "녹음 복구 검사 완료: sessions=${report.reconciledSessionIds.size}, actions=${report.actions.size}",
                        )
                    }
                }
                .onFailure { error ->
                    AppLog.e(TAG, "녹음 복구 검사 실패: ${error.javaClass.simpleName}")
                }
            runCatching {
                RecordingTranscriptionGroupStore(this@SttApp)
                    .reconcileAfterProcessDeath(processStartedAtMs)
            }.onFailure { error ->
                AppLog.e(TAG, "녹음 그룹 복구 검사 실패: ${error.javaClass.simpleName}")
            }
            runCatching {
                TranscriptFileShareFactory(this@SttApp).cleanupExpired(processStartedAtMs)
            }.onFailure { error ->
                AppLog.e(TAG, "전사 공유 cache 정리 실패: ${error.javaClass.simpleName}")
            }
        }
    }

    companion object {
        private const val TAG = "SttApp"
    }
}
