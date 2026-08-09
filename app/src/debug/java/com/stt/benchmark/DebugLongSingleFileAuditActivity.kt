package com.stt.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stt.benchmark.data.MediaLibraryStore
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.ui.theme.SttBenchmarkTheme

/**
 * Debug-only readiness check for the required legacy single-file long-STT regression.
 * It never exposes a filename, URI/path, ID, duration, size, audio content or transcript.
 */
class DebugLongSingleFileAuditActivity : ComponentActivity() {
    private var auditSummary by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshAudit()
        setContent {
            SttBenchmarkTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Debug long single-file readiness audit")
                        Text(auditSummary)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshAudit()
    }

    private fun refreshAudit() {
        val imported = MediaLibraryStore(this).listAudios()
            .filter { it.source == MediaLibraryStore.AudioSource.IMPORTED }
        val candidates = imported.filter { it.durationMs >= MINIMUM_LEGACY_DURATION_MS }
        val candidatePaths = candidates.mapTo(mutableSetOf()) { it.path }
        val completed = TranscriptionSessionStore(this).listAll()
            .filter { checkpoint -> checkpoint.audioPath in candidatePaths }
            .filter { it.status == TranscriptionSessionStore.Status.COMPLETED }
            .filter { it.chunks.size == it.totalChunks && coverageIsContinuous(it) }
            .maxByOrNull { it.updatedAtMs }
        auditSummary = when {
            completed != null -> {
                "LEGACY_LONG_SINGLE_STT_AUDIT_PASS_CHUNKS=${completed.totalChunks}_COVERAGE=COMPLETE"
            }
            candidates.isNotEmpty() -> {
                "LEGACY_LONG_SINGLE_STT_CANDIDATE_READY_COUNT=${candidates.size}"
            }
            imported.any { it.durationMs <= 0L } -> {
                // Do not inspect an unknown-length file in this audit: that could add metadata or
                // trigger decoder diagnostics. Product import/selection resolves its duration first.
                "LEGACY_LONG_SINGLE_STT_DURATION_METADATA_UNRESOLVED"
            }
            else -> "LEGACY_LONG_SINGLE_STT_CANDIDATE_NONE"
        }
    }

    private fun coverageIsContinuous(checkpoint: TranscriptionSessionStore.Checkpoint): Boolean {
        var cursor = 0L
        checkpoint.chunks.sortedBy { it.index }.forEach { chunk ->
            if (chunk.decodedStartMs > cursor + COVERAGE_TOLERANCE_MS ||
                chunk.decodedEndMs < chunk.primaryEndMs - COVERAGE_TOLERANCE_MS
            ) return false
            cursor = maxOf(cursor, chunk.decodedEndMs)
        }
        return cursor >= checkpoint.durationMs - COVERAGE_TOLERANCE_MS
    }

    private companion object {
        const val MINIMUM_LEGACY_DURATION_MS = 6L * 60L * 60L * 1_000L
        const val COVERAGE_TOLERANCE_MS = 50L
    }
}
