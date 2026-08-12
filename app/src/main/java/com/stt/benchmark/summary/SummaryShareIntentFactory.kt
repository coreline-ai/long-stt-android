package com.stt.benchmark.summary

import android.content.Intent

/** Builds a text-only Android Sharesheet payload from an already saved final summary. */
object SummaryShareIntentFactory {
    const val SHARE_TITLE = "요약 공유"

    fun createChooser(summary: String): Intent = Intent.createChooser(create(summary), SHARE_TITLE)

    fun create(summary: String): Intent {
        val selectedSummary = summary.trim()
        require(selectedSummary.isNotEmpty()) { "summary must not be blank" }
        require(selectedSummary.length <= SummaryRequestPolicy.MAX_SUMMARY_CHARS) { "summary is too long" }

        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Long STT 요약")
            putExtra(Intent.EXTRA_TEXT, selectedSummary)
        }
    }
}
