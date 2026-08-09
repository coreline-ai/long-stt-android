package com.stt.benchmark.recording;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stt.benchmark.MainActivity;
import com.stt.benchmark.ui.recording.RecordingAvailability;
import com.stt.benchmark.ui.recording.RecordingUiState;
import com.stt.benchmark.ui.recording.RecordingViewModel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RecordingUiRuntimeInstrumentedTest {
    private Context context;
    private MainActivity activity;
    private RecordingViewModel viewModel;

    @After
    public void cleanUp() {
        if (viewModel != null) {
            try {
                viewModel.stopRecording();
            } catch (Throwable ignored) {
            }
        }
        if (activity != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    @Test
    public void activityRecreationRestoresLiveServiceTimerAndChunk() throws Exception {
        Assume.assumeTrue(
                "실제 UI/runtime smoke는 -e runRecorderSmoke true에서만 실행합니다",
                "true".equalsIgnoreCase(
                        InstrumentationRegistry.getArguments().getString("runRecorderSmoke", "false")
                )
        );
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        grant(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grant(Manifest.permission.POST_NOTIFICATIONS);
        }
        deleteRecursively(new File(context.getFilesDir(), "recording_sessions"));
        deleteRecursively(new File(context.getFilesDir(), "recordings"));
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        viewModel = new ViewModelProvider(activity).get(RecordingViewModel.class);
        viewModel.refreshEnvironmentAndSessions();
        waitForAvailability(RecordingAvailability.READY, 15);

        assertTrue(viewModel.startRecording());
        RecordingUiState before = waitForPhase(RecordingPhase.RECORDING, 30);
        String sessionId = before.getRuntime().getSessionId();
        int chunkIndex = before.getRuntime().getCurrentChunkIndex();
        Thread.sleep(1_200L);

        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(),
                null,
                false
        );
        instrumentation.runOnMainSync(activity::recreate);
        MainActivity recreated = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15_000L);
        instrumentation.removeMonitor(monitor);
        assertNotNull("재생성 Activity 없음", recreated);
        activity = recreated;
        instrumentation.waitForIdleSync();
        viewModel = new ViewModelProvider(activity).get(RecordingViewModel.class);

        RecordingUiState restored = waitForPhase(RecordingPhase.RECORDING, 15);
        assertEquals(sessionId, restored.getRuntime().getSessionId());
        assertEquals(chunkIndex, restored.getRuntime().getCurrentChunkIndex());
        assertTrue(restored.getRuntime().getElapsedMs() >= before.getRuntime().getElapsedMs());

        instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
        assertTrue(viewModel.stopRecording());
        waitForPhase(RecordingPhase.SAVED, 30);
    }

    private RecordingUiState waitForAvailability(
            RecordingAvailability availability,
            long timeoutSeconds
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RecordingUiState value;
        do {
            value = viewModel.getUiState().getValue();
            if (value.getAvailability() == availability) return value;
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("availability timeout=" + value.getAvailability());
    }

    private RecordingUiState waitForPhase(RecordingPhase phase, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RecordingUiState value;
        do {
            value = viewModel.getUiState().getValue();
            if (value.getDisplayPhase() == phase) return value;
            if (value.getDisplayPhase() == RecordingPhase.FAILED) {
                throw new AssertionError("녹음 실패: " + value.getMessage());
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("phase timeout=" + value.getDisplayPhase());
    }

    private void grant(String permission) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("pm grant " + context.getPackageName() + " " + permission);
        descriptor.close();
        Thread.sleep(300L);
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        // data-safe deviceTest package only.
        file.delete();
    }
}
