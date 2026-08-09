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
import com.stt.benchmark.data.RecordingTranscriptionGroupStore
import com.stt.benchmark.data.TranscriptionSessionStore
import com.stt.benchmark.ui.theme.SttBenchmarkTheme

/**
 * Debug-only, metadata-only verification surface. It deliberately exposes neither recording
 * paths, IDs, segment text, transcript text nor an error payload; it is not packaged in Release.
 */
class DebugSttAuditActivity : ComponentActivity() {
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
                        Text("Debug STT metadata audit")
                        Text(auditSummary)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // ADB/manual debug launches can be delivered to the existing top-most Activity. Refresh
        // the metadata-only result so a new completed group is never reported as a stale audit.
        refreshAudit()
    }

    private fun refreshAudit() {
        auditSummary = auditLatestCompletedMultiChunkGroup().summary
    }

    private fun auditLatestCompletedMultiChunkGroup(): AuditResult {
        val group = RecordingTranscriptionGroupStore(this).listAll()
            .filter {
                it.status == RecordingTranscriptionGroupStore.GroupStatus.COMPLETED &&
                    it.children.size >= 2
            }
            .maxByOrNull { it.updatedAtMs }
            ?: return AuditResult("AUDIT_FAIL_NO_COMPLETED_MULTICHUNK_GROUP")
        val sessions = TranscriptionSessionStore(this)
        val coverageComplete = group.children.all { child ->
            val checkpoint = sessions.load(child.sttSessionId) ?: return@all false
            checkpoint.status == TranscriptionSessionStore.Status.COMPLETED &&
                checkpoint.chunks.size == checkpoint.totalChunks &&
                coverageIsContinuous(checkpoint)
        }
        return if (coverageComplete) {
            AuditResult("AUDIT_PASS_CHILDREN=${group.children.size}_COVERAGE=COMPLETE")
        } else {
            AuditResult("AUDIT_FAIL_CHILD_COVERAGE")
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

    private data class AuditResult(val summary: String)

    private companion object {
        const val COVERAGE_TOLERANCE_MS = 50L
    }
}
