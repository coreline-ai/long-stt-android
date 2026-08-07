# 개발 계획서: 안드로이드 온디바이스 M4A/MP3 장시간 STT

| 항목 | 내용 |
|------|------|
| 문서 ID | `PLAN-STT-COMPRESSED-AUDIO-001` |
| 버전 | 1.1 |
| 작성일 | 2026-08-06 |
| 대상 프로젝트 | `AndroidSttBenchmark` (`com.stt.benchmark`) |
| 엔진 | whisper.cpp (JNI, 16 kHz mono float32 PCM) |
| 상태 | **P0+P1 구현 완료 / 스모크 통과** |

---

## 1. 배경 및 문제 정의

### 1.1 현재 동작

| 경로 | 조건 | 지원 포맷 | 구현 |
|------|------|-----------|------|
| 단일 전사 | 길이 ≤ 약 10분 | MediaCodec이 디코딩 가능한 포맷 (m4a/mp3/wav 등) | `AudioDecoder.decodeToFloatArray` |
| 장시간 배치 | 길이 > 약 10분 | **WAV만** | `AudioDecoder.readWavRange` |
| 다중 파일 배치 | N개 파일 순차 | 위 규칙 파일별 적용 | `runBatchBenchmark` |

장시간 경로 핵심 제약 (`SttViewModel.transcribeSmart`):

```text
if (!path.endsWith(".wav")) throw "10분 초과 배치는 16kHz mono WAV만 지원"
```

### 1.2 문제

1. **M4A/MP3 장시간**(예: `4인AI대화.mp3` ≈ 6시간 14분)을 앱에서 직접 전사할 수 없음.
2. 짧은 파일이라도 **전체 디코드 → 전체 float 배열** 방식은 1시간급에서 힙 ~220MB, 6시간급 ~1.4GB로 **OOM 위험**.
3. 호스트 `ffmpeg` 전처리에 의존하면 온디바이스 UX·재현성이 떨어짐.

### 1.3 목표

안드로이드 기기만으로 **M4A·MP3·WAV**를 안정적으로 전사하되:

- 장시간(수 시간)에도 **OOM 없이** 완주
- 기존 WAV 배치·다중 파일 배치 회귀 없음
- 가능하면 **추가 디스크 변환 없이** 원본 1회 복사만으로 처리

### 1.4 비목표 (이번 계획 범위 밖)

- 실시간 마이크 스트리밍 STT
- 화자 분리(diarization)
- 클라우드 STT
- ffmpeg 네이티브 번들 (1차 해법 아님)
- WER/CER 자동 평가 파이프라인 완비

---

## 2. 요구사항

### 2.1 기능 요구사항 (FR)

| ID | 요구사항 | 우선순위 |
|----|----------|----------|
| FR-1 | M4A 파일 전사 (≤10분, >10분 모두) | P0 |
| FR-2 | MP3 파일 전사 (≤10분, >10분 모두) | P0 |
| FR-3 | 기존 WAV 장시간 배치 동작 유지 | P0 |
| FR-4 | 장시간 파일을 고정 창(기본 10분) 단위로 분할 전사 | P0 |
| FR-5 | 청크마다 PCM 버퍼 해제 (피크 메모리 제한) | P0 |
| FR-6 | 다중 파일 배치와 조합 가능 (파일 루프 × 시간 청크 루프) | P0 |
| FR-7 | 청크 경계 overlap 및 단순 병합(선택 적용) | P1 |
| FR-8 | 실패 청크 스킵/재시도 및 로그 | P1 |
| FR-9 | 옵션: 1회 WAV 캐시 생성 후 기존 WAV 경로 사용 | P2 |
| FR-10 | 장시간 전사 Foreground Service + 알림 | P2 |

### 2.2 비기능 요구사항 (NFR)

| ID | 요구사항 | 목표 |
|----|----------|------|
| NFR-1 | 장시간 전사 피크 Java 힙 | **&lt; 150 MB** (가이드) |
| NFR-2 | 6시간급 base 모델 완주 | S25급, 크래시 0 |
| NFR-3 | RTF (base, S25급) | 청크 평균 **≤ 0.15** (실측 기준 조정) |
| NFR-4 | 16KB page size 호환 | 기존 정렬 유지 |
| NFR-5 | minSdk | 26 유지 |
| NFR-6 | 회귀 | 기존 chunk WAV 배치·스모크 통과 |

### 2.3 제약

- 엔진 입력: **16 kHz, mono, float32**
- 동일 whisper ctx 연속 `fullTranscribe` 크래시 이슈 → 당분간 **청크마다 모델 재로드** 유지 가능
- `/data` 여유·발열·백그라운드 킬은 기기/OS 정책에 종속

