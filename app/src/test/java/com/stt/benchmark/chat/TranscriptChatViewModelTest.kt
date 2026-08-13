package com.stt.benchmark.chat

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.data.TranscriptSourceSection
import com.stt.benchmark.data.TranscriptSourceType
import com.stt.benchmark.summary.CodexLlmHttpException
import java.nio.file.Files
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptChatViewModelTest {
    @Test
    fun openSourceNeverPostsUntilConsentThenStreamsAndPersistsCompletedAnswer() = runBlocking {
        val fixture = fixture(FakeLlm(responses = ArrayDeque(listOf("출시 일정 인덱스", "다음 주입니다 [U0001]"))))

        fixture.viewModel.openSource(fixture.document)
        assertEquals(TranscriptChatPhase.CONSENT_REQUIRED, fixture.viewModel.uiState.value.phase)
        assertEquals(0, fixture.llm.requests.size)

        fixture.viewModel.confirmIndexingConsent()
        waitUntil { fixture.viewModel.uiState.value.indexReady }
        assertEquals(1, fixture.llm.requests.size)

        fixture.viewModel.updateDraft("출시 일정은 언제인가요?")
        fixture.viewModel.ask()
        waitUntil { fixture.viewModel.uiState.value.phase == TranscriptChatPhase.COMPLETED }

        assertEquals(2, fixture.llm.requests.size)
        assertEquals(2, fixture.viewModel.uiState.value.messages.size)
        assertEquals(listOf("U0001"), fixture.viewModel.uiState.value.currentCitations.map { it.unitId })
        assertEquals(2, fixture.sessionStore.read(fixture.document.source)?.messages?.size)
    }

    @Test
    fun cancellationClearsPartialAnswerAndNeverPersistsItOrAutoRestarts() = runBlocking {
        val llm = FakeLlm(responses = ArrayDeque(listOf("ignored")), suspendAfterDelta = true)
        val fixture = fixture(llm)
        fixture.seedCompleteIndex("관련 인덱스")
        fixture.viewModel.openSource(fixture.document)

        fixture.viewModel.updateDraft("관련 질문")
        fixture.viewModel.ask()
        waitUntil { fixture.viewModel.uiState.value.currentAnswer.isNotBlank() }
        fixture.viewModel.stop()
        delay(50)

        assertEquals(TranscriptChatPhase.CANCELLED, fixture.viewModel.uiState.value.phase)
        assertEquals("", fixture.viewModel.uiState.value.currentAnswer)
        assertNull(fixture.sessionStore.read(fixture.document.source))
        val requestCount = llm.requests.size
        delay(100)
        assertEquals(requestCount, llm.requests.size)
    }

    @Test
    fun providerErrorsAreRedactedAndRetryRunsOnlyAfterUserAction() = runBlocking {
        val llm = FakeLlm(failure = CodexLlmHttpException(429))
        val fixture = fixture(llm)
        fixture.seedCompleteIndex("관련 인덱스")
        fixture.viewModel.openSource(fixture.document)
        fixture.viewModel.updateDraft("관련 질문")
        fixture.viewModel.ask()
        waitUntil { fixture.viewModel.uiState.value.phase == TranscriptChatPhase.ERROR }

        assertTrue(fixture.viewModel.uiState.value.statusMessage.contains("요청이 많습니다"))
        assertFalse(fixture.viewModel.uiState.value.statusMessage.contains("429"))
        assertEquals(1, llm.requests.size)
        delay(100)
        assertEquals(1, llm.requests.size)

        fixture.viewModel.retry()
        waitUntil { llm.requests.size == 2 }
    }

    @Test
    fun processRecreationRestoresCompletedStateWithoutPosting() = runBlocking {
        val first = fixture(FakeLlm())
        first.seedCompleteIndex("인덱스")
        first.sessionStore.save(
            TranscriptChatSessionStore.Entry(
                source = first.document.source,
                sourceFingerprint = TranscriptChatPolicy.fingerprint(first.document),
                messages = listOf(
                    TranscriptChatSessionStore.Message(
                        TranscriptChatSessionStore.Role.ASSISTANT,
                        "복원 답변 [U0001]",
                        listOf("U0001"),
                        1L,
                    ),
                ),
                updatedAtMs = 1L,
            ),
        )
        val recreatedLlm = FakeLlm()
        val recreated = first.recreate(recreatedLlm)

        recreated.openSource(first.document)

        assertEquals(TranscriptChatPhase.COMPLETED, recreated.uiState.value.phase)
        assertEquals("복원 답변 [U0001]", recreated.uiState.value.messages.single().text)
        assertEquals(0, recreatedLlm.requests.size)
    }

    @Test
    fun preciseResumeReusesCompletedUnitAndProgressEndsAtHundred() = runBlocking {
        val document = document(
            listOf(
                section("s1", 0, 10_000, "출시 일정 ".repeat(1_000)),
                section("s2", 10_000, 20_000, "후속 조치 ".repeat(1_000)),
            ),
        )
        val llm = FakeLlm(responses = ArrayDeque(listOf("[U0002] 조치 발견", "최종 답변 [U0001] [U0002]")))
        val fixture = fixture(llm, document)
        fixture.seedCompleteIndex("일정", "조치")
        fixture.preciseStore.save(
            TranscriptPreciseSearchStore.Entry(
                source = document.source,
                sourceFingerprint = TranscriptChatPolicy.fingerprint(document),
                question = "무엇을 결정했나요?",
                findings = listOf(TranscriptPreciseSearchStore.Finding("U0001", "[U0001] 일정 발견")),
                totalUnits = 2,
                updatedAtMs = 1L,
            ),
        )
        fixture.viewModel.openSource(document)
        fixture.viewModel.selectMode(TranscriptChatMode.PRECISE)
        fixture.viewModel.updateDraft("무엇을 결정했나요?")
        fixture.viewModel.ask()
        waitUntil { fixture.viewModel.uiState.value.phase == TranscriptChatPhase.COMPLETED }

        assertEquals(2, llm.requests.size)
        assertEquals(100, fixture.viewModel.uiState.value.progressPercent)
        assertNull(fixture.preciseStore.read(document.source))
    }

    @Test
    fun oldHistoryIsDigestedInsideTheSameUserStartedLeaseBeforeAnswering() = runBlocking {
        val llm = FakeLlm(responses = ArrayDeque(listOf("이전 대화 요약", "최종 답변 [U0001]")))
        val fixture = fixture(llm)
        fixture.seedCompleteIndex("관련 질문 인덱스")
        val messages = (1..8).map { index ->
            TranscriptChatSessionStore.Message(
                TranscriptChatSessionStore.Role.USER,
                "관련 질문 $index " + "가".repeat(1_980),
                timestampMs = index.toLong(),
            )
        }
        fixture.sessionStore.save(
            TranscriptChatSessionStore.Entry(
                source = fixture.document.source,
                sourceFingerprint = TranscriptChatPolicy.fingerprint(fixture.document),
                messages = messages,
                updatedAtMs = 8L,
            ),
        )
        fixture.viewModel.openSource(fixture.document)

        fixture.viewModel.ask("관련 질문")
        waitUntil { fixture.viewModel.uiState.value.phase == TranscriptChatPhase.COMPLETED }

        assertEquals(2, llm.requests.size)
        val stored = requireNotNull(fixture.sessionStore.read(fixture.document.source))
        assertEquals("이전 대화 요약", stored.historyDigest)
        assertTrue(stored.historyDigestThrough > 0)
    }

    @Test
    fun providerAndTimeoutErrorsNeverExposeRawDetails() {
        val service = TranscriptChatViewModel.safeErrorMessage(CodexLlmHttpException(503))
        val timeout = TranscriptChatViewModel.safeErrorMessage(SocketTimeoutException("private-host"))
        val malformed = TranscriptChatViewModel.safeErrorMessage(IllegalStateException("raw provider payload"))

        assertTrue(service.contains("자동 재시도하지 않았습니다"))
        assertFalse(service.contains("503"))
        assertFalse(timeout.contains("private-host"))
        assertFalse(malformed.contains("raw provider payload"))
    }

    @Test
    fun signedOutSourceShowsConnectionRequirementWithoutConsentOrPost() {
        val llm = FakeLlm(authenticated = false)
        val fixture = fixture(llm)

        fixture.viewModel.openSource(fixture.document)

        assertEquals(TranscriptChatPhase.ERROR, fixture.viewModel.uiState.value.phase)
        assertTrue(fixture.viewModel.uiState.value.statusMessage.contains("ChatGPT 연결"))
        assertEquals(0, llm.requests.size)
    }

    private data class Fixture(
        val application: Application,
        val document: TranscriptSourceDocument,
        val llm: FakeLlm,
        val indexStore: TranscriptChatIndexStore,
        val sessionStore: TranscriptChatSessionStore,
        val preciseStore: TranscriptPreciseSearchStore,
        val viewModel: TranscriptChatViewModel,
    ) {
        fun seedCompleteIndex(vararg summaries: String) {
            val units = TranscriptChatUnitPlanner.plan(document)
            require(summaries.size == units.size)
            indexStore.save(
                TranscriptChatIndexStore.Entry(
                    source = document.source,
                    sourceFingerprint = TranscriptChatPolicy.fingerprint(document),
                    units = units.mapIndexed { index, unit ->
                        TranscriptChatIndexStore.UnitEntry(unit.unitId, unit.startMs, unit.endMs, summaries[index])
                    },
                    isComplete = true,
                    updatedAtMs = 1L,
                ),
            )
        }

        fun recreate(client: FakeLlm) = TranscriptChatViewModel(
            application, client, indexStore, sessionStore, preciseStore, testOnly = true,
        )
    }

    private fun fixture(llm: FakeLlm, document: TranscriptSourceDocument = document(listOf(section("s1", 0, 1_000, "출시 일정은 다음 주입니다.")))): Fixture {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val root = Files.createTempDirectory("chat-vm").toFile()
        val indexStore = TranscriptChatIndexStore(root.resolve("index"))
        val sessionStore = TranscriptChatSessionStore(root.resolve("session"))
        val preciseStore = TranscriptPreciseSearchStore(root.resolve("precise"))
        val viewModel = TranscriptChatViewModel(
            application, llm, indexStore, sessionStore, preciseStore, testOnly = true,
        )
        return Fixture(application, document, llm, indexStore, sessionStore, preciseStore, viewModel)
    }

    private class FakeLlm(
        private val responses: ArrayDeque<String> = ArrayDeque(),
        private val failure: Exception? = null,
        private val suspendAfterDelta: Boolean = false,
        private val authenticated: Boolean = true,
    ) : TranscriptChatLlmClient {
        val requests = mutableListOf<String>()
        override fun isAuthenticated() = authenticated

        override suspend fun stream(requestJson: String, maxChars: Int, onDelta: (String) -> Unit): String {
            requests += requestJson
            failure?.let { throw it }
            if (suspendAfterDelta) {
                onDelta("부분")
                delay(10_000)
            }
            val response = if (responses.isEmpty()) "완료" else responses.removeFirst()
            onDelta(response)
            return response.take(maxChars)
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 3_000, predicate: () -> Boolean) {
        val started = System.currentTimeMillis()
        while (!predicate()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() - started < timeoutMs) { "timed out" }
            delay(10)
        }
    }

    private companion object {
        fun section(key: String, startMs: Long, endMs: Long, text: String) =
            TranscriptSourceSection(key, key, startMs, endMs, text)

        fun document(sections: List<TranscriptSourceSection>) = TranscriptSourceDocument(
            TranscriptSourceRef(TranscriptSourceType.TRANSCRIPTION_SESSION, "stt_vm"),
            updatedAtMs = 1L,
            sections = sections,
        )
    }
}
