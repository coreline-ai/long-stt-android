package com.stt.benchmark.core

import android.util.Log
import com.stt.benchmark.BuildConfig

/**
 * Debug-only diagnostics boundary.
 *
 * File names, paths, importer Uris and exception messages can reveal user data. Product state is
 * checkpointed and presented through explicit UI; release builds intentionally emit none of these
 * application diagnostics to logcat. Keeping all app log calls behind this object also makes the
 * release policy auditable without changing service error handling.
 */
internal object AppLog {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        Log.w(tag, error?.let { "$message (${it.javaClass.simpleName})" } ?: message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        Log.e(tag, error?.let { "$message (${it.javaClass.simpleName})" } ?: message)
    }
}
