package com.stt.benchmark.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.stt.benchmark.data.CompletedResultTargetStore;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TranscriptionCompletionNotifierInstrumentedTest {
    private Context context;
    private NotificationManager manager;
    private TranscriptionCompletionNotifier notifier;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".deviceTest"));
        manager = context.getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                    PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    "long_transcription",
                    "Synthetic completion audit",
                    NotificationManager.IMPORTANCE_LOW
            ));
        }
        notifier = new TranscriptionCompletionNotifier(context);
        notifier.cancel();
        new CompletedResultTargetStore(context).clear();
    }

    @After
    public void tearDown() {
        notifier.cancel();
        new CompletedResultTargetStore(context).clear();
    }

    @Test
    public void syntheticGroupTargetPostsExactlyOneCompletionNotification() {
        CompletedResultTargetStore.Target target = CompletedResultTargetStore.Target.Companion.create(
                CompletedResultTargetStore.Type.RECORDING_GROUP,
                "recording_group_device_audit"
        );
        assertNotNull(target);
        CompletedResultTargetStore store = new CompletedResultTargetStore(context);

        assertTrue(store.save(target));
        assertTrue(notifier.post(target));
        assertTrue(notifier.post(target));

        assertNotNull(store.load());
        assertEquals(CompletedResultTargetStore.Type.RECORDING_GROUP, store.load().getType());
        long count = Arrays.stream(manager.getActiveNotifications())
                .filter(notification -> "전사 완료".contentEquals(
                        notification.getNotification().extras.getCharSequence("android.title")
                ))
                .count();
        assertEquals(1L, count);
        assertTrue(Arrays.stream(manager.getActiveNotifications())
                .filter(notification -> "전사 완료".contentEquals(
                        notification.getNotification().extras.getCharSequence("android.title")
                ))
                .findFirst()
                .orElseThrow()
                .getNotification()
                .contentIntent
                .isImmutable());
    }
}
