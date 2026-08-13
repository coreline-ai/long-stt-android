package com.stt.benchmark.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stt.benchmark.core.DeviceWorkCoordinator
import com.stt.benchmark.core.DeviceWorkRuntime
import com.stt.benchmark.data.TranscriptSourceDocument
import com.stt.benchmark.data.TranscriptSourceRef
import com.stt.benchmark.summary.CodexLlmHttpException
import com.stt.benchmark.summary.CodexLlmRequestCoordinator
import com.stt.benchmark.summary.CodexLlmRequestRuntime
import com.stt.benchmark.summary.CodexSummaryAuthController
import dev.alpine.llm.OAuthException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TranscriptChatPhase {
    IDLE,
    CONSENT_REQUIRED,
    INDEXING,
    SEARCHING,
    ANSWERING,
    COMPLETED,
    CANCELLED,
    ERROR,
}

enum class TranscriptChatMode { QUICK, PRECISE }

data class TranscriptChatUiState(
    val source: TranscriptSourceRef? = null,
    val sourceFingerprint: String = "",
    val phase: TranscriptChatPhase = TranscriptChatPhase.IDLE,
    val mode: TranscriptChatMode = TranscriptChatMode.QUICK,
    val isAuthenticated: Boolean = false,
    val messages: List<TranscriptChatSessionStore.Message> = emptyList(),
    val currentAnswer: String = "",
    val currentCitations: List<TranscriptCitation> = emptyList(),
    val citationCatalog: List<TranscriptCitation> = emptyList(),
    val draftQuestion: String = "",
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
    val stageLabel: String = "대기",
    val statusMessage: String = "완료 전사를 선택하면 대화를 시작할 수 있습니다.",
    val indexReady: Boolean = false,
    val canRetry: Boolean = false,
    val preciseAvailable: Boolean = false,
) {
    val progressPercent: Int
        get() = TranscriptPreciseSearchPlanner.progressPercent(completedSteps, totalSteps)
}

