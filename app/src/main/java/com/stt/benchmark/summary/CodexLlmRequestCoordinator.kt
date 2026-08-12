package com.stt.benchmark.summary

import java.util.UUID

/** 요약·연결 probe·향후 전사 채팅이 한 번에 하나의 Codex 요청만 실행하도록 하는 process-local lease. */
class CodexLlmRequestCoordinator {
    enum class Owner { PROBE, SUMMARY, CHAT }

    data class Lease internal constructor(
        val owner: Owner,
        val workId: String,
        internal val token: String,
    )

    data class Snapshot(val owner: Owner?, val workId: String)

    sealed interface AcquireResult {
        data class Acquired(val lease: Lease) : AcquireResult
        data class Busy(val snapshot: Snapshot) : AcquireResult
    }

    private var active: Lease? = null

    @Synchronized
    fun tryAcquire(owner: Owner, workId: String): AcquireResult {
        require(workId.matches(WORK_ID_REGEX)) { "invalid LLM workId" }
        active?.let { return AcquireResult.Busy(Snapshot(it.owner, it.workId)) }
        val lease = Lease(owner, workId, UUID.randomUUID().toString())
        active = lease
        return AcquireResult.Acquired(lease)
    }

    @Synchronized
    fun release(lease: Lease): Boolean {
        val current = active ?: return false
        if (current.token != lease.token || current.owner != lease.owner) return false
        active = null
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot = active?.let { Snapshot(it.owner, it.workId) } ?: Snapshot(null, "")

    private companion object {
        val WORK_ID_REGEX = Regex("[A-Za-z0-9:_-]{1,180}")
    }
}

object CodexLlmRequestRuntime {
    val coordinator = CodexLlmRequestCoordinator()
}
