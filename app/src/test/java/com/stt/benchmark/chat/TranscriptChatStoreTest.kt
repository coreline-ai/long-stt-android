package com.stt.benchmark.chat

import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceType
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptChatStoreTest {
    private val source = TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_store_1")
    private val fingerprint = "a".repeat(64)

    @Test
    fun indexRoundTripContainsOnlyAllowlistedDerivedFields() {
        val root = Files.createTempDirectory("chat-index").toFile()
        val store = TranscriptChatIndexStore(root)
        val entry = TranscriptChatIndexStore.Entry(
            source = source,
            sourceFingerprint = fingerprint,
            units = listOf(TranscriptChatIndexStore.UnitEntry("U0001", 0L, 1_000L, "파생 요약")),
            isComplete = true,
            updatedAtMs = 10L,
        )

        store.save(entry)

        assertEquals(entry, store.read(source))
        val raw = root.listFiles().single().readText()
        listOf("transcript", "segment", "audioPath", "modelPath", "token", "account", "note", "sessionId")
            .forEach { forbidden -> assertFalse(raw.contains("\"$forbidden\"", ignoreCase = true)) }
    }

    @Test
    fun sessionRoundTripStoresCompletedDisplayMessagesAndValidatedCitations() {
        val root = Files.createTempDirectory("chat-session").toFile()
        val store = TranscriptChatSessionStore(root)
        val entry = TranscriptChatSessionStore.Entry(
            source = source,
            sourceFingerprint = fingerprint,
            messages = listOf(
                TranscriptChatSessionStore.Message(TranscriptChatSessionStore.Role.USER, "질문", timestampMs = 1L),
                TranscriptChatSessionStore.Message(
                    TranscriptChatSessionStore.Role.ASSISTANT,
                    "답변 [U0001]",
                    listOf("U0001"),
                    2L,
                ),
            ),
            historyDigest = "이전 결정 요약",
            historyDigestThrough = 1,
            updatedAtMs = 2L,
        )

        store.save(entry)

        assertEquals(entry, store.read(source))
        val raw = root.listFiles().single().readText()
        listOf("partial", "oauth", "audioPath", "modelPath", "provider", "rawResponse")
            .forEach { forbidden -> assertFalse(raw.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun preciseCheckpointRoundTripStoresOnlyDerivedFindings() {
        val root = Files.createTempDirectory("chat-precise").toFile()
        val store = TranscriptPreciseSearchStore(root)
        val entry = TranscriptPreciseSearchStore.Entry(
            source = source,
            sourceFingerprint = fingerprint,
            question = "결정은?",
            findings = listOf(TranscriptPreciseSearchStore.Finding("U0001", "결정 발견")),
            totalUnits = 2,
            updatedAtMs = 3L,
        )

        store.save(entry)

        assertEquals(entry, store.read(source))
        assertFalse(root.listFiles().single().readText().contains("\"transcript\"", ignoreCase = true))
    }

    @Test
    fun corruptedAndOldSchemaFilesFailClosed() {
        val root = Files.createTempDirectory("chat-corrupt").toFile()
        val store = TranscriptChatIndexStore(root)
        File(root, "transcription_session_${source.id}.json").writeText("not-json")
        assertNull(store.read(source))

        store.save(
            TranscriptChatIndexStore.Entry(
                source = source,
                sourceFingerprint = fingerprint,
                units = listOf(TranscriptChatIndexStore.UnitEntry("U0001", 0L, 1L, "요약")),
                isComplete = true,
                updatedAtMs = 1L,
            ),
        )
        val raw = root.listFiles().single().readText().replace("\"schemaVersion\":1", "\"schemaVersion\":0")
        root.listFiles().single().writeText(raw)
        assertNull(store.read(source))
    }

    @Test
    fun reuseRequiresFingerprintAndAllVersions() {
        val index = TranscriptChatIndexStore.Entry(
            source,
            fingerprint,
            units = listOf(TranscriptChatIndexStore.UnitEntry("U0001", 0, 1, "요약")),
            isComplete = true,
            updatedAtMs = 1,
        )

        assertTrue(index.isReusable(fingerprint))
        assertFalse(index.isReusable("b".repeat(64)))
        assertFalse(index.copy(promptVersion = "old").isReusable(fingerprint))
        assertFalse(index.copy(modelVersion = "old").isReusable(fingerprint))
    }
}
