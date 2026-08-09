package com.stt.benchmark.ui.onboarding

import android.content.Context

/** A single non-sensitive product-introduction flag; recording settings use a separate store. */
class OnboardingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isComplete(): Boolean = preferences.getBoolean(KEY_COMPLETE, false)

    fun setComplete(complete: Boolean) {
        preferences.edit().putBoolean(KEY_COMPLETE, complete).apply()
    }

    private companion object {
        const val FILE_NAME = "long_stt_ui"
        const val KEY_COMPLETE = "onboarding_complete_v1"
    }
}
