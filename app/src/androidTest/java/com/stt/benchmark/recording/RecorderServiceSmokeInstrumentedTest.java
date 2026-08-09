package com.stt.benchmark.recording;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stt.benchmark.MainActivity;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class RecorderServiceSmokeInstrumentedTest {
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
    public void recordsThreeSecondsAndLeavesOnlyVerifiedFinalFile() throws Exception {
        assumeRecorderSmokeEnabled();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        prepareRecording(instrumentation);
        RecorderController.INSTANCE.start(context);
        RecordingRuntimeSnapshot recording = waitForPhase(RecordingPhase.RECORDING, 30);
        assertTrue(recording.getSessionId().startsWith("recording_"));
        instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
        Thread.sleep(3_000L);
        RecorderController.INSTANCE.stop(context);
        assertVerifiedTerminalRecording(waitForTerminal(30));
    }

    @Test
    public void notificationStopUsesSameVerifiedTerminalContract() throws Exception {
        assumeRecorderSmokeEnabled();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        prepareRecording(instrumentation);
        RecorderController.INSTANCE.start(context);
        waitForPhase(RecordingPhase.RECORDING, 30);
        instrumentation.runOnMainSync(() -> activity.moveTaskToBack(true));
        Thread.sleep(1_500L);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = null;
        for (StatusBarNotification item : manager.getActiveNotifications()) {
            if (item.getId() == RecorderNotificationFactory.NOTIFICATION_ID) {
                notification = item.getNotification();
                break;
            }
        }
        assertNotNull("녹음 foreground notification 없음", notification);
        assertNotNull("녹음 정지 action 없음", notification.actions);
        assertTrue("녹음 정지 action 없음", notification.actions.length > 0);
        notification.actions[0].actionIntent.send();
        assertVerifiedTerminalRecording(waitForTerminal(30));
    }

    private void assumeRecorderSmokeEnabled() {
        Assume.assumeTrue(
                "실제 마이크 smoke는 -e runRecorderSmoke true에서만 실행합니다",
                "true".equalsIgnoreCase(
                        InstrumentationRegistry.getArguments().getString("runRecorderSmoke", "false")
                )
        );
    }

    private void prepareRecording(Instrumentation instrumentation) throws Exception {
        context = instrumentation.getTargetContext();
        grant(context, Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grant(context, Manifest.permission.POST_NOTIFICATIONS);
        }
        deleteRecursively(new File(context.getFilesDir(), "recording_sessions"));
        deleteRecursively(new File(context.getFilesDir(), "recordings"));
        activity = instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
    }

    private void assertVerifiedTerminalRecording(RecordingRuntimeSnapshot terminal) {
        assertTrue("terminal phase=" + terminal.getPhase() + " message=" + terminal.getMessage(),
                terminal.getPhase() == RecordingPhase.SAVED);

        List<RecordingSessionStore.RecordingSession> sessions =
                new RecordingSessionStore(context).listAll();
        assertFalse(sessions.isEmpty());
        RecordingSessionStore.RecordingSession latest = sessions.get(0);
        assertTrue(latest.getPhase() == RecordingPhase.SAVED);
        assertFalse(latest.getReadyChunks().isEmpty());
        RecordingSessionStore.RecordingChunk chunk = latest.getReadyChunks().get(0);
        File finalFile = new File(chunk.getFinalPath());
        assertTrue(finalFile.isFile());
        assertTrue(finalFile.length() > 0L);
        assertTrue(chunk.getSha256().matches("[a-f0-9]{64}"));
        assertTrue(chunk.getDurationMs() > 0L);
        assertTrue(chunk.getSampleRateHz() > 0);
        assertTrue(chunk.getChannelCount() > 0);
        assertNotNull(chunk.getCodec());
        assertFalse(hasPartFile(new File(context.getFilesDir(), "recordings")));
    }

    private RecordingRuntimeSnapshot waitForPhase(RecordingPhase phase, long timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        RecordingRuntimeSnapshot value;
        do {
            value = RecordingRuntime.INSTANCE.getSnapshot().getValue();
            if (value.getPhase() == phase) return value;
            if (value.getPhase() == RecordingPhase.FAILED) {
                throw new AssertionError("녹음 시작 실패: " + value.getMessage());
            }
            Thread.sleep(100L);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("녹음 시작 timeout, phase=" + value.getPhase());
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
        throw new AssertionError("녹음 종료 timeout, phase=" + value.getPhase());
    }

    private void grant(Context target, String permission) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand("pm grant " + target.getPackageName() + " " + permission);
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
        // data-safe deviceTest package only; never points at com.stt.benchmark production data.
        file.delete();
    }
}
