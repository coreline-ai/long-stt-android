package com.stt.benchmark.data;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.stt.benchmark.whisper.TranscriptSegment;
import com.stt.benchmark.whisper.TranscriptionResult;
import com.whispercpp.whisper.WhisperLib;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class StorageRecoveryInstrumentedTest {

    private Context context;

    @Before
    public void setUp() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File testRoot = new File(target.getFilesDir(), "instrumentation-storage-recovery");
        context = new ContextWrapper(target) {
            @Override
            public File getFilesDir() {
                testRoot.mkdirs();
                return testRoot;
            }
        };
        assertTrue("test filesDir 생성 실패", testRoot.mkdirs() || testRoot.isDirectory());
        cleanTestFiles();
    }

    @After
    public void tearDown() {
        cleanTestFiles();
    }

    @Test
    public void sessionStoreRecoversLastFinishedCheckpointAfterInterruptedWrite() throws Exception {
        TranscriptionSessionStore store = new TranscriptionSessionStore(context);
        TranscriptionSessionStore.Checkpoint initial = checkpoint(
                TranscriptionSessionStore.Status.RUNNING,
                "atomic"
        );
        store.save(initial);

        leaveInterruptedAtomicWrite(new File(context.getFilesDir(), "stt_sessions/atomic.json"));

        TranscriptionSessionStore.Checkpoint recovered = store.load("atomic");
        assertNotNull(recovered);
        assertEquals(TranscriptionSessionStore.Status.RUNNING, recovered.getStatus());
        assertEquals(initial.getDurationMs(), recovered.getDurationMs());
    }

    @Test
    public void startupReconciliationChangesOnlyActivelyRunningSessions() {
        TranscriptionSessionStore store = new TranscriptionSessionStore(context);
        store.save(checkpoint(TranscriptionSessionStore.Status.RUNNING, "running"));
        store.save(checkpoint(TranscriptionSessionStore.Status.COMPLETED, "completed"));

        List<TranscriptionSessionStore.Checkpoint> changed = store.reconcileAfterProcessDeath(5_000L);

        assertEquals(1, changed.size());
        assertEquals("running", changed.get(0).getSessionId());
        assertEquals(TranscriptionSessionStore.Status.INTERRUPTED, store.load("running").getStatus());
        assertEquals(TranscriptionSessionStore.Status.COMPLETED, store.load("completed").getStatus());
    }

    @Test
    public void unknownSessionSchemaIsRejectedWithoutRewritingSource() throws Exception {
        File file = new File(context.getFilesDir(), "stt_sessions/unknown.json");
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        byte[] original = "{\"version\":999,\"sessionId\":\"unknown\"}".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(file.toPath(), original);

        TranscriptionSessionStore.Checkpoint loaded = new TranscriptionSessionStore(context).load("unknown");

        assertEquals(null, loaded);
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(file.toPath()));
    }

    @Test
    public void mediaIndexUsesAtomicReadRecovery() throws Exception {
        MediaLibraryStore store = new MediaLibraryStore(context);
        File audio = new File(context.getFilesDir(), "test_audio_atomic.wav");
        java.nio.file.Files.write(audio.toPath(), new byte[]{1, 2, 3});
        store.registerAudio(audio, "atomic.wav", 1_000L);

        leaveInterruptedAtomicWrite(new File(context.getFilesDir(), "media_library.json"));

        boolean found = false;
        for (MediaLibraryStore.AudioEntry entry : store.listAudios()) {
            if (entry.getPath().equals(audio.getAbsolutePath()) && entry.getDisplayName().equals("atomic.wav")) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    public void benchmarkV2StoresSessionIdentityWithoutTranscriptAndRecovers() throws Exception {
        BenchmarkRecorder recorder = new BenchmarkRecorder(context);
        String transcript = "sensitive transcript must not be duplicated";
        TranscriptionResult result = new TranscriptionResult(
                transcript,
                Collections.singletonList(new TranscriptSegment(0L, 1_000L, transcript)),
                100L,
                1_000L,
                "1MB",
                "test",
                Collections.emptyList()
        );
        recorder.appendResult(result, "/private/test.wav", "test.bin", "fixture", "session-v2");
        File csvFile = new File(context.getFilesDir(), "stt_benchmark_results_v2.csv");
        String csv;
        try (java.io.InputStream input = new AtomicFile(csvFile).openRead()) {
            csv = new String(readAllBytes(input), StandardCharsets.UTF_8);
        }
        assertTrue(csv.contains("session-v2"));
        assertFalse(csv.contains(transcript));

        leaveInterruptedAtomicWrite(csvFile);

        BenchmarkRecorder.BenchmarkRecord recovered = null;
        for (BenchmarkRecorder.BenchmarkRecord record : recorder.loadAll()) {
            if (record.getSessionId().equals("session-v2")) recovered = record;
        }
        assertNotNull(recovered);
        assertEquals("", recovered.getText());
        assertEquals(1, recovered.getSegmentCount());

        assertTrue(recorder.deleteMatchingResult(
                "session-v2",
                "/private/test.wav",
                "test.bin",
                "fixture",
                transcript
        ));
        for (BenchmarkRecorder.BenchmarkRecord record : recorder.loadAll()) {
            assertFalse(record.getSessionId().equals("session-v2"));
        }
    }

    @Test
    public void nativeBuildInfoContainsLockedWhisperCommit() {
        String info = WhisperLib.Companion.getSystemInfo();
        assertTrue(info.contains("whisper.cpp 1.9.2"));
        assertTrue(info.contains("8631825d41a2712268813981a9550b04a3f225e5"));
    }

    private void leaveInterruptedAtomicWrite(File file) throws Exception {
        FileOutputStream stream = new AtomicFile(file).startWrite();
        stream.write("{interrupted".getBytes(StandardCharsets.UTF_8));
        stream.getFD().sync();
        stream.close();
        // finishWrite/failWrite를 호출하지 않아 process death 직전 상태를 재현한다.
    }

    private TranscriptionSessionStore.Checkpoint checkpoint(
            TranscriptionSessionStore.Status status,
            String id
    ) {
        return new TranscriptionSessionStore.Checkpoint(
                id,
                status,
                "/data/model.bin",
                "/data/audio.wav",
                "",
                600_001L,
                2,
                0,
                "",
                1_000L,
                1_000L,
                Collections.emptyList()
        );
    }

    private byte[] readAllBytes(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private void cleanTestFiles() {
        if (context == null) return;
        deleteRecursively(new File(context.getFilesDir(), "stt_sessions"));
        deleteAtomic("media_library.json");
        deleteAtomic("stt_benchmark_results.csv");
        deleteAtomic("stt_benchmark_results_v2.csv");
        File[] testAudio = context.getFilesDir().listFiles(
                (dir, name) -> name.startsWith("test_audio_")
        );
        if (testAudio != null) for (File file : testAudio) file.delete();
    }

    private void deleteAtomic(String name) {
        new File(context.getFilesDir(), name).delete();
        new File(context.getFilesDir(), name + ".bak").delete();
        new File(context.getFilesDir(), name + ".new").delete();
    }

    private void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        file.delete();
    }
}
