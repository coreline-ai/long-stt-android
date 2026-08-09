package com.stt.benchmark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stt.benchmark.ui.transcription.TranscriptionRoute

/**
 * 이전 자동화/소스 호출을 위한 얇은 호환 진입점.
 * 제품 navigation은 `TranscriptionRoute`를 직접 사용한다.
 */
@Deprecated("Use TranscriptionRoute")
@Composable
fun SttBenchmarkScreen(
    modifier: Modifier = Modifier,
    viewModel: SttViewModel = viewModel(),
    @Suppress("UNUSED_PARAMETER") showOverview: Boolean = false,
    @Suppress("UNUSED_PARAMETER") showResultLibrary: Boolean = false,
) {
    TranscriptionRoute(
        viewModel = viewModel,
        onOpenSettings = {},
        modifier = modifier,
    )
}
