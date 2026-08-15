# 실기기 16KB 호환 테스트 리포트

> [!NOTE]
> 이 문서는 2026-08-06 AGP 8.5.2 빌드의 실측 증거를 보존한다. 현재 빌드 기준은 AGP 8.10.1 / Gradle 8.11.1이며 [`ANDROID_16KB_PAGE_SIZE.md`](ANDROID_16KB_PAGE_SIZE.md)를 따른다.

- 일시: 2026-08-06
- 기기: **Samsung SM-S931N** (Galaxy S25 계열), serial `R3CY40PXCAP`
- 빌드: AGP 8.5.2 + NDK 28.2.13676358 + 16KB ELF/ZIP 정렬 APK

---

## 1. 기기 페이지 크기 상태

| 항목 | 값 |
|------|-----|
| Android | 16 (API 36) |
| `getconf PAGE_SIZE` | **4096** (4KB 커널로 부팅) |
| `ro.boot.hardware.cpu.pagesize` | 4096 |
| `ro.product.cpu.pagesize.max` | **16384** (하드웨어 상 16KB 지원) |
| `ro.boot.flash.locked` | **1** (부트로더 잠김) |
| `/data` 파일시스템 | **f2fs** |
| Settings UI | `Boot with 16 KB page size` / `Enable16kPagesPreferenceController` 존재 |

### 1.1 순수 16KB 커널 모드 전환이 막힌 이유

SecSettings 문자열 및 AOSP 가이드 기준, 이 기기에서 16KB 모드 ON 시:

1. **부트로더(OEM) 잠금 해제 필요** (`confirm_oem_unlock_for_16k_*`)
2. **전체 사용자 데이터 삭제** (wipe)
3. `/data` 를 **F2FS → EXT4** 로 재포맷

adb 로 강제 전환 불가(잠금 + wipe 대화상자).

**데이터 손실 위험** 때문에 이번 세션에서는 16KB 커널 재부팅을 실행하지 않음.

---

## 2. 수행한 검증

### 2.1 설치·패키징 (재부팅 전)

| 검사 | 결과 |
|------|------|
| `adb install -r` | Success |
| `extractNativeLibs` | false |
| `pageSizeCompat` | 0 |
| 설치본 `zipalign -c -P 16` | **Verification successful** |

### 2.2 일반 재부팅 검증 (2026-08-06 11:32)

| 단계 | 결과 |
|------|------|
| `adb reboot` | 성공, `sys.boot_completed=1` |
| 재부팅 후 `PAGE_SIZE` | 4096 (예상: 16KB 토글 미적용) |
| 앱 패키지 유지 | PASS |
| 모델/오디오 private files 유지 | PASS |
| 설치본 zip 16KB 정렬 재확인 | **PASS** |
| 앱 기동 / 네이티브 로드 | PASS (`UnsatisfiedLink` 없음) |
| STT 스모크 (`chunk_00_01`) | **PASS** |

### 2.3 재부팅 후 STT 수치

| 지표 | 값 |
|------|-----|
| note | `post_reboot_smoke_00_01` |
| RTF | **0.068** (부팅 직후 부하로 이전 0.054 대비 소폭 상승) |
| elapsed | 40719 ms |
| chars | 4546 |
| 크래시 | 없음 |

CSV: `stt_post_reboot_smoke.csv`

---

## 3. 판정 요약

| 구분 | 판정 | 비고 |
|------|------|------|
| Play/설치용 16KB APK (ELF+ZIP) | **PASS** | |
| 실기기 설치·네이티브 로드 | **PASS** | |
| 실기기 STT (4KB 부팅) | **PASS** | 재부팅 전/후 |
| 재부팅 후 앱·STT 생존 | **PASS** | |
| 실기기 STT (**16KB 커널 부팅**) | **BLOCKED** | OEM unlock + 전체 초기화 필요 |

---

## 4. 진짜 16KB 커널 검증을 하려면 (수동, 파괴적)

> ⚠️ **모든 데이터 삭제 + 부트로더 잠금 해제**. 업무 폰이면 비권장.

1. 설정 → 개발자 옵션 → **OEM 잠금 해제** ON
2. 부트로더 unlock 절차 수행 (삼성 계정/녹스 워런티 영향 가능)
3. 개발자 옵션 → **16KB 페이지 크기로 부팅** ON
4. 안내 대화상자에서 데이터 삭제 확인 → 재부팅
5. 부팅 후:

```bash
adb -s R3CY40PXCAP shell getconf PAGE_SIZE   # 16384 이어야 함
```

6. 앱 재설치 + 모델/오디오 재스테이징 + STT 스모크

대안:

- Android Studio **16KB Page Size** 시스템 이미지 에뮬레이터
- [Samsung Remote Test Lab 16KB devices](https://developer.samsung.com/remotetestlab/devices/129/16kb-page-size)

---

## 5. 결론

현재 **16KB 정렬 APK** 는 삼성 SM-S931N 실기기에서:

- 설치 가능
- 재부팅 후에도 네이티브 라이브러리 정상 로드
- whisper STT 정상 동작

까지 확인됨.

**16KB 페이지 커널 위에서의 런타임 검증**만 OEM unlock/초기화 없이는 진행 불가.