---

## 3. 현재 아키텍처 vs 목표 아키텍처

### 3.1 현재 (문제)

```text
파일
 ├─ ≤10분 ── MediaCodec 전체 디코드 ── FloatArray(전체) ── whisper
 └─ >10분 ── WAV? ── readWavRange ── whisper
              └─ 그 외 ── throw
```

### 3.2 목표 (2단계 루프 유지 + 디코드 추상화)

```text
파일 (wav | m4a | mp3 | …)
    │
    ▼
duration 조회 (MediaMetadataRetriever)
    │
    ├─ ≤ 10분+ε
    │     └─ decodeFull (기존, 스트리밍 개선 권장)
    │           └─ whisper.transcribePcm
    │
    └─ > 10분+ε
          for window in [0, duration) step chunkMs:
                pcm = decodeWindow(path, start, len)   // 포맷별 구현
                result += whisper.transcribePcm(pcm, offset)
                (optional) model reload
                release pcm
          return merge(results)
```

### 3.3 디코드 추상화

```kotlin
interface AudioWindowDecoder {
    /** [startMs, startMs+durationMs) 구간을 16k mono float32 로 반환 */
    fun decodeWindow(path: String, startMs: Long, durationMs: Long): FloatArray
}

// 구현체
WavWindowDecoder      // 기존 readWavRange 래핑
MediaCodecWindowDecoder // M4A/MP3/AAC 등 신규
```

선택 로직:

```text
if (isPcmWav(path)) WavWindowDecoder
else MediaCodecWindowDecoder
```

확장자만이 아니라 `MediaExtractor` MIME / WAV 헤더로 판별 권장.

---

## 4. 선정 방안 및 근거

### 4.1 1순위: MediaCodec 구간 디코드 (온디바이스, 디스크 최소)

| 항목 | 내용 |
|------|------|
| 방법 | `MediaExtractor.seekTo` + `MediaCodec`으로 시간 창만 디코드 → PCM → whisper |
| 디스크 | 원본 외 추가 대용량 파일 불필요 |
| 메모리 | 창당 ~37MB (10분 f32 mono) |
| 근거 | Android 표준 디코더, AAC/MP3 HW 가속, 라이선스·.so 부담 없음 |

### 4.2 2순위 폴백: 1회 16k mono WAV 캐시

| 항목 | 내용 |
|------|------|
| 방법 | 스트리밍 디코드하며 filesDir에 WAV 기록 → 기존 `readWavRange` 배치 |
| 디스크 | 6시간 ≈ +700MB |
| 사용 시점 | seek 불안정 기기, 동일 파일 다중 재전사 |

### 4.3 비선정

| 방안 | 이유 |
|------|------|
| 6시간 통째 `decodeToFloatArray` | OOM |
| ffmpeg 네이티브 1차 도입 | 용량·16KB·유지비 대비 이득 부족 |
| 호스트 전처리 only | 온디바이스 목표와 불일치 (임시 수단으로는 유지) |

---

## 5. 상세 설계

### 5.1 `MediaCodecWindowDecoder` 시퀀스

```text
1. MediaExtractor.setDataSource(path)
2. select audio track, read sampleRate/channelCount/mime
3. create decoder, configure, start
4. extractor.seekTo(startUs, SEEK_TO_PREVIOUS_SYNC)
5. loop:
     - feed input until EOS or sampleTime > endUs + margin
     - drain output PCM
     - drop frames with pts < startUs
     - stop collecting when pts >= endUs
6. stop/release codec & extractor
7. interleaved short → mono short → float32
8. resample to 16k if needed (chunk-local)
9. return FloatArray
```

### 5.2 파라미터 (초기값)

| 파라미터 | 초기값 | 비고 |
|----------|--------|------|
| `CHUNK_DURATION_MS` | 10 * 60 * 1000 | 기존과 동일 |
| `CHUNK_OVERLAP_MS` | 500~1000 (P1) | 경계 품질 |
| `SEEK_DROP_MARGIN_MS` | seek 동기 프레임 보정 | 구현 시 튜닝 |
| `NUM_THREADS` | min(8, cores) | 기존 8 고정 → 개선 여지 |
| 파일 간 쿨다운 | 10s | 다중 파일 배치 유지 |
| 시간 청크 간 쿨다운 | 10s (매 청크) + 30s (5청크마다) | 2026-08-07 적용 — 장시간 연속 쓰로틀 완화 |

### 5.3 경계 품질 (P1)

