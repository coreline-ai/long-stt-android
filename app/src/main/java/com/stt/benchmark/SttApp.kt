package com.stt.benchmark

import android.app.Application
import android.util.Log

/**
 * 애플리케이션 진입점.
 * 네이티브 라이브러리 로드는 WhisperEngine 초기화 시점에 수행 (지연 로딩).
 */
class SttApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "STT Benchmark 앱 시작 - ${BuildConfig.VERSION_NAME}")
    }

    companion object {
        private const val TAG = "SttApp"
    }
}
