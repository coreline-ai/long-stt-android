package com.stt.benchmark.summary

import android.content.ActivityNotFoundException
import android.content.Context

/** 시스템 chooser 실행 실패를 UI가 비민감 안내로 처리할 수 있게 결과로 제한한다. */
object SummaryShareLauncher {
    enum class Result { STARTED, NO_HANDLER, BLOCKED }

    fun launch(context: Context, summary: String): Result = try {
        context.startActivity(SummaryShareIntentFactory.createChooser(summary))
        Result.STARTED
    } catch (_: ActivityNotFoundException) {
        Result.NO_HANDLER
    } catch (_: SecurityException) {
        Result.BLOCKED
    }
}
