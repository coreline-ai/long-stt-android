package com.stt.benchmark.core

import java.util.UUID

/** 녹음·전사·요약이 동시에 장시간 자원을 소유하지 못하게 하는 process-local lease. */
class DeviceWorkCoordinator {
    enum class Owner { RECORDING, TRANSCRIPTION, SUMMARY, CHAT }
    enum class LeaseState { ACTIVE, FINALIZING }
    enum class TerminalOutcome { COMPLETED, FAILED, CANCELLED }

    data class Lease internal constructor(
        val owner: Owner,
        val workId: String,
        internal val token: String,
    )

    data class Snapshot(
        val owner: Owner?,
        val workId: String,
        val state: LeaseState?,
    )

    sealed interface AcquireResult {
        data class Acquired(val lease: Lease) : AcquireResult
        data class Busy(val snapshot: Snapshot) : AcquireResult
    }

    private data class ActiveLease(val lease: Lease, val state: LeaseState)
    private var active: ActiveLease? = null

    @Synchronized
    fun tryAcquire(owner: Owner, workId: String): AcquireResult {
        require(workId.isNotBlank()) { "workId가 필요합니다" }
        val current = active
        if (current != null) return AcquireResult.Busy(current.toSnapshot())
        val lease = Lease(owner, workId, UUID.randomUUID().toString())
        active = ActiveLease(lease, LeaseState.ACTIVE)
        return AcquireResult.Acquired(lease)
    }

    @Synchronized
    fun beginFinalization(lease: Lease): Boolean {
        val current = active ?: return false
        if (current.lease.token != lease.token || current.lease.owner != lease.owner) return false
        active = current.copy(state = LeaseState.FINALIZING)
        return true
    }

    /** terminal checkpoint 저장이 끝난 호출자만 outcome을 제공해 lease를 해제한다. */
    @Synchronized
    fun releaseAfterTerminal(lease: Lease, outcome: TerminalOutcome): Boolean {
        // outcome은 호출 계약을 명시하기 위한 값이며 모든 terminal 결과가 같은 방식으로 lease를 해제한다.
        @Suppress("UNUSED_VARIABLE") val terminal = outcome
        val current = active ?: return false
        if (current.lease.token != lease.token || current.lease.owner != lease.owner) return false
        active = null
        return true
    }

    @Synchronized
    fun snapshot(): Snapshot = active?.toSnapshot() ?: Snapshot(null, "", null)

    private fun ActiveLease.toSnapshot() = Snapshot(lease.owner, lease.workId, state)
}
