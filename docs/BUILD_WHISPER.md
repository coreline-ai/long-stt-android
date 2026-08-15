# whisper.cpp Android 빌드 가이드

## 현재 빌드 방식

앱은 pre-built AAR이나 개인 `/tmp` 디렉터리를 사용하지 않는다. `scripts/setup_whisper.sh`가 lock 파일의 commit을 `third_party/whisper.cpp/`에 준비하고, 앱 CMake가 그 소스를 직접 빌드한다.

| 항목 | 값 |
|---|---|
| upstream | `https://github.com/ggerganov/whisper.cpp.git` |
| lock | `third_party/whisper.cpp.lock` |
| commit | `8631825d41a2712268813981a9550b04a3f225e5` |
| upstream version | `1.9.2` |
| ABI | `arm64-v8a` |
| NDK | `28.2.13676358` |
| CMake | `3.22.1` |
| Android SDK | compile/target 36, min 26 |
| AGP / Gradle | `8.10.1` / `8.11.1` |

이 commit은 현재 fresh-clone·Release·16KB 검증의 고정 빌드 기준선이다. 2026-08-07 이전 `/tmp/whisper.cpp` 기반 APK와는 바이너리 hash가 다르므로 그 과거 APK와 동일한 artifact라고 주장하지 않는다. 현재 기능·사용자/실장비 검증 상태는 [`VERIFICATION_COMPLETION_20260814.md`](VERIFICATION_COMPLETION_20260814.md)를 따른다.

## 사전 요구사항

- JDK 17. Android Studio JBR 사용 가능
- Android SDK 36
- Android NDK `28.2.13676358`
- CMake `3.22.1`
- Git

macOS에서 Android Studio JBR를 사용하는 예:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

## fresh clone 준비

```bash
git clone https://github.com/coreline-ai/long-stt-android.git
cd long-stt-android
./scripts/setup_whisper.sh
./scripts/setup_whisper.sh --verify-only
```

`third_party/whisper.cpp/`는 Git 추적 대상이 아니다. 대신 아래 두 파일이 재현성을 보장한다.

- `third_party/whisper.cpp.lock`: remote, 40자리 commit, upstream version
- `scripts/setup_whisper.sh`: exact commit checkout 및 tracked-source 변경 검사

소스가 없거나 commit이 다르면 CMake configure 단계에서 즉시 실패한다. 임의의 다른 소스를 사용하려면 빌드 명령에서 `-DWHISPER_CPP_DIR=/absolute/path`를 명시할 수 있지만 release provenance로 인정하지 않는다.

## 빌드

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease :app:bundleRelease :app:verify16KbAlignment
```

산출물:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Android test: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Release unsigned APK: `app/build/outputs/apk/release/app-release-unsigned.apk`
- Release unsigned AAB: `app/build/outputs/bundle/release/app-release.aab`

위 Release 파일은 자동 품질 검증용 unsigned 산출물이다. 실제 배포용 signed AAB는 외부 keystore를 주입한 뒤 `./gradlew :app:productionReleaseBundle`로 만들며, 자세한 절차는 [`PRODUCTION_SIGNING.md`](PRODUCTION_SIGNING.md)를 따른다.

JNI의 `WhisperLib.getSystemInfo()`는 upstream version과 고정 commit을 포함한다. 런타임 보고서에서 실제 빌드 provenance를 확인할 때 사용한다.

## 16KB 페이지 검증

```bash
ZIPALIGN="$ANDROID_HOME/build-tools/36.0.0/zipalign"
"$ZIPALIGN" -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
"$ZIPALIGN" -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
```

ELF 검증은 `llvm-readelf -l`의 모든 LOAD alignment가 `0x4000` 이상인지 확인한다. 자세한 절차는 `docs/ANDROID_16KB_PAGE_SIZE.md`를 따른다.

## 모델 파일

whisper.cpp source와 모델 artifact는 별도다. 앱은 모델을 `files/models/`에 다운로드하며 모델 파일은 Git에 포함하지 않는다.

제품 앱은 mutable `main` URL을 사용하지 않는다. `ModelDownloader`가 revision `5359861c739e955e79d9a303bcbc70fb988958b1`에 고정된 URL, 정확한 byte size와 SHA-256, HTTPS 허용 host, 제한된 manual redirect를 확인한 뒤에만 `.part` 파일을 완료 모델로 승격한다.

예를 들어 `ggml-base-q5_1.bin` 정본은 다음과 같다.

| 항목 | 값 |
|---|---|
| byte size | `59,707,625` |
| SHA-256 | `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898` |

수동 다운로드 파일은 위 값을 독립 검증하지 않으면 제품 catalog 모델로 간주하지 않는다.

## commit 변경 절차

1. `third_party/whisper.cpp.lock`의 commit/version을 변경한다.
2. 새 source directory에서 `scripts/setup_whisper.sh --verify-only`를 통과시킨다.
3. Debug/Release, JVM/계측 테스트를 통과시킨다.
4. APK와 native `.so`의 SHA-256을 기록한다.
5. ZIP/ELF 16KB alignment를 검증한다.
6. 짧은 WAV/M4A/MP3 native smoke test를 수행한다.
7. checkpoint 백업 후 Samsung 장시간 중단·재개 테스트를 수행한다.

local patch가 필요하면 patch 파일과 적용 이유를 저장소에 포함하고 lock/provenance 문서에서 참조한다.

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `Pinned whisper.cpp source is missing` | setup 미실행 | `./scripts/setup_whisper.sh` |
| `commit 불일치` | source directory가 다른 revision | 디렉터리를 보존·이동한 뒤 setup 재실행 |
| tracked source 변경 오류 | third-party source 직접 수정 | patch를 정식 파일로 기록하거나 변경 복원 |
| Java home 오류 | 존재하지 않는 `JAVA_HOME` | Android Studio JBR 또는 JDK 17 설정 |
| SDK 위치 오류 | `ANDROID_HOME`/`local.properties` 없음 | Android SDK 경로 설정 |
| `UnsatisfiedLinkError` | native build/packaging 실패 | APK의 `lib/arm64-v8a`와 CMake 로그 확인 |
| 16KB 실패 | linker/packaging 정렬 누락 | NDK r28, AGP 8.10.1, Gradle 8.11.1, CMake linker flag 확인 |

## Provenance 주의

- 기존 Samsung 설치 APK SHA-256: `4d41a2e2db264c94de32436c6c4b5f62f4ebc14493ebb14d9541d49bf29bb09f`
- 기존 APK의 native source 경로 문자열은 `/tmp/whisper.cpp`였지만 commit 정보는 포함하지 않았다.
- 현재 lock commit으로 만든 `.so`와 설치 APK의 `.so`는 hash가 다르므로 binary parity로 간주하지 않는다.
- 완료된 6시간 STT checkpoint를 native source 자격 검증 과정에서 수정하지 않는다.
