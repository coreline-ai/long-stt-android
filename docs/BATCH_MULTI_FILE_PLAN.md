# 다중 파일 배치 전사 — 구현 계획 & 검증 체크리스트

## 목표

사용자가 여러 오디오 파일을 한 번에 선택하고, **순차 자동 전사**(발열 대응 휴식 포함) 후 통합 결과(히스토리/CSV)를 확인한다.

## 아키텍처 (2단계 루프)

```
다중 파일 선택 (List<Uri>)
    ↓
파일별 복사 → filesDir  (audioPaths)
    ↓
┌─ 파일 1: transcribeSmart → (10분 초과 시 청크 분할) → CSV
├─ … 파일 간 10초 냉각 …
└─ 파일 N: transcribeSmart → CSV
    ↓
전체 완료 → 히스토리 갱신
```

## 구현 상태 (2026-08-06)

| 항목 | 상태 | 위치 |
|------|------|------|
| UiState: audioPaths / index / total / batchStatus / batchProgress | ✅ | `SttViewModel.kt` |
| copyAudioFromUris (병합 선택) | ✅ | `SttViewModel.kt` |
| runBatchBenchmark + 10초 냉각 | ✅ | `SttViewModel.kt` |
| 파일별 CSV 저장 (note=`배치i/N`) | ✅ | `BenchmarkRecorder.appendResult` |
| GetMultipleContents | ✅ | `SttBenchmarkScreen.kt` |
| AudioSelectionCard 목록·크기·× 제거 | ✅ | `SttBenchmarkScreen.kt` |
| RunCard 이중 진행률 + "파일 x/y 전사 중 z%" | ✅ | `SttBenchmarkScreen.kt` |
| 히스토리 갱신 | ✅ | batch 완료 시 `loadHistory()` |

## 성공 기준

| # | 기준 | 구현 |
|---|------|------|
| 1 | 다중 파일 선택 (3개 이상) | ✅ GetMultipleContents + 목록 UI |
| 2 | 순차 자동 전사 (파일 내 10분 분할 포함) | ✅ `transcribeSmart` |
| 3 | 진행률: "파일 2/3 전사 중 45%" | ✅ RunCard statusText |
| 4 | 파일 간 발열 대기 | ✅ 10초 + "냉각 대기 중..." |
| 5 | 각 파일 결과 CSV 개별 저장 | ✅ appendResult per file |
| 6 | 히스토리에서 모든 결과 확인 | ✅ loadHistory + HistoryCard |

## 수동 실기기 확인 순서

1. 모델 로드 (`ggml-base.bin` 등)
2. **오디오 선택** → 3개 이상 WAV 선택
3. **STT 실행** → 배치 루프 시작
4. UI: 전체 바 / 현재 파일 바 / 상태 문구 확인
5. 파일 전환 시 "냉각 대기 중... (10초)" 확인
6. 완료 후 히스토리에 `배치1/N` … note 행 확인
7. CSV: `adb shell run-as com.stt.benchmark cat files/stt_benchmark_results.csv`
