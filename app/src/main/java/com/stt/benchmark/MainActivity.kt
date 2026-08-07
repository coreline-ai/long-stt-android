package com.stt.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.ui.SttBenchmarkScreen
import com.stt.benchmark.ui.SttViewModel
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private var viewModel: SttViewModel? = null

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

        // broadcast 등록
        ContextCompat.registerReceiver(
            this,
            sttReceiver,
            IntentFilter("com.stt.benchmark.RUN_STT"),
            ContextCompat.RECEIVER_EXPORTED
        )

        // Intent extra로 자동 전사 모드 (adb에서 프로세스 재시작으로 호출)
        intent?.let { i ->
            val autoModel = i.getStringExtra("auto_model")
            val autoAudio = i.getStringExtra("auto_audio")
            val autoNote = i.getStringExtra("auto_note") ?: ""
            if (!autoModel.isNullOrEmpty() && !autoAudio.isNullOrEmpty()) {
                Log.i(TAG, "자동 전사 모드: $autoNote")
                setContent {
                    SttBenchmarkTheme {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            val vm: SttViewModel = viewModel()
                            viewModel = vm
                            // 화면 표시 후 자동 실행
                            LaunchedEffect(Unit) {
                                delay(500)
                                vm.loadAndTranscribe(autoModel, autoAudio, autoNote)
                            }
                            SttBenchmarkScreen(viewModel = vm)
                        }
                    }
                }
                return
            }
        }

        setContent {
            SttBenchmarkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val vm: SttViewModel = viewModel()
                    viewModel = vm
                    SttBenchmarkScreen(viewModel = vm)
                }
            }
        }
    }

    /** 이미 열린 Activity에 adb 자동 실행 Intent가 다시 전달되는 경우도 처리한다. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val autoModel = intent.getStringExtra("auto_model")
        val autoAudio = intent.getStringExtra("auto_audio")
        val autoNote = intent.getStringExtra("auto_note") ?: ""
        if (!autoModel.isNullOrEmpty() && !autoAudio.isNullOrEmpty()) {
            Log.i(TAG, "기존 화면에서 자동 전사 재요청: $autoNote")
            viewModel?.loadAndTranscribe(autoModel, autoAudio, autoNote)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(sttReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
