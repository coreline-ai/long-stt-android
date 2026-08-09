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
import com.stt.benchmark.recording.RecordingPhase
import com.stt.benchmark.recording.RecordingSessionStore
import com.stt.benchmark.ui.theme.SttBenchmarkTheme
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Debug-only aggregate validation for the Phase 7 long recording run. */
class DebugRecordingAuditActivity : ComponentActivity() {
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
                        Text("Debug recording metadata audit")
                        Text(auditSummary)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Samsung can deliver a new launch to the already top-most debug Activity. Refresh the
        // aggregate result so a later long-run audit cannot render a stale completed session.
        refreshAudit()
    }

    private fun refreshAudit() {
        auditSummary = auditLatestSavedLongSession().summary
    }

    private fun auditLatestSavedLongSession(): AuditResult {
        val session = RecordingSessionStore(this).listAll()
            .filter { it.phase == RecordingPhase.SAVED && it.chunks.size >= MINIMUM_LONG_RUN_CHUNKS }
            .maxByOrNull { it.updatedAtMs }
            ?: return AuditResult("RECORDING_AUDIT_WAITING_FOR_MULTICHUNK_SAVED_SESSION")

        val chunks = session.chunks.sortedBy { it.index }
        val sequencesAreContinuous = chunks.map { it.index } == (0..chunks.last().index).toList()
        val chunkFilesHaveFinalMetadata = chunks.all { chunk ->
            chunk.status == RecordingSessionStore.ChunkStatus.READY &&
                chunk.sizeBytes > 0L &&
                chunk.durationMs > 0L &&
                chunk.sha256.matches(SHA256_REGEX) &&
                File(chunk.finalPath).isFile &&
                File(chunk.finalPath).length() == chunk.sizeBytes
        }
        val chunkHashesMatch = chunkFilesHaveFinalMetadata && chunks.all { chunk ->
            sha256(File(chunk.finalPath)).equals(chunk.sha256, ignoreCase = true)
        }
        val mediaBySequence = MediaLibraryStore(this).listAudios()
            .filter {
                it.source == MediaLibraryStore.AudioSource.RECORDED &&
                    it.recordingSessionId == session.sessionId
            }
            .associateBy { it.sequence }
        val mediaSequencesAreComplete = mediaBySequence.size == chunks.size &&
            chunks.all { it.index in mediaBySequence }
        val mediaMetadataMatches = mediaSequencesAreComplete && chunks.all { chunk ->
            val media = mediaBySequence[chunk.index] ?: return@all false
            media.sizeBytes == chunk.sizeBytes &&
                media.durationMs == chunk.durationMs &&
                media.sha256.equals(chunk.sha256, ignoreCase = true)
        }
        val elapsedMs = session.stoppedAtMs - session.startedAtMs
        val durationDeltaMs = abs(chunks.sumOf { it.durationMs } - elapsedMs)
        val durationIsConsistent = elapsedMs > 0L &&
            durationDeltaMs <= chunks.size * MAX_DURATION_DELTA_PER_CHUNK_MS

        val failure = when {
            !sequencesAreContinuous -> "SEQUENCE"
            !chunkFilesHaveFinalMetadata -> "FINAL_METADATA"
            !chunkHashesMatch -> "FINAL_HASH"
            !mediaSequencesAreComplete -> "MEDIA_SEQUENCE_EXPECTED=${chunks.size}_REGISTERED=${mediaBySequence.size}"
            !mediaMetadataMatches -> "MEDIA_METADATA"
            !durationIsConsistent -> "DURATION"
            else -> null
        }
        return if (failure == null) {
            AuditResult("RECORDING_AUDIT_PASS_CHUNKS=${chunks.size}_MEDIA=COMPLETE_DURATION=CONSISTENT")
        } else {
            // Category only: never disclose a path, ID, hash, duration or audio payload.
            AuditResult("RECORDING_AUDIT_FAIL_$failure")
        }
    }

    private data class AuditResult(val summary: String)

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MINIMUM_LONG_RUN_CHUNKS = 3
        const val MAX_DURATION_DELTA_PER_CHUNK_MS = 1_500L
        val SHA256_REGEX = Regex("[a-fA-F0-9]{64}")
    }
}
