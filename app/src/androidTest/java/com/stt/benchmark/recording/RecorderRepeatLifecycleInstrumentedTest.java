package com.stt.benchmark.recording;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stt.benchmark.MainActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Repetition gate for the data-safe deviceTest package. It deliberately deletes only this test
 * package's files before starting; the production com.stt.benchmark package is never addressed.
 */
@RunWith(AndroidJUnit4.class)
public class RecorderRepeatLifecycleInstrumentedTest {
    private Context context;
    private Activity activity;

    @After
    public void cleanUp() {
        if (context != null) {
            try {
                RecorderController.INSTANCE.stop(context);
            } catch (Throwable ignored) {
            }
        }
        if (activity != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
        }
    }

    @Test
    public void twentyStartStopsLeaveUniqueVerifiedFilesAndNoRunningService() throws Exception {
        Assume.assumeTrue(
                "반복 실제 마이크 gate는 -e runRecorderSmoke true에서만 실행합니다",
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
        activity = instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();

        Set<String> sessionIds = new HashSet<>();
        for (int attempt = 1; attempt <= 20; attempt++) {
            RecorderController.INSTANCE.start(context);
            RecordingRuntimeSnapshot running = waitForPhase(RecordingPhase.RECORDING, 30);
            assertTrue("중복 session at attempt " + attempt, sessionIds.add(running.getSessionId()));
            // A short sample is enough to force a real container finalization without recording content.
            Thread.sleep(350L);
            RecorderController.INSTANCE.stop(context);
            RecordingRuntimeSnapshot terminal = waitForTerminal(30);
            assertEquals("attempt " + attempt + " terminal=" + terminal.getMessage(),
                    RecordingPhase.SAVED, terminal.getPhase());
        }

        List<RecordingSessionStore.RecordingSession> sessions =
                new RecordingSessionStore(context).listAll();
        assertEquals(20, sessions.size());
        for (RecordingSessionStore.RecordingSession session : sessions) {
            assertEquals(RecordingPhase.SAVED, session.getPhase());
            assertFalse(session.getReadyChunks().isEmpty());
            for (RecordingSessionStore.RecordingChunk chunk : session.getReadyChunks()) {
                File finalFile = new File(chunk.getFinalPath());
                assertTrue("final missing", finalFile.isFile());
                assertTrue("zero-byte final", finalFile.length() > 0L);
                assertTrue("SHA-256 missing", chunk.getSha256().matches("[a-f0-9]{64}"));
            }
        }
        assertFalse(hasPartFile(new File(context.getFilesDir(), "recordings")));
        waitForNoRecorderService(20);
    }

    private RecordingRuntimeSnapshot waitForPhase(RecordingPhase phase, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RecordingRuntimeSnapshot value;
        do {
            value = RecordingRuntime.INSTANCE.getSnapshot().getValue();
            if (value.getPhase() == phase) return value;
            if (value.getPhase() == RecordingPhase.FAILED) {
                throw new AssertionError("recording failed: " + value.getMessage());
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("recording start timeout=" + value.getPhase());
    }

    private RecordingRuntimeSnapshot waitForTerminal(long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RecordingRuntimeSnapshot value;
        do {
            value = RecordingRuntime.INSTANCE.getSnapshot().getValue();
            if (value.getPhase() == RecordingPhase.SAVED || value.getPhase() == RecordingPhase.FAILED) {
                return value;
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("recording terminal timeout=" + value.getPhase());
    }

    private void waitForNoRecorderService(long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        do {
            String services = shell("dumpsys activity services " + context.getPackageName());
            if (!services.contains("RecorderService")) return;
            Thread.sleep(250L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("RecorderService remained after terminal recording");
    }

    private String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1_024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        } finally {
            descriptor.close();
        }
    }

    private void grant(String permission) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("pm grant " + context.getPackageName() + " " + permission);
        descriptor.close();
        Thread.sleep(300L);
    }

    private boolean hasPartFile(File file) {
        if (!file.exists()) return false;
        if (file.isFile()) return file.getName().endsWith(".part");
        File[] children = file.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (hasPartFile(child)) return true;
        }
        return false;
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        // com.stt.benchmark.deviceTest only; never production app data.
        file.delete();
    }
}
