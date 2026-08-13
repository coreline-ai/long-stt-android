package com.stt.benchmark.export

import android.content.ActivityNotFoundException

/** SAF document picker launch failures are converted to non-sensitive UI outcomes. */
internal object TranscriptDocumentLauncher {
    enum class Result { STARTED, NO_HANDLER, BLOCKED }

    fun launch(block: () -> Unit): Result = try {
        block()
        Result.STARTED
    } catch (_: ActivityNotFoundException) {
        Result.NO_HANDLER
    } catch (_: SecurityException) {
        Result.BLOCKED
    }
}
