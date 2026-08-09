package com.stt.benchmark.core

/** Application process 안에서 녹음·전사·요약이 공유할 coordinator 정본. */
object DeviceWorkRuntime {
    val coordinator = DeviceWorkCoordinator()
}
