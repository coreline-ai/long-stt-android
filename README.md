# AndroidSttBenchmark

안드로이드 온디바이스 STT(Speech-to-Text) 성능 벤치마크 앱.
whisper.cpp를 활용해 로컬 기기에서 한국어 오디오 전사 속도·정확도를 측정합니다.

## 목적

| 항목 | 측정 가능 여부 |
|---|---|
| 처리 속도 (RTF, Real-Time Factor) | ✅ |
| 오디오 길이 대비 처리 시간 | ✅ |
| 디바이스별 성능 비교 | ✅ |
| 정확도 (WER/CER) | ⚠️ Ground truth 필요 |
| 배터리 소모 | ⚠️ 추가 구현 필요 |
| 화자 분리 | ❌ (별도 엔진 필요) |

## 프로젝트 구조

```
AndroidSttBenchmark/
├── app/
│   ├── build.gradle.kts              # 모듈 빌드 설정
│   ├── libs/                          # whisper.aar 배치 위치
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/                    # (옵션) 모델 파일
│       └── java/com/stt/benchmark/
│           ├── SttApp.kt              # Application
│           ├── MainActivity.kt        # 진입점
│           ├── data/
│           │   └── BenchmarkRecorder.kt   # 결과 기록/CSV 내보내기
│           ├── ui/
│           │   ├── SttViewModel.kt     # 상태 관리
│           │   ├── SttBenchmarkScreen.kt  # Compose UI
│           │   └── theme/Theme.kt
│           └── whisper/
│               └── WhisperEngine.kt    # Whisper 인터페이스 + 스텁 구현
├── docs/
│   └── BUILD_WHISPER.md              # whisper.cpp 빌드/연동 가이드 ★
├── scripts/
│   ├── prepare_audio.sh              # 오디오 16kHz 변환
│   └── push_to_device.sh             # 기기로 파일 전송
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## 빠른 시작

### 1. 빌드 환경 준비

```bash
# Android Studio 설치 (또는 커맨드라인 SDK)
# - Android Studio Meerkat 이상
# - NDK r25c 이상
# - JDK 17 (Temurin 권장)
```

### 2. whisper.cpp 네이티브 라이브러리 준비

**[중요]** `docs/BUILD_WHISPER.md` 참조하여 다음 중 하나 수행:
- pre-built AAR을 `app/libs/whisper.aar`로 배치
- 또는 직접 빌드하여 `.so` 파일을 `jniLibs/`에 배치

### 3. 모델 및 오디오 파일 준비

```bash
# 스크립트 실행
cd scripts
./prepare_audio.sh /path/to/input.m4a   # 16kHz mono 변환
./push_to_device.sh                      # adb로 기기 전송
```

### 4. 빌드 및 실행

```bash
# Android Studio에서 프로젝트 열기 → Run
# 또는 커맨드라인
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 5. 앱에서 벤치마크 실행

1. **모델 경로 입력**: `/sdcard/Download/ggml-base-q5_1.bin`
2. **오디오 선택**: `/sdcard/Download/audio_16k.wav`
3. **STT 실행** 클릭
4. 결과 확인: RTF, 처리시간, 속도 배수

## 예상 성능 (Snapdragon 8 Gen 3, 추정치)

| 모델 | 크기 | 1시간 오디오 처리 | RTF |
|---|---|---|---|
| tiny (q5) | 75MB | 3-5분 | ~0.08 |
| base (q5) | 75MB | 9-15분 | ~0.25 |
| small (q5) | 500MB | 25-40분 | ~0.6 |

## 측정 결과 활용

결과는 자동으로 CSV로 저장됩니다:
- 위치: 앱 내부 저장소 `/data/data/com.stt.benchmark/files/stt_benchmark_results.csv`
- 컬럼: timestamp, device, engine, model, audio_duration, elapsed_ms, rtf, speed_multiplier 등

```bash
# 결과 추출
adb shell run-as com.stt.benchmark cat files/stt_benchmark_results.csv > results.csv
```

## 실험 계획 (누락 청크 · 모델 비교)

상세: [`docs/EXPERIMENT_PLAN.md`](docs/EXPERIMENT_PLAN.md)

```bash
# E1: 누락 9청크 재전사 (dry-run → 실제)
./scripts/rerun_missing_chunks.sh --dry-run
./scripts/rerun_missing_chunks.sh --serial R3CY40PXCAP --wait-sec 90

# E2: base / base-q5 / small-q5 × 샘플 3개
./scripts/run_model_compare.sh --dry-run
./scripts/run_model_compare.sh --serial R3CY40PXCAP --wait-sec 120
```

- 10분 초과 WAV는 앱이 자동 배치 전사(10분 청크, 청크마다 모델 재로드)
- adb 자동 실행: `am start ... --es auto_model ... --es auto_audio ... --es auto_note ...`

## 기술 스택

- **언어**: Kotlin 1.9
- **UI**: Jetpack Compose + Material3
- **STT 엔진**: whisper.cpp (C++ 네이티브)
- **최소 SDK**: API 26 (Android 8.0)
- **타겟 SDK**: API 34 (Android 14)
- **16KB page size**: AGP 8.5.2 + NDK r28 + CMake max-page-size (상세: [`docs/ANDROID_16KB_PAGE_SIZE.md`](docs/ANDROID_16KB_PAGE_SIZE.md))
- **M4A/MP3 장시간 STT 계획**: [`docs/ONDEVICE_M4A_MP3_STT_PLAN.md`](docs/ONDEVICE_M4A_MP3_STT_PLAN.md)

## 라이선스

MIT License. whisper.cpp 자체은 MIT 라이선스를 따릅니다.
