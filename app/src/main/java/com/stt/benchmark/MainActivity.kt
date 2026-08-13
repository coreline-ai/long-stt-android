package com.stt.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import com.stt.benchmark.core.AppLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.summary.CodexAuthViewModel
import com.stt.benchmark.data.CompletedResultLaunchContract
import com.stt.benchmark.ui.CompletedResultTarget
import com.stt.benchmark.ui.LongSttApp
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import com.stt.benchmark.ui.transcription.TranscriptionRoute
import com.stt.benchmark.ui.transcription.DebugTranscriptionRequest
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var viewModel: SttViewModel? = null
    private var automationReceiverRegistered = false
    private var completedResultLaunchTarget by mutableStateOf<CompletedResultTarget?>(null)
    private var completedResultLaunchHandled = false

    /**
     * adb에서 전사 트리거용 BroadcastReceiver
     * 사용법:
     *   adb shell am broadcast -a com.stt.benchmark.RUN_STT \
     *     --es model /data/.../ggml-small.bin \
     *     --es audio /data/.../4인_index_00.wav \
     *     --es note "index_00"
     */
    private val sttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val vm = viewModel ?: return
            val modelPath = intent.getStringExtra("model") ?: return
            val audioPath = intent.getStringExtra("audio") ?: return
            val note = intent.getStringExtra("note") ?: ""

            runOnUiThread {
                vm.loadAndTranscribe(modelPath, audioPath, note)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        completedResultLaunchHandled = savedInstanceState?.getBoolean(
            STATE_COMPLETED_RESULT_LAUNCH_HANDLED,
            false,
        ) == true
        if (!completedResultLaunchHandled) {
            completedResultLaunchTarget = CompletedResultLaunchContract.read(intent)
                ?.let(CompletedResultTarget::fromStoredTarget)
        }

        // 외부 adb 자동화 surface는 debug APK에만 노출한다.
        if (BuildConfig.DEBUG) {
            ContextCompat.registerReceiver(
                this,
                sttReceiver,
                IntentFilter("com.stt.benchmark.RUN_STT"),
                ContextCompat.RECEIVER_EXPORTED
            )
            automationReceiverRegistered = true
        }

        // Intent extra로 자동 전사 모드 (adb에서 프로세스 재시작으로 호출)
        intent?.let { i ->
            DebugTranscriptionRequest.create(
                enabled = BuildConfig.DEBUG,
                modelPath = i.getStringExtra("auto_model"),
                audioPath = i.getStringExtra("auto_audio"),
                note = i.getStringExtra("auto_note"),
            )?.let { request ->
                AppLog.i(TAG, "자동 전사 요청 수신")
                setContent {
                    SttBenchmarkTheme {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val vm: SttViewModel = viewModel()
                            viewModel = vm
                            // 화면 표시 후 자동 실행
                            LaunchedEffect(Unit) {
                                delay(500)
                                vm.loadAndTranscribe(request.modelPath, request.audioPath, request.note)
                            }
                            TranscriptionRoute(
                                viewModel = vm,
                                onOpenSettings = {},
                            )
                        }
                    }
                }
                return
            }
        }

        setContent {
            val vm: SttViewModel = viewModel()
            val codexAuthViewModel: CodexAuthViewModel = viewModel()
            viewModel = vm
            LongSttApp(
                sttViewModel = vm,
                codexAuthViewModel = codexAuthViewModel,
                completedResultLaunchTarget = completedResultLaunchTarget,
                onCompletedResultLaunchHandled = {
                    completedResultLaunchTarget = null
                    completedResultLaunchHandled = true
                },
            )
        }
    }

    /** 이미 열린 Activity에 adb 자동 실행 Intent가 다시 전달되는 경우도 처리한다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == CompletedResultLaunchContract.ACTION_OPEN_COMPLETED_RESULT) {
            completedResultLaunchTarget = CompletedResultLaunchContract.read(intent)
                ?.let(CompletedResultTarget::fromStoredTarget)
            completedResultLaunchHandled = completedResultLaunchTarget == null
        }
        DebugTranscriptionRequest.create(
            enabled = BuildConfig.DEBUG,
            modelPath = intent.getStringExtra("auto_model"),
            audioPath = intent.getStringExtra("auto_audio"),
            note = intent.getStringExtra("auto_note"),
        )?.let { request ->
            AppLog.i(TAG, "기존 화면에서 자동 전사 재요청 수신")
            viewModel?.loadAndTranscribe(request.modelPath, request.audioPath, request.note)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (automationReceiverRegistered) {
            try { unregisterReceiver(sttReceiver) } catch (_: Exception) {}
            automationReceiverRegistered = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_COMPLETED_RESULT_LAUNCH_HANDLED, completedResultLaunchHandled)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_COMPLETED_RESULT_LAUNCH_HANDLED = "completed_result_launch_handled"
    }
}
