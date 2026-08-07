# whisper.cpp 안드로이드 빌드 가이드

## 개요

이 문서는 `WhisperCppEngine.kt`의 스텁(더미) 구현을 **실제 작동하는 네이티브 whisper.cpp**로 교체하는 방법을 설명합니다.

## 사전 요구사항

| 도구 | 버전 | 비고 |
|---|---|---|
| Android NDK | r25c 이상 | C++ 빌드용 |
| CMake | 3.22+ | NDK 번들 |
| Android SDK | API 26+ | compileSdk 34 권장 |
| git | 최신 | 소스 클론 |

## 빌드 옵션 (난이도순)

### 옵션 A — Pre-built AAR 사용 (가장 쉬움, 추천)

커뮤니티에서 미리 빌드한 AAR을 직접 사용:

```bash
# 1. 안드로이드용 whisper.cpp 바인딩 다운로드
#    추천 포크: zufuliu/whisper.cpp-android 또는 lintoai/whisper.cpp
git clone https://github.com/zufuliu/whisper.cpp.git --depth 1
cd whisper.cpp

# 2. 안드로이드 빌드 스크립트 실행
cd bindings/android
./gradlew :whisper:assembleRelease

# 3. 생성된 AAR을 프로젝트 libs/로 복사
cp whisper/build/outputs/aar/whisper-release.aar \
   /Users/iriver/Documents/glm/AndroidSttBenchmark/app/libs/whisper.aar
```

### 옵션 B — 수동 CMake 빌드

```bash
# 1. whisper.cpp 소스
git clone https://github.com/ggerganov/whisper.cpp.git
cd whisper.cpp

# 2. 안드로이드용 크로스 컴파일
mkdir build-android && cd build-android

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DWHISPER_BUILD_TESTS=OFF \
  -DWHISPER_BUILD_EXAMPLES=OFF \
  -DBUILD_SHARED_LIBS=ON

make -j$(nproc)

# 3. 산출물
#    libwhisper.so, libggml.so → app/src/main/jniLibs/arm64-v8a/
```

## JNI 연동 (Kotlin)

`WhisperCppEngine.kt`의 TODO 영역을 아래와 같이 교체:

### 1단계: native 메서드 선언 추가

```kotlin
class WhisperCppEngine(...) : WhisperEngine {

    companion object {
        init {
            System.loadLibrary("whisper")   // libwhisper.so
        }
    }

    private var ctx: Long = 0L  // 네이티브 컨텍스트 포인터

    // JNI 선언
    private external fun nativeInitContext(modelPath: String): Long
    private external fun nativeFullTranscribe(
        ctx: Long,
        pcmFloats: FloatArray,
        language: String
    ): Int
    private external fun nativeGetSegmentText(ctx: Long, index: Int): String
    private external fun nativeGetSegmentCount(ctx: Long): Int
    private external fun nativeFreeContext(ctx: Long)
```

### 2단계: loadModel 구현

```kotlin
    override fun loadModel(modelPath: String): Boolean {
        ctx = nativeInitContext(modelPath)
        return ctx != 0L
    }
```

### 3단계: transcribe 구현

```kotlin
    override fun transcribe(audioPath: String, language: String): TranscriptionResult {
        // 1. 오디오 → 16kHz mono PCM 변환
        val pcm = AudioDecoder.decodeToPcm16kMono(audioPath)

        val startTime = System.currentTimeMillis()

        // 2. 네이티브 전사
        nativeFullTranscribe(ctx, pcm, language)

        val elapsedMs = System.currentTimeMillis() - startTime

        // 3. 세그먼트 수집
        val segCount = nativeGetSegmentCount(ctx)
        val segments = (0 until segCount).map { i ->
            TranscriptSegment(0, 0, nativeGetSegmentText(ctx, i))
        }
        val fullText = segments.joinToString(" ") { it.text }

        return TranscriptionResult(
            text = fullText,
            segments = segments,
            elapsedMs = elapsedMs,
            audioDurationMs = measureAudioDurationMs(audioPath),
            modelSize = "...",
            engineName = engineName
        )
    }
```

## 모델 파일 준비

```bash
# ggml 양자화 모델 다운로드 (base, 한국어 충분)
# 출처: https://huggingface.co/ggerganov/whisper.cpp

# base (권장 시작점, ~75MB)
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin

# small (정확도 ↑, ~500MB)
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin

# 안드로이드 기기로 전송
adb push ggml-base-q5_1.bin /sdcard/Download/
```

## 오디오 전처리 (16kHz mono 변환)

whisper.cpp는 **16kHz mono PCM** 입력을 요구합니다. 안드로이드에서 변환 옵션:

| 방법 | 장점 | 단점 |
|---|---|---|
| MediaExtractor + MediaCodec | 네이티브, 빠름 | 구현 복잡 |
| FFmpegMobile (library) | 간편 | APK 크기 ↑ |
| 미리 변환해서 넣기 | 가장 단순 | 유연성 ↓ |

**권장**: 테스트 단계에서는 PC에서 미리 변환:

```bash
ffmpeg -i input.m4a -ar 16000 -ac 1 -c:a pcm_s16le output_16k.wav
adb push output_16k.wav /sdcard/Download/
```

## 검증 체크리스트

- [ ] `libwhisper.so`가 `app/src/main/jniLibs/arm64-v8a/`에 존재
- [ ] 모델 `.bin` 파일이 기기에 존재 (`/sdcard/Download/`)
- [ ] 오디오 파일이 16kHz mono PCM으로 변환됨
- [ ] `System.loadLibrary("whisper")` 호출 시 UnsatisfiedLinkError 없음
- [ ] 첫 전사 실행 후 RTF 값이 정상 범위 (0.1 ~ 1.0)

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `UnsatisfiedLinkError` | .so 파일 누락 | jniLibs 폴더 확인 |
| OOM (메모리 부족) | 모델 너무 큼 | base → tiny로 다운그레이드 |
| 속도 매우 느림 | 디버그 빌드 | Release 빌드 사용 (`-DCMAKE_BUILD_TYPE=Release`) |
| 텍스트 깨짐 | 언어 미지정 | `language = "ko"` 명시 |