- 창을 `[start - overlap, end)` 로 디코드·전사
- 오프셋은 논리 `start` 기준 세그먼트 타임스탬프 보정
- 인접 청크 경계 세그먼트 텍스트 단순 중복 제거(옵션)

### 5.4 모델 컨텍스트

- 당분간 **청크마다 `loadModel` 재호출** 유지 (안정성)
- 후속 과제: ctx 재사용 크래시 원인 제거 시 RTF 개선

### 5.5 에러 처리

| 상황 | 동작 |
|------|------|
| 트랙 없음 / 디코더 생성 실패 | 파일 실패, 배치면 다음 파일 |
| 빈 PCM 청크 | 1회 재시도 후 스킵·로그 |
| whisper 실패 | 청크 실패 기록, 계속/중단 정책 플래그 |
| duration 0 | 단일 경로 시도; 실패 시 에러 |

### 5.6 영향 파일 (예상)

| 파일 | 변경 |
|------|------|
| `whisper/AudioDecoder.kt` | `decodeWindow` / MediaCodec range API |
| `ui/SttViewModel.kt` | `transcribeSmart` non-WAV 장시간 분기 |
| `whisper/WhisperEngine.kt` | 필요 시 인터페이스 정리만 |
| `ui/SttBenchmarkScreen.kt` | 에러 메시지·진행 문구 보강 |
| `AndroidManifest.xml` | P2 FGS 시 Service 등록 |
| `docs/` | 본 계획, 테스트 결과 |

---

## 6. 구현 페이즈 및 일정

| Phase | 내용 | 완료 기준 | 예상 |
|-------|------|-----------|------|
| **P0** | `decodeWindow` MediaCodec 구현 + 단위 스모크 (15분 m4a/mp3) | 15분 파일 2청크 성공, 힙 안정 | 1일 |
| **P1** | `transcribeSmart` 연동, WAV/압축 분기, 회귀 테스트 | 6시간 mp3 샘플 완주 또는 1시간 이상 안정 | 1일 |
| **P2** | overlap·경계 병합, 실패 청크 재시도 | 경계 품질 주관 개선, 실패율 감소 | 0.5–1일 |
| **P3** | WAV 캐시 옵션 (설정/플래그) | 재전사 시 캐시 히트 | 0.5일 |
| **P4** | Foreground Service + 알림 + (선택) 재개 | 화면 꺼도 장시간 유지 | 1일 |

**권장 1차 릴리스 스코프: P0 + P1**  
(P2–P4는 안정화 스프린트)

---

## 7. 작업 분해 (WBS)

### P0

1. `AudioDecoder.decodeWindow(path, startMs, durationMs): FloatArray` 추가  
2. seek + drop + end 조건 구현  
3. mono/resample 청크 로컬 적용  
4. 로그: mime, sr, ch, out samples, elapsed decode ms  
5. 기기 스모크: 짧은 m4a, 15분 m4a/mp3  

### P1

1. `transcribeSmart`에서 `isWav` 분기 → `decodeWindow`  
2. 에러 메시지 UX (포맷/길이/실패 청크)  
3. 회귀: 기존 `chunk_*.wav` 단일·배치  
4. 장시간 mp3 (`4인AI대화.mp3`) 부분(1h) → 전체  

### P2–P4

- overlap 파라미터  
- CSV note에 `chunkIndex`/`format`  
- FGS `TranscriptionService`  
- 설정: 청크 길이, 쿨다운, WAV 캐시 on/off  

---

## 8. 테스트 계획

### 8.1 기능 매트릭스

| ID | 케이스 | 기대 |
|----|--------|------|
| T1 | wav ≤10분 | 성공 (기존) |
| T2 | m4a ≤10분 | 성공 |
| T3 | mp3 ≤10분 | 성공 |
| T4 | wav 1시간 | 6청크 배치 성공 |
| T5 | m4a 15분+ | ≥2청크 성공 |
| T6 | mp3 6h | OOM 없이 진행(최소 1h, 목표 전체) |
| T7 | 다중 파일 배치 (wav+mp3 혼합) | 순차 성공 |
| T8 | 잘못된 파일/빈 오디오 | 명확한 에러, 크래시 없음 |

### 8.2 성능·리소스

- `adb shell dumpsys meminfo com.stt.benchmark` 피크  
- 청크별 RTF·elapsed 로그  
- thermal throttling 시 RTF 악화 기록  

### 8.3 기기

| 우선 | 기기 | 비고 |
|------|------|------|
| P0 | SM-S931N (실보유) | Android 16 |
| P1 | 가능 시 1대 추가 (API 26–33) | seek 호환 |

### 8.4 합격 기준 (P0+P1 머지)