class TranscriptChatViewModel private constructor(
    application: Application,
    private val llm: TranscriptChatLlmClient,
    private val indexStore: TranscriptChatIndexStore,
    private val sessionStore: TranscriptChatSessionStore,
    private val preciseStore: TranscriptPreciseSearchStore,
    private val llmCoordinator: CodexLlmRequestCoordinator,
    private val deviceCoordinator: DeviceWorkCoordinator,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        llm = CodexTranscriptChatLlmClient(CodexSummaryAuthController(application)),
        indexStore = TranscriptChatIndexStore(application),
        sessionStore = TranscriptChatSessionStore(application),
        preciseStore = TranscriptPreciseSearchStore(application),
        llmCoordinator = CodexLlmRequestRuntime.coordinator,
        deviceCoordinator = DeviceWorkRuntime.coordinator,
    )

    internal constructor(
        application: Application,
        llm: TranscriptChatLlmClient,
        indexStore: TranscriptChatIndexStore,
        sessionStore: TranscriptChatSessionStore,
        preciseStore: TranscriptPreciseSearchStore,
        llmCoordinator: CodexLlmRequestCoordinator = CodexLlmRequestCoordinator(),
        deviceCoordinator: DeviceWorkCoordinator = DeviceWorkCoordinator(),
        @Suppress("UNUSED_PARAMETER") testOnly: Boolean = true,
    ) : this(application, llm, indexStore, sessionStore, preciseStore, llmCoordinator, deviceCoordinator)

    private val _uiState = MutableStateFlow(TranscriptChatUiState())
    val uiState: StateFlow<TranscriptChatUiState> = _uiState.asStateFlow()

    private var document: TranscriptSourceDocument? = null
    private var plannedUnits: List<TranscriptChatPlannedUnit> = emptyList()
    private var activeIndex: TranscriptChatIndexStore.Entry? = null
    private var activeSession: TranscriptChatSessionStore.Entry? = null
    private var activeJob: Job? = null
    private var retryAction: RetryAction? = null

    fun openSource(sourceDocument: TranscriptSourceDocument?) {
        if (activeJob?.isActive == true) return
        when (val validation = TranscriptChatPolicy.validate(sourceDocument)) {
            is TranscriptChatPolicy.SourceValidation.Rejected -> {
                document = null
                plannedUnits = emptyList()
                activeIndex = null
                activeSession = null
                _uiState.value = TranscriptChatUiState(
                    phase = TranscriptChatPhase.ERROR,
                    isAuthenticated = llm.isAuthenticated(),
                    statusMessage = validation.message,
                )
            }
            is TranscriptChatPolicy.SourceValidation.Ready -> restore(validation)
        }
    }

    fun refreshAuthentication() {
        _uiState.update { it.copy(isAuthenticated = llm.isAuthenticated()) }
    }

    fun confirmIndexingConsent() {
        val currentDocument = document ?: return
        if (activeJob?.isActive == true) return
        if (!llm.isAuthenticated()) {
            _uiState.update {
                it.copy(
                    isAuthenticated = false,
                    phase = TranscriptChatPhase.ERROR,
                    statusMessage = "설정에서 ChatGPT 연결을 완료한 뒤 다시 시도하세요.",
                )
            }
            return
        }
        retryAction = RetryAction.Index
        activeJob = viewModelScope.launch { runIndexing(currentDocument) }
            .also(::clearWhenCompleted)
    }

    fun updateDraft(value: String) {
        _uiState.update { it.copy(draftQuestion = value.take(TranscriptChatPolicy.MAX_QUESTION_CHARS)) }
    }

    fun selectMode(mode: TranscriptChatMode) {
        if (activeJob?.isActive == true) return
        _uiState.update { it.copy(mode = mode) }
    }

    fun ask(question: String = _uiState.value.draftQuestion) {
        val selected = question.trim()
        if (selected.isEmpty() || selected.length > TranscriptChatPolicy.MAX_QUESTION_CHARS) return
        if (_uiState.value.mode == TranscriptChatMode.PRECISE) {
            startPreciseSearch(selected)
        } else {
            startQuickAnswer(selected)
        }
    }

    fun stop() {
        if (activeJob?.isActive != true) return
        activeJob?.cancel()
        activeJob = null
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.CANCELLED,
                currentAnswer = "",
                stageLabel = "취소됨",
                statusMessage = "작업을 중지했습니다. 자동으로 다시 시작하지 않습니다.",
                canRetry = retryAction != null,
            )
        }
    }

    fun retry() {
        if (activeJob?.isActive == true) return
        when (val action = retryAction) {
            RetryAction.Index -> confirmIndexingConsent()
            is RetryAction.Quick -> startQuickAnswer(action.question)
            is RetryAction.Precise -> startPreciseSearch(action.question)
            null -> Unit
        }
    }

    fun newConversation() {
        if (activeJob?.isActive == true) return
        val source = _uiState.value.source ?: return
        sessionStore.delete(source)
        activeSession = null
        retryAction = null
        _uiState.update {
            it.copy(
                phase = if (it.indexReady) TranscriptChatPhase.IDLE else TranscriptChatPhase.CONSENT_REQUIRED,
                messages = emptyList(),
                currentAnswer = "",
                currentCitations = emptyList(),
                draftQuestion = "",
                canRetry = false,
                statusMessage = if (it.indexReady) "새 대화를 시작했습니다." else "전사 구간 인덱싱 동의가 필요합니다.",
            )
        }
    }

    fun deleteConversation() = newConversation()

    private fun restore(validation: TranscriptChatPolicy.SourceValidation.Ready) {
        val sourceChanged = document?.source != validation.document.source ||
            _uiState.value.sourceFingerprint != validation.fingerprint
        document = validation.document
        plannedUnits = TranscriptChatUnitPlanner.plan(validation.document)
        val storedIndex = indexStore.read(validation.document.source)
            ?.takeIf { it.isReusable(validation.fingerprint) }
        activeIndex = storedIndex
        val storedSession = sessionStore.read(validation.document.source)
            ?.takeIf { it.isReusable(validation.fingerprint) }
        activeSession = storedSession
        val authenticated = llm.isAuthenticated()
        val ready = storedIndex?.isComplete == true && storedIndex.units.size == plannedUnits.size
        val lastStoredCitations = storedSession?.messages.orEmpty().asReversed()
            .firstOrNull { it.role == TranscriptChatSessionStore.Role.ASSISTANT }
            ?.citationUnitIds.orEmpty()
        val restoredCitations = plannedUnits.filter { it.unitId in lastStoredCitations }
            .map { TranscriptCitation(it.unitId, it.startMs, it.endMs, it.sourceSectionKeys.first()) }
        _uiState.value = TranscriptChatUiState(
            source = validation.document.source,
            sourceFingerprint = validation.fingerprint,
            phase = when {
                !authenticated -> TranscriptChatPhase.ERROR
                !ready -> TranscriptChatPhase.CONSENT_REQUIRED
                storedSession?.messages?.isNotEmpty() == true -> TranscriptChatPhase.COMPLETED
                else -> TranscriptChatPhase.IDLE
            },
            isAuthenticated = authenticated,
            messages = storedSession?.messages.orEmpty(),
            currentCitations = restoredCitations,
            citationCatalog = plannedUnits.map {
                TranscriptCitation(it.unitId, it.startMs, it.endMs, it.sourceSectionKeys.first())
            },
            stageLabel = when {
                !authenticated -> "연결 필요"
                ready -> "준비 완료"
                else -> "인덱싱 동의 필요"
            },
            statusMessage = when {
                !authenticated -> "설정에서 ChatGPT 연결을 완료한 뒤 다시 시도하세요."
                sourceChanged && storedIndex != null && !ready -> "전사가 변경되어 인덱스를 다시 만들어야 합니다."
                ready -> "전사 근거 인덱스를 사용할 수 있습니다."
                else -> "전사 구간을 외부 LLM으로 보내 인덱스를 만들기 전에 동의가 필요합니다."
            },
            indexReady = ready,
            preciseAvailable = ready,
        )
    }

    private suspend fun runIndexing(sourceDocument: TranscriptSourceDocument) {
        val fingerprint = TranscriptChatPolicy.fingerprint(sourceDocument)
        val existing = indexStore.read(sourceDocument.source)
            ?.takeIf { it.isReusable(fingerprint) }
        val completed = existing?.units.orEmpty().associateBy(TranscriptChatIndexStore.UnitEntry::unitId).toMutableMap()
        val remaining = plannedUnits.filterNot { completed.containsKey(it.unitId) }
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.INDEXING,
                completedSteps = completed.size,
                totalSteps = plannedUnits.size,
                stageLabel = "구간 인덱싱",
                statusMessage = "전사 원문을 저장하지 않고 구간별 파생 요약을 만들고 있습니다.",
                currentAnswer = "",
                canRetry = false,
            )
        }
        try {
            withChatLeases("chat_index_${sourceDocument.source.id}") {
                for (unit in remaining) {
                    val summary = llm.stream(
                        TranscriptChatProfile.indexRequest(unit),
                        TranscriptChatPolicy.MAX_UNIT_SUMMARY_CHARS,
                    ) { }
                    completed[unit.unitId] = TranscriptChatIndexStore.UnitEntry(
                        unitId = unit.unitId,
                        startMs = unit.startMs,
                        endMs = unit.endMs,
                        summary = summary,
                    )
                    indexStore.save(
                        TranscriptChatIndexStore.Entry(
                            source = sourceDocument.source,
                            sourceFingerprint = fingerprint,
                            units = completed.values.sortedBy { it.unitId },
                            isComplete = completed.size == plannedUnits.size,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    _uiState.update { state -> state.copy(completedSteps = completed.size) }
                }
            }
            _uiState.update {
                it.copy(
                    stageLabel = "저장 중",
                    statusMessage = "원문 없이 완료된 파생 인덱스를 안전하게 저장하고 있습니다.",
                )
            }
            activeIndex = indexStore.read(sourceDocument.source)?.takeIf { it.isReusable(fingerprint) }
            retryAction = null
            _uiState.update {
                it.copy(
                    phase = TranscriptChatPhase.IDLE,
                    completedSteps = plannedUnits.size,
                    totalSteps = plannedUnits.size,
                    stageLabel = "완료",
                    statusMessage = "전사 근거 인덱싱을 완료했습니다.",
                    indexReady = true,
                    preciseAvailable = true,
                    canRetry = false,
                )
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            fail(error, "인덱싱을 완료하지 못했습니다.")
        }
    }

    private fun startQuickAnswer(question: String) {
        if (activeJob?.isActive == true || !ensureReady()) return
        retryAction = RetryAction.Quick(question)
        activeJob = viewModelScope.launch { runQuickAnswer(question) }.also(::clearWhenCompleted)
    }

    private suspend fun runQuickAnswer(question: String) {
        val index = activeIndex ?: return
        val recent = _uiState.value.messages.map(TranscriptChatSessionStore.Message::text)
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.SEARCHING,
                draftQuestion = question,
                currentAnswer = "",
                currentCitations = emptyList(),
                stageLabel = "관련 구간 검색",
                statusMessage = "질문과 관련된 전사 구간을 기기에서 찾고 있습니다.",
                canRetry = false,
            )
        }
        val ranked = withContext(Dispatchers.Default) { TranscriptChatSearch.rank(question, recent, index.units) }
        val context = TranscriptChatSearch.buildContext(ranked, plannedUnits)
        if (context.isBlank()) {
            retryAction = null
            _uiState.update {
                it.copy(
                    phase = TranscriptChatPhase.COMPLETED,
                    currentAnswer = "전사에서 확인되지 않습니다.",
                    stageLabel = "근거 부족",
                    statusMessage = "전체 정밀 탐색으로 모든 구간을 확인할 수 있습니다.",
                    preciseAvailable = true,
                )
            }
            return
        }
        val allowedIds = ranked.map { it.unit.unitId }.toSet()
        val allowedUnits = plannedUnits.filter { it.unitId in allowedIds }
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.ANSWERING,
                stageLabel = "답변 생성",
                statusMessage = "선택된 전사 근거로 답변하고 있습니다.",
            )
        }
        try {
            val answer = withChatLeases("chat_answer_${requireNotNull(document).source.id}") {
                val history = prepareHistory()
                llm.stream(
                    TranscriptChatProfile.quickAnswerRequest(question, history.digest, history.recent, context),
                    TranscriptChatPolicy.MAX_ANSWER_CHARS,
                ) { delta -> _uiState.update { it.copy(currentAnswer = (it.currentAnswer + delta).take(TranscriptChatPolicy.MAX_ANSWER_CHARS)) } }
            }
            completeAnswer(question, answer, allowedUnits)
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            fail(error, "답변을 완료하지 못했습니다.")
        }
    }

    private fun startPreciseSearch(question: String) {
        if (activeJob?.isActive == true || !ensureReady()) return
        retryAction = RetryAction.Precise(question)
        activeJob = viewModelScope.launch { runPreciseSearch(question) }.also(::clearWhenCompleted)
    }

    private suspend fun runPreciseSearch(question: String) {
        val sourceDocument = document ?: return
        val fingerprint = TranscriptChatPolicy.fingerprint(sourceDocument)
        val checkpoint = preciseStore.read(sourceDocument.source)
            ?.takeIf { it.isReusable(fingerprint, question, plannedUnits.size) }
        val findings = checkpoint?.findings.orEmpty().associateBy(TranscriptPreciseSearchStore.Finding::unitId).toMutableMap()
        val total = TranscriptPreciseSearchPlanner.totalSteps(plannedUnits.size)
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.SEARCHING,
                mode = TranscriptChatMode.PRECISE,
                draftQuestion = question,
                completedSteps = findings.size,
                totalSteps = total,
                currentAnswer = "",
                currentCitations = emptyList(),
                stageLabel = "전체 구간 탐색",
                statusMessage = "사용자가 선택한 전체 정밀 탐색을 진행하고 있습니다.",
                canRetry = false,
            )
        }
        try {
            val finalAnswer = withChatLeases("chat_precise_${sourceDocument.source.id}") {
                for (unit in plannedUnits.filterNot { findings.containsKey(it.unitId) }) {
                    val finding = llm.stream(
                        TranscriptChatProfile.preciseScanRequest(question, unit),
                        TranscriptChatPolicy.MAX_FINDING_CHARS,
                    ) { }
                    findings[unit.unitId] = TranscriptPreciseSearchStore.Finding(unit.unitId, finding)
                    preciseStore.save(
                        TranscriptPreciseSearchStore.Entry(
                            source = sourceDocument.source,
                            sourceFingerprint = fingerprint,
                            question = question,
                            findings = findings.values.sortedBy { it.unitId },
                            totalUnits = plannedUnits.size,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                    _uiState.update { it.copy(completedSteps = findings.size) }
                }
                var completed = plannedUnits.size
                var level = findings.values.sortedBy { it.unitId }.map { "[${it.unitId}] ${it.text}" }
                if (level.size == 1) {
                    level = listOf(
                        llm.stream(
                            TranscriptChatProfile.preciseMergeRequest(question, level, finalRound = true),
                            TranscriptChatPolicy.MAX_ANSWER_CHARS,
                        ) { delta ->
                            _uiState.update { state ->
                                state.copy(currentAnswer = (state.currentAnswer + delta).take(TranscriptChatPolicy.MAX_ANSWER_CHARS))
                            }
                        },
                    )
                    completed += 1
                    _uiState.update {
                        it.copy(
                            phase = TranscriptChatPhase.ANSWERING,
                            completedSteps = completed,
                            stageLabel = "최종 답변",
                        )
                    }
                }
                while (level.size > 1) {
                    val batches = level.chunked(TranscriptPreciseSearchPlanner.MERGE_BATCH_SIZE)
                    val finalRound = batches.size == 1
                    level = batches.map { batch ->
                        llm.stream(
                            TranscriptChatProfile.preciseMergeRequest(question, batch, finalRound),
                            if (finalRound) TranscriptChatPolicy.MAX_ANSWER_CHARS else TranscriptChatPolicy.MAX_FINDING_CHARS,
                        ) { delta ->
                            if (finalRound) {
                                _uiState.update { state -> state.copy(currentAnswer = (state.currentAnswer + delta).take(TranscriptChatPolicy.MAX_ANSWER_CHARS)) }
                            }
                        }
                    }
                    completed += batches.size
                    _uiState.update {
                        it.copy(
                            phase = if (finalRound) TranscriptChatPhase.ANSWERING else TranscriptChatPhase.SEARCHING,
                            completedSteps = completed,
                            stageLabel = if (finalRound) "최종 답변" else "발견 사항 통합",
                        )
                    }
                }
                level.single()
            }
            completeAnswer(question, finalAnswer, plannedUnits)
            preciseStore.delete(sourceDocument.source)
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            fail(error, "전체 정밀 탐색을 완료하지 못했습니다.")
        }
    }

    private fun completeAnswer(question: String, answer: String, allowedUnits: List<TranscriptChatPlannedUnit>) {
        val sourceDocument = document ?: return
        val citations = TranscriptChatCitations.validate(answer, allowedUnits)
        val now = System.currentTimeMillis()
        val allMessages = _uiState.value.messages + listOf(
            TranscriptChatSessionStore.Message(TranscriptChatSessionStore.Role.USER, question, timestampMs = now),
            TranscriptChatSessionStore.Message(
                TranscriptChatSessionStore.Role.ASSISTANT,
                answer,
                citationUnitIds = citations.map(TranscriptCitation::unitId),
                timestampMs = now,
            ),
        )
        val droppedMessages = (allMessages.size - TranscriptChatPolicy.MAX_MESSAGES).coerceAtLeast(0)
        val messages = allMessages.takeLast(TranscriptChatPolicy.MAX_MESSAGES)
        val priorSession = activeSession
        val nextSession = TranscriptChatSessionStore.Entry(
            source = sourceDocument.source,
            sourceFingerprint = TranscriptChatPolicy.fingerprint(sourceDocument),
            messages = messages,
            historyDigest = priorSession?.historyDigest.orEmpty(),
            historyDigestThrough = priorSession?.historyDigestThrough
                ?.minus(droppedMessages)
                ?.coerceIn(0, messages.size)
                ?: 0,
            updatedAtMs = now,
        )
        sessionStore.save(nextSession)
        activeSession = nextSession
        retryAction = null
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.COMPLETED,
                messages = messages,
                currentAnswer = "",
                currentCitations = citations,
                draftQuestion = "",
                completedSteps = if (it.totalSteps > 0) it.totalSteps else it.completedSteps,
                stageLabel = "완료",
                statusMessage = if (citations.isEmpty()) {
                    "답변은 완료됐지만 활성화할 수 있는 근거 ID가 없습니다."
                } else {
                    "답변과 검증된 전사 근거를 저장했습니다."
                },
                canRetry = false,
            )
        }
    }

    private fun ensureReady(): Boolean {
        if (!llm.isAuthenticated()) {
            _uiState.update { it.copy(isAuthenticated = false, phase = TranscriptChatPhase.ERROR, statusMessage = "ChatGPT 연결이 필요합니다.") }
            return false
        }
        if (activeIndex?.isComplete != true) {
            _uiState.update { it.copy(phase = TranscriptChatPhase.CONSENT_REQUIRED, statusMessage = "먼저 전사 구간 인덱싱에 동의하세요.") }
            return false
        }
        return true
    }

    private suspend fun <T> withChatLeases(workId: String, block: suspend () -> T): T {
        val llmLease = when (val result = llmCoordinator.tryAcquire(CodexLlmRequestCoordinator.Owner.CHAT, workId)) {
            is CodexLlmRequestCoordinator.AcquireResult.Acquired -> result.lease
            is CodexLlmRequestCoordinator.AcquireResult.Busy -> throw ChatBusyException()
        }
        var deviceLease: DeviceWorkCoordinator.Lease? = null
        try {
            deviceLease = when (val result = deviceCoordinator.tryAcquire(DeviceWorkCoordinator.Owner.CHAT, workId)) {
                is DeviceWorkCoordinator.AcquireResult.Acquired -> result.lease
                is DeviceWorkCoordinator.AcquireResult.Busy -> throw ChatBusyException()
            }
            return block()
        } finally {
            deviceLease?.let { deviceCoordinator.releaseAfterTerminal(it, DeviceWorkCoordinator.TerminalOutcome.COMPLETED) }
            llmCoordinator.release(llmLease)
        }
    }

    private suspend fun prepareHistory(): PreparedHistory {
        var digest = activeSession?.historyDigest.orEmpty()
        val messages = _uiState.value.messages
        val window = TranscriptChatSearch.historyWindow(
            messages = messages,
            existingDigest = digest,
            digestThrough = activeSession?.historyDigestThrough?.coerceAtMost(messages.size) ?: 0,
        )
        if (window.pendingDigest.isEmpty()) return PreparedHistory(digest, window.recent)

        TranscriptChatSearch.digestBatches(window.pendingDigest).forEach { batch ->
            digest = llm.stream(
                TranscriptChatProfile.historyDigestRequest(digest, batch),
                TranscriptChatPolicy.MAX_HISTORY_DIGEST_CHARS,
            ) { }
        }
        val sourceDocument = document ?: return PreparedHistory(digest, window.recent)
        val updatedSession = TranscriptChatSessionStore.Entry(
            source = sourceDocument.source,
            sourceFingerprint = TranscriptChatPolicy.fingerprint(sourceDocument),
            messages = messages,
            historyDigest = digest,
            historyDigestThrough = window.digestThrough,
            updatedAtMs = System.currentTimeMillis(),
        )
        sessionStore.save(updatedSession)
        activeSession = updatedSession
        return PreparedHistory(digest, window.recent)
    }

    private fun fail(error: Exception, prefix: String) {
        _uiState.update {
            it.copy(
                phase = TranscriptChatPhase.ERROR,
                currentAnswer = "",
                stageLabel = "확인 필요",
                statusMessage = "$prefix ${safeErrorMessage(error)}",
                canRetry = retryAction != null,
            )
        }
    }

    private fun clearWhenCompleted(job: Job) {
        job.invokeOnCompletion { if (activeJob === job) activeJob = null }
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }

    private sealed interface RetryAction {
        data object Index : RetryAction
        data class Quick(val question: String) : RetryAction
        data class Precise(val question: String) : RetryAction
    }

    private data class PreparedHistory(
        val digest: String,
        val recent: List<TranscriptChatSessionStore.Message>,
    )

    private class ChatBusyException : Exception()

    companion object {
        internal fun safeErrorMessage(error: Exception): String = when (error) {
            is ChatBusyException -> "다른 녹음·전사·요약·채팅 작업이 끝난 뒤 다시 시도하세요."
            is CodexLlmHttpException -> when {
                error.statusCode == 429 -> "요청이 많습니다. 잠시 뒤 재시도를 눌러주세요."
                error.statusCode in 500..599 -> "외부 서비스가 응답하지 않습니다. 자동 재시도하지 않았습니다."
                else -> "외부 요청을 완료하지 못했습니다."
            }
            is SocketTimeoutException -> "요청 시간이 초과됐습니다. 자동 재시도하지 않았습니다."
            is OAuthException -> "ChatGPT 인증을 다시 확인하세요."
            else -> "민감한 오류 상세는 표시하지 않습니다."
        }
    }
}
