package com.stt.benchmark.ui.transcription

/** Debug 빌드 자동화 입력을 제품 navigation과 분리해 검증하는 최소 계약. */
data class DebugTranscriptionRequest(
    val modelPath: String,
    val audioPath: String,
    val note: String,
) {
    companion object {
        fun create(
            enabled: Boolean,
            modelPath: String?,
            audioPath: String?,
            note: String?,
        ): DebugTranscriptionRequest? {
            if (!enabled || modelPath.isNullOrBlank() || audioPath.isNullOrBlank()) return null
            return DebugTranscriptionRequest(
                modelPath = modelPath,
                audioPath = audioPath,
                note = note.orEmpty(),
            )
        }
    }
}