- [ ] T1–T5, T7–T8 통과  
- [ ] T6 최소 1시간 연속 통과, 가능 시 전체  
- [ ] 피크 힙 가이드 충족 또는 문서화된 예외  
- [ ] 16KB zip/ELF 회귀 없음 (`zipalign -c -P 16`)  

---

## 9. 리스크 및 대응

| 리스크 | 영향 | 대응 |
|--------|------|------|
| M4A/MP3 seek 부정확 | 구간 누락/중복 | PREVIOUS_SYNC + pts drop; 폴백 WAV 캐시 |
| VBR MP3 타임스탬프 | 경계 오차 | overlap; 필요 시 캐시 |
| MediaCodec 기기 편차 | 특정 기기 실패 | 에러 로그 MIME/기기 모델; 이슈 트래킹 |
| 모델 재로드 오버헤드 | 총시간 증가 | 안정화 후 ctx 재사용 과제 |
| 백그라운드 킬 | 장시간 실패 | P4 FGS;  interim 충전·화면 ON 가이드 |
| UTF-8 세그먼트 크래시 | abort | 기존 `safe_new_string_utf` 유지 |
| 내부 저장 부족 | 캐시/복사 실패 | 사전 용량 체크, 캐시 옵션 기본 off |

---

## 10. 운영·사용 가이드 (P1 이후)

1. 모델 로드 (base 권장 시작)  
2. m4a/mp3/wav 선택 (다중 가능)  
3. STT 실행 → 장시간은 자동 시간 분할  
4. 히스토리/CSV 확인  
5. 실패 청크만 재실행 (P2 note 기반)

호스트 전처리는 **옵션**으로 유지:

```bash
# 비상/대량 전처리용 (기존)
ffmpeg -i input.m4a -ar 16000 -ac 1 -c:a pcm_s16le out_16k.wav
```

---

## 11. 성공 정의 (프로젝트 관점)

| 목표 | 달성 조건 |
|------|-----------|
| 포맷 | M4A·MP3·WAV 온디바이스 전사 |
| 장시간 | 6시간급 압축 오디오 OOM 없이 처리 가능 |
| UX | PC 변환 없이도 앱 선택만으로 실행 가능 |
| 품질 | 기존 WAV 경로 대비 현저한 회귀 없음 |
| 유지보수 | MediaCodec 표준 API, 추가 네이티브 디코더 없음 (P0–P1) |

---

## 12. 의사결정 로그

| 일자 | 결정 | 사유 |
|------|------|------|
| 2026-08-06 | 1순위 = MediaCodec 구간 디코드 | 디스크·메모리·표준 API 균형 |
| 2026-08-06 | WAV 캐시 = P3 폴백 | 1차 복잡도 축소 |
| 2026-08-06 | ffmpeg native 비선정 | 벤치앱 대비 과한 의존성 |
| 2026-08-06 | 청크 10분 유지 | 기존 실측·메모리 검증과 일치 |

---

## 13. 구현 완료 기록 (2026-08-06)

### P0+P1 구현

| 항목 | 내용 |
|------|------|
| `AudioDecoder.decodeWindow` | WAV → `readWavRange`, 그 외 → MediaCodec seek 구간 디코드 |
| `transcribeSmart` | 10분 초과 시 포맷 무관 시간 청크 (m4a/mp3/wav) |
| 실패 청크 | 디코드 실패 시 스킵 후 계속, 전부 실패 시 throw |

### 스모크 (SM-S931N)

| 항목 | 값 |
|------|-----|
| 입력 | `smoke_12min.mp3` (12분, 원본 앞부분) |
| 경로 | 시간 청크 2개 (10분 + 2분) |
| format | mp3 (`audio/mpeg` → PCM) |
| elapsed | 41814 ms |
| RTF | **0.058** |
| segs / chars | 354 / ~4991 |
| failedChunks | 0 |
| note | `smoke_mp3_12min_window_decode` |

### 다음 액션 (선택)

1. P2: overlap·경계 병합  
2. `4인AI대화.mp3` 전체(6h) 장시간 런  
3. P3 WAV 캐시 / P4 FGS  


---

## 14. 참고

- 앱 경로: `app/src/main/java/com/stt/benchmark/whisper/AudioDecoder.kt`  
- 배치: `SttViewModel.transcribeSmart` / `runBatchBenchmark`  
- 실험: `docs/EXPERIMENT_PLAN.md`  
- 다중 파일: `docs/BATCH_MULTI_FILE_PLAN.md`  
- 16KB: `docs/ANDROID_16KB_PAGE_SIZE.md`  
- Android MediaCodec / MediaExtractor 공식 문서  

---

**문서 끝.**
