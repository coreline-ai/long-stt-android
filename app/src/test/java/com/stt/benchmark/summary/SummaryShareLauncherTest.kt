package com.stt.benchmark.summary

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class SummaryShareLauncherTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun missingShareHandlerReturnsFailureWithoutThrowing() {
        val result = SummaryShareLauncher.launch(
            ThrowingContext(context, ActivityNotFoundException("synthetic")),
            "synthetic summary",
        )

        assertEquals(SummaryShareLauncher.Result.NO_HANDLER, result)
    }

    @Test
    fun blockedActivityLaunchReturnsFailureWithoutThrowing() {
        val result = SummaryShareLauncher.launch(
            ThrowingContext(context, SecurityException("synthetic")),
            "synthetic summary",
        )

        assertEquals(SummaryShareLauncher.Result.BLOCKED, result)
    }

    private class ThrowingContext(
        base: Context,
        private val failure: RuntimeException,
    ) : ContextWrapper(base) {
        override fun startActivity(intent: Intent?) {
            throw failure
        }
    }
}
