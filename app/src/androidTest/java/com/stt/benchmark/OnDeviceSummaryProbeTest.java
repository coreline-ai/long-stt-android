package com.stt.benchmark;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.mlkit.common.MlKit;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.summarization.Summarization;
import com.google.mlkit.genai.summarization.SummarizationRequest;
import com.google.mlkit.genai.summarization.SummarizationResult;
import com.google.mlkit.genai.summarization.Summarizer;
import com.google.mlkit.genai.summarization.SummarizerOptions;
import com.stt.benchmark.data.TranscriptionSessionStore;
import com.stt.benchmark.whisper.TranscriptSegment;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class OnDeviceSummaryProbeTest {

    @Test
    public void summarizeMiddleOfLatestSixHourTranscript() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Activity activity = instrumentation.startActivitySync(
                new Intent(context, SummaryProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        );
        instrumentation.waitForIdleSync();
        MlKit.initialize(context);

        TranscriptionSessionStore.Checkpoint checkpoint = findLatestSixHourCheckpoint(context);
        SourceSample source = selectMiddleSample(checkpoint);
        SummarizerOptions options = SummarizerOptions.builder(context)
                .setInputType(SummarizerOptions.InputType.ARTICLE)
                .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
                .setLanguage(SummarizerOptions.Language.KOREAN)
                .build();
        Summarizer summarizer = Summarization.getClient(options);
        long startedAt = System.currentTimeMillis();

        try {
            int initialStatus = summarizer.checkFeatureStatus().get(60, TimeUnit.SECONDS);
            Map<String, Object> checked = baseResult(checkpoint, source);
            checked.put("phase", "FEATURE_CHECKED");
            checked.put("featureStatus", statusName(initialStatus));
            writeResult(context, checked);
            if (initialStatus == FeatureStatus.UNAVAILABLE) {
                throw new IllegalStateException("AICore 한국어 요약 기능을 사용할 수 없습니다");
            }

            summarizer.prepareInferenceEngine().get(30, TimeUnit.MINUTES);
            int readyStatus = summarizer.checkFeatureStatus().get(60, TimeUnit.SECONDS);
            if (readyStatus != FeatureStatus.AVAILABLE) {
                throw new IllegalStateException(
                        "요약 모델 준비 후 상태가 AVAILABLE이 아닙니다: " + statusName(readyStatus)
                );
            }
            String modelName = summarizer.getBaseModelName().get(60, TimeUnit.SECONDS);
            SummarizationRequest request = SummarizationRequest.builder(source.text).build();
            long inferenceStartedAt = System.currentTimeMillis();
            SummarizationResult inference = summarizer.runInference(request).get(20, TimeUnit.MINUTES);
            long inferenceElapsedMs = System.currentTimeMillis() - inferenceStartedAt;
            String summary = inference.getSummary().trim();
            assertTrue("요약 결과가 비어 있습니다", !summary.isEmpty());

            Map<String, Object> completed = baseResult(checkpoint, source);
            completed.put("phase", "COMPLETED");
            completed.put("featureStatus", statusName(readyStatus));
            completed.put("baseModelName", modelName);
            completed.put("summaryChars", summary.length());
            completed.put("summary", summary);
            completed.put("inferenceElapsedMs", inferenceElapsedMs);
            completed.put("totalElapsedMs", System.currentTimeMillis() - startedAt);
            completed.put("completedAtMs", System.currentTimeMillis());
            writeResult(context, completed);
        } catch (Throwable error) {
            Map<String, Object> failed = baseResult(checkpoint, source);
            failed.put("phase", "FAILED");
            failed.put("errorType", error.getClass().getName());
            Throwable cause = error.getCause();
            failed.put("errorMessage", cause != null ? String.valueOf(cause.getMessage()) : String.valueOf(error.getMessage()));
            failed.put("totalElapsedMs", System.currentTimeMillis() - startedAt);
            writeResult(context, failed);
            throw new AssertionError("온디바이스 요약 사전 테스트 실패", error);
        } finally {
            summarizer.close();
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    private TranscriptionSessionStore.Checkpoint findLatestSixHourCheckpoint(Context context) {
        for (TranscriptionSessionStore.Checkpoint candidate : new TranscriptionSessionStore(context).listAll()) {
            if (candidate.getStatus() == TranscriptionSessionStore.Status.COMPLETED
                    && candidate.getDurationMs() >= SIX_HOURS_MS
                    && candidate.getChunks().size() == candidate.getTotalChunks()) {
                return candidate;
            }
        }
        throw new IllegalStateException("완료된 6시간 전사 세션이 없습니다");
    }

    private SourceSample selectMiddleSample(TranscriptionSessionStore.Checkpoint checkpoint) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (TranscriptionSessionStore.CompletedChunk chunk : checkpoint.getChunks()) {
            segments.addAll(chunk.getSegments());
        }
        segments.sort(Comparator.comparingLong(TranscriptSegment::getStartMs));
        if (segments.isEmpty()) throw new IllegalStateException("6시간 전사에 타임스탬프 세그먼트가 없습니다");

        long middleMs = checkpoint.getDurationMs() / 2L;
        int firstIndex = 0;
        for (int index = 0; index < segments.size(); index++) {
            if (segments.get(index).getEndMs() >= middleMs) {
                firstIndex = index;
                break;
            }
        }

        List<TranscriptSegment> selected = new ArrayList<>();
        int chars = 0;
        for (int index = firstIndex; index < segments.size(); index++) {
            TranscriptSegment segment = segments.get(index);
            String text = segment.getText().trim();
            if (text.isEmpty()) continue;
            if (!selected.isEmpty() && chars + text.length() + 1 > TARGET_SAMPLE_CHARS) break;
            selected.add(segment);
            chars += text.length() + 1;
        }
        if (chars < MIN_SAMPLE_CHARS) {
            throw new IllegalStateException("요약 테스트 입력이 너무 짧습니다: " + chars);
        }
        StringBuilder text = new StringBuilder();
        for (TranscriptSegment segment : selected) {
            if (text.length() > 0) text.append(' ');
            text.append(segment.getText().trim());
        }
        return new SourceSample(
                text.toString(),
                selected.get(0).getStartMs(),
                selected.get(selected.size() - 1).getEndMs()
        );
    }

    private Map<String, Object> baseResult(
            TranscriptionSessionStore.Checkpoint checkpoint,
            SourceSample source
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceSessionId", checkpoint.getSessionId());
        result.put("sourceStartMs", source.startMs);
        result.put("sourceEndMs", source.endMs);
        result.put("inputChars", source.text.length());
        return result;
    }

    private String statusName(int status) {
        if (status == FeatureStatus.UNAVAILABLE) return "UNAVAILABLE";
        if (status == FeatureStatus.DOWNLOADABLE) return "DOWNLOADABLE";
        if (status == FeatureStatus.DOWNLOADING) return "DOWNLOADING";
        if (status == FeatureStatus.AVAILABLE) return "AVAILABLE";
        return "UNKNOWN_" + status;
    }

    private void writeResult(Context context, Map<String, Object> values) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        AtomicFile atomic = new AtomicFile(new File(context.getFilesDir(), RESULT_FILE));
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) atomic.failWrite(stream);
            throw error;
        }
    }

    private static final class SourceSample {
        final String text;
        final long startMs;
        final long endMs;

        SourceSample(String text, long startMs, long endMs) {
            this.text = text;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }

    private static final String RESULT_FILE = "summary_probe_result.json";
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;
    private static final int TARGET_SAMPLE_CHARS = 3_000;
    private static final int MIN_SAMPLE_CHARS = 400;
}
