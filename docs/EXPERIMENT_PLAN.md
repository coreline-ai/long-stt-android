# 실험 계획 — 누락 청크 보완 & 모델 비교

최종 업데이트: 2026-08-06  
기준 결과: `4인대화_STT결과_최종.csv` (ggml-base.bin, SM-S931N)

---

## 1. 목표

| ID | 목표 | 성공 기준 |
|----|------|-----------|
| **E1** | 4인 대화 10분 청크 **전체 커버** | 36/36 청크 CSV 확보 (또는 실제 존재하는 전체 청크) |
| **E2** | 동일 오디오·동일 기기에서 **모델별 RTF/품질 비교** | 최소 2개 모델 × 동일 샘플 세트 |
| **E3** | (선택) 정확도 방향성 확인 | 샘플 구간 수동 검수 또는 CER 스크립트 |

---

## 2. 현재 베이스라인 (E0)

| 항목 | 값 |
|------|-----|
| 기기 | samsung/SM-S931N, Android 16, 8 cores |
| 모델 | `ggml-base.bin` |
| 완료 청크 | **27 / 36** (예상 그리드 기준) |
| 총 오디오 | ~4.50 h |
| 총 처리 | ~22.9 min |
| RTF | min **0.060** / avg **0.085** / max **0.108** |
| 글자 수 | 121,511 |

### 2.1 완료된 청크 (27)

```
chunk_00_00, 00_02, 00_04, 00_05
chunk_01_01, 01_02, 01_03, 01_04
chunk_02_00, 02_01, 02_02, 02_04
chunk_03_00, 03_01, 03_02, 03_03, 03_04, 03_05
chunk_04_00, 04_01, 04_02, 04_03, 04_05
chunk_05_00, 05_01, 05_03, 05_05
```

### 2.2 누락 청크 (9) — E1 대상

```
chunk_00_01.wav
chunk_00_03.wav
chunk_01_00.wav
chunk_01_05.wav
chunk_02_03.wav
chunk_02_05.wav
chunk_04_04.wav
chunk_05_02.wav
chunk_05_04.wav
```

예상 추가 오디오: **~1.5 h**  
예상 처리 시간 (base, RTF≈0.09): **~8–10 min** (+ 모델 로드 오버헤드)

---

## 3. E1 — 누락 청크 재전사

### 3.1 사전 조건

1. 앱 최신 빌드 설치 (`./gradlew :app:assembleDebug`)
2. **벤치 기기**: `SM-S931N` serial `R3CY40PXCAP` (2026-08-06 기준)
3. 청크 WAV는 기기에 이미 있음: `/sdcard/Download/chunk_*.wav` (36개)
4. 재설치 후에는 앱 `filesDir` 가 비어 있으므로 **스테이징 필수**
5. USB 디버깅 + `adb devices` 확인

### 3.2 스테이징 (재설치 직후)

앱 private storage 는 `/sdcard/Download` 를 직접 못 읽을 수 있어,
모델을 포함해 필요한 파일을 `files/` 로 복사합니다.

```bash
cd scripts
chmod +x stage_to_app.sh rerun_missing_chunks.sh run_model_compare.sh

# 기기 내 cp 로 빠르게 복사 (Download → Android/data/.../files)
# 이미 Download 에 ggml-base.bin + chunk_*.wav 있음 (S931N 확인됨)
./stage_to_app.sh --serial R3CY40PXCAP --missing-only --model ggml-base.bin
```

최종 경로: **`/data/user/0/com.stt.benchmark/files/`** (private)  
`stage_to_app.sh` 가 `Download → (shell) Android/data → (run-as) private files` 2단계 복사.

> Android 15/16 에서 shell 이 쓴 `Android/data/...` 파일은 앱 `File.exists()` 가 false 가 될 수 있음.  
> run-as 는 `/sdcard/Download` 직접 cat 이 Permission denied. 그래서 2단계 필요.

### 3.3 실행

```bash
# dry-run
./rerun_missing_chunks.sh --serial R3CY40PXCAP --dry-run

# 실제 실행 (기본 경로 = private filesDir, broadcast 트리거)
./rerun_missing_chunks.sh --serial R3CY40PXCAP --wait-sec 90
```

### 3.4 스모크 검증 (2026-08-06 완료)

| 항목 | 결과 |
|------|------|
| 기기 | SM-S931N (`R3CY40PXCAP`) |
| 샘플 | `chunk_00_01.wav` (누락 청크 중 1개) |
| 모델 | `ggml-base.bin` |
| 경로 | 단일 전사 (10분+1초 여유) |
| RTF | **0.053** |
| elapsed | 31797 ms |
| chars | 4546 |
| note | `smoke_missing_chunk_00_01` |

수정된 크래시:
- 깨진 UTF-8 세그먼트 → `NewStringUTF` abort → `jni.c` `safe_new_string_utf` 로 해결
- 600004ms 가 배치로 가던 문제 → `CHUNK + 1000ms` 여유로 단일 경로 유지

스크립트는 Activity 기동 후 `am broadcast -a com.stt.benchmark.RUN_STT` 로 순차 실행합니다.

### 3.5 결과 수집

```bash
adb -s R3CY40PXCAP shell run-as com.stt.benchmark cat files/stt_benchmark_results.csv \
  > results_$(date +%Y%m%d_%H%M).csv
```

### 3.6 완료 체크리스트

