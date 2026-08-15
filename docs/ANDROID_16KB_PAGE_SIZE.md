# Android 16KB Page Size 호환 가이드

- 기준: [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) (2026-08 문서)
- 대상 프로젝트: `AndroidSttBenchmark` (whisper.cpp 네이티브 포함)

---

## 1. 무엇이 문제인가

| 구분 | 내용 |
|------|------|
| 배경 | 일부 Android 15+ 기기가 **16KB 메모리 페이지** 사용 (기존 4KB) |
| Play 정책 | targetSdk 35+ 앱/업데이트는 64-bit 네이티브 라이브러리가 16KB 지원 필요 |
| 마감 | Play Console 경고 기준 (문서 갱신에 따라 **2027-02-01** 등) |
| 영향 | `.so` 가 있는 앱(본 프로젝트: whisper/ggml/omp/c++) |

두 가지 정렬이 **둘 다** 맞아야 함:

1. **ELF LOAD segment alignment** — 바이너리 내부 (`align ≥ 2**14` = 16384)
2. **ZIP alignment in APK** — 비압축 `.so` 가 APK 안에서 **16KB 경계**에 위치

---

## 2. 본 프로젝트 진단 결과 (수정 전)

### 2.1 ELF — 이미 통과

`llvm-readelf -l *.so` → 모든 LOAD `Align 0x4000` (16KB)

| 라이브러리 | ELF Align |
|------------|-----------|
| libwhisper.so | 0x4000 ✅ |
| libggml*.so | 0x4000 ✅ |
| libc++_shared.so | 0x4000 ✅ |
| libomp.so | 0x4000 ✅ |

이유: `CMakeLists.txt` 에 이미

```cmake
-Wl,-z,max-page-size=16384
-Wl,-z,common-page-size=16384
```

### 2.2 ZIP (APK packaging) — 실패였음

```bash
zipalign -c -P 16 -v 4 app-debug.apk
```

| .so | 결과 |
|-----|------|
| libc++_shared.so | OK |
| libggml-base.so | OK |
| libggml.so | OK |
| **libomp.so** | **BAD - 8192** |
| **libggml-cpu.so** | **BAD - 8192** |
| **libwhisper.so** | **BAD - 8192** |

원인:

- `useLegacyPackaging = false` (비압축 Stored) 는 맞음
- 그러나 **AGP 8.2.2 < 8.5.1** → 비압축 `.so` 를 16KB zip 경계로 안 맞춤

---

## 3. 해결책 (우선순위)

### ✅ 권장 A — AGP 8.5.1+ + 비압축 jniLibs (본 프로젝트 적용)

| 항목 | 값 |
|------|-----|
| AGP | **8.10.1** (API 36·16KB ZIP alignment 지원) |
| Gradle | **8.11.1** |
| NDK | **28.2.x** (ELF 16KB 기본; r27도 링커 플래그로 가능) |
| packaging | `jniLibs.useLegacyPackaging = false` |

효과:

- ELF: NDK r28 기본 16KB (+ 기존 CMake 플래그 유지해도 무방)
- ZIP: AGP 8.5.1+ 가 비압축 `.so` 16KB align

### ⚠️ 임시 B — 압축 패키징 (AGP 업그레이드 불가 시)

```kotlin
// app/build.gradle.kts
packaging {
    jniLibs {
        useLegacyPackaging = true  // .so 압축 → zip 정렬 이슈 회피
    }
}
```

- 단점: 설치 시 디스크에 추출 → 용량↑, 설치 실패 확률↑
- Google 권장: 가능하면 A 경로.

### ✅ 권장 C — CMake 링커 플래그 (NDK ≤ r27)

이미 적용됨 (`app/src/main/cpp/CMakeLists.txt`):

```cmake
target_link_options(whisper PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)
# ggml / ggml-base / ggml-cpu 에도 동일 전파
```

### ✅ D — 런타임 코드 (PAGE_SIZE 하드코딩 금지)

- `PAGE_SIZE` / `4096` 가정 금지
- `getpagesize()` 또는 `sysconf(_SC_PAGESIZE)` 사용
- whisper/ggml 쪽은 대부분 문제 없음; 커스텀 `mmap` 시 주의

### ✅ E — 서드파티 prebuilt `.so` / AAR

- 직접 빌드하지 않은 `.so` 는 공급사 16KB 빌드 필요
- 본 프로젝트: 자체 CMake 빌드 + NDK STL/OpenMP → 통제 가능

---

## 4. 검증 체크리스트

### 4.1 ELF

```bash
NDK="$ANDROID_HOME/ndk/28.2.13676358"
READELF=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf
SO=app/build/intermediates/merged_native_libs/debug/out/lib/arm64-v8a

for f in $SO/*.so; do
  echo "==== $(basename $f) ===="
  $READELF -l "$f" | grep -E "LOAD|Align"
done
# Align 이 0x4000 (또는 그 이상) 이어야 함. 0x1000 이면 실패.
```

### 4.2 ZIP (APK)

```bash
ZIPALIGN="$ANDROID_HOME/build-tools/36.0.0/zipalign"
$ZIPALIGN -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
# 마지막 줄: Verification successful
```

### 4.3 기기/에뮬레이터

```bash
adb shell getconf PAGE_SIZE   # 16KB 환경이면 16384
```

- Android Studio: **16 KB Page Size** 시스템 이미지 에뮬레이터
- 일부 Pixel: Developer option **Boot with 16KB page size**
- Samsung RTL 등

### 4.4 RELRO (선택)

```bash
$READELF -l libwhisper.so | grep RELRO   # GNU_RELRO 확인
```

---

## 5. 본 프로젝트 적용 요약

| 파일 | 변경 |
|------|------|
| `build.gradle.kts` | AGP **8.2.2 → 8.10.1** |
| `gradle-wrapper.properties` | Gradle **8.5 → 8.11.1** |
| `app/build.gradle.kts` | NDK **28.2.13676358**, packaging 주석 정리 |
| `CMakeLists.txt` | 16KB 링커 플래그 유지 (r27 백업 호환) |

재빌드:

```bash
./gradlew clean :app:assembleDebug
zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

### 검증 결과

```
zipalign -c -P 16 -v 4 app-debug.apk
→ 모든 lib/arm64-v8a/*.so (OK)
→ Verification successful

ELF LOAD Align (APK 추출 후 llvm-readelf):
→ 전 라이브러리 Align=0x4000 (16KB)
```

2026-08-15 API 36 / AGP 8.10.1 기준으로 아래 자동 gate를 다시 통과했다.

```bash
./gradlew :app:verify16KbAlignment
```

- Debug·Release APK ZIP alignment 통과
- Debug·Release arm64 ELF LOAD alignment `0x4000` 이상 통과

---

## 6. 참고

- 공식: https://developer.android.com/guide/practices/page-sizes
- Play 블로그: Prepare apps for 16 KB page size
- whisper.cpp 이슈: 자체 빌드 시 max-page-size 미적용 사례 있음 → **반드시 앱 CMake 에서 플래그 강제**