- [x] 스모크 1청크 (`chunk_00_01`) 성공 (RTF 0.053)
- [ ] 나머지 누락 8개 포함 9개 전체 CSV 확보
- [ ] 각 RTF가 0.05–0.15 범위 (base 기준 이상치 점검)
- [ ] 전사 텍스트 비어 있지 않음 (`chars > 0`)
- [ ] 호스트 `4인대화_STT결과_최종.csv` 와 병합
- [ ] 통합 전사 텍스트 갱신 (`4인대화_전사텍스트.txt`)

---

## 4. E2 — 모델 비교

### 4.1 비교 세트 (권장)

동일 기기에서 **동일 샘플 3개**로 시작 (시간 절약):

| 샘플 ID | 파일 | 이유 |
|---------|------|------|
| S1 | `chunk_00_00.wav` | 오프닝, 인사/자기소개 |
| S2 | `chunk_02_00.wav` | 중간 대화 밀도 |
| S3 | `chunk_04_03.wav` | RTF가 상대적으로 높았던 구간(0.099) |

확장 시: 누락 보완 후 **전체 36청크 × 모델** (시간 많이 소요).

### 4.2 후보 모델

| 모델 | 크기(대략) | 기대 | 우선순위 |
|------|------------|------|----------|
| `ggml-base.bin` | ~74MB | 베이스라인 (이미 있음) | P0 완료 |
| `ggml-base-q5_1.bin` | ~57MB | base 대비 속도↑ 품질≈ | P1 |
| `ggml-small-q5_1.bin` | ~181MB | 품질↑ 속도↓ | P1 |
| `ggml-small.bin` | ~466MB | 상한 품질 참고 | P2 |
| `ggml-tiny` / tiny-q5 | ~39MB | 하한 속도 참고 | P2 (옵션) |

### 4.3 측정 지표

| 지표 | 수집 방법 |
|------|-----------|
| RTF, speed_multiplier | CSV 자동 |
| elapsed_ms, segments, chars | CSV 자동 |
| 주관 품질 (1–5) | 샘플 1분 구간 청취 대비 |
| 고유명사 적중 | 수기 체크리스트 (이름/제품/용어 10개) |
| 환각/반복 | 수기 (프리픽스 반복 등) |

### 4.4 실행 절차

```bash
# 1) 모델 다운로드 (앱 UI 또는 스크립트)
./scripts/download_model.sh /tmp

# 2) 기기 전송
adb push /tmp/ggml-base-q5_1.bin /sdcard/Download/
adb push /tmp/ggml-small-q5_1.bin /sdcard/Download/

# 3) 앱 filesDir로 복사 (권한에 맞게)
#    앱 내 "경로 입력" 로드 또는 run-as / content provider 경로 사용

# 4) 샘플 벤치 (모델별 note 접두사 권장)
# note 예: base_q5_S1, small_q5_S1
./scripts/run_model_compare.sh
```

### 4.5 결과 테이블 템플릿

| model | sample | audio_ms | elapsed_ms | RTF | chars | quality_1_5 | notes |
|-------|--------|----------|------------|-----|-------|-------------|-------|
| base | S1 | | | | | | |
| base-q5_1 | S1 | | | | | | |
| small-q5_1 | S1 | | | | | | |
| … | | | | | | | |

판정 가이드:

- RTF < 0.15 이면 실시간에 여유
- 품질 동률이면 **더 작은/빠른 모델 채택**
- 고유명사 오류가 비즈니스 크리티컬이면 small 계열 우선

---

## 5. E3 — 정확도 (선택, 후속)

1. 10분 중 2–3분 구간 ground truth 수기 작성  
2. PC에서 CER 계산 (예: `jiwer` 또는 자체 스크립트)  
3. 모델별 CER vs RTF 스캐터로 최종 권장 모델 결정  

현재 앱에는 WER/CER 미구현 — **호스트 사이드 분석**이 빠름.

---

## 6. 일정 제안

| 순서 | 작업 | 예상 소요 |
|------|------|-----------|
| 1 | 최신 APK 설치 | 5 min |
| 2 | E1 누락 9청크 재전사 | 15–25 min |
| 3 | 결과 pull + 커버리지 확인 | 5 min |
| 4 | E2 샘플 3 × 모델 2–3 | 30–60 min |
| 5 | 비교 표 정리 + 권장 모델 결정 | 15 min |
| 6 | (옵션) E3 CER | 반나절 |

---

## 7. 리스크

| 리스크 | 대응 |
|--------|------|
| 청크 파일이 기기에 없음 | `adb shell run-as ... ls files/` 확인 후 push |
| 장시간 실행 중 앱 킬 | `am start` 프로세스 재시작 방식 유지, wait 여유 |
| small 모델 OOM | largeHeap 이미 활성; 실패 시 q5 우선 |
| CSV 중복 행 | note에 모델명·날짜 포함, 분석 시 최신만 사용 |

---

## 8. 관련 스크립트

| 스크립트 | 용도 |
|----------|------|
| `scripts/rerun_missing_chunks.sh` | E1 누락 청크 순차 실행 |
| `scripts/run_model_compare.sh` | E2 샘플 × 모델 매트릭스 실행 |
| `scripts/prepare_audio.sh` | 원본 → 16k mono / 10분 분할 |
| `scripts/download_model.sh` | HF 모델 다운로드 |
| `scripts/push_to_device.sh` | 모델/오디오 adb push |
