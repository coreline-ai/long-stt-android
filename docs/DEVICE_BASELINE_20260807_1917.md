# DEVICE_BASELINE_20260807_1917

> 이 문서는 삭제 전 기준선이다. `2026-08-07 20:01 KST` 사용자의 명시적 승인으로 기존 앱과
> 앱 데이터를 삭제하고 최신 Debug APK를 클린 설치했다. 현재 기기에는 아래 STT 세션이 없으며,
> 기준 데이터는 Git 제외 로컬 백업에만 존재한다.

- 확인 시각: 2026-08-07 19:17 KST
- 대상 기기: Samsung SM-S931N (`R3CY40PXCAP`)
- 패키지: `com.stt.benchmark`
- 설치 버전: `1.0.0` (`versionCode=1`, `targetSdk=34`)
- 확인 방식: ADB/read-only `run-as`

## 앱 상태

- 앱 PID: `783`
- 활성 `TranscriptionService`: 없음
- top resumed activity: Samsung Launcher
- 설치 APK SHA-256: `4d41a2e2db264c94de32436c6c4b5f62f4ebc14493ebb14d9541d49bf29bb09f`
- 설치 APK 크기: `20,737,489 bytes`

## STT 세션 기준선

| 파일 | 크기 | 상태 | SHA-256 |
|---|---:|---|---|
| `stt_1786070482081_ebea6024.json` | 39,834 | `COMPLETED` | `4c62503ebe4393e4bf98f0dab5ab5b02295bc40edd7932bfd069c07957dacc4b` |
| `stt_1786070596903_7dd0f8ab.json` | 39,835 | `COMPLETED` | `c6996883d7d38de37f02e361ae208051377a6bad259d47d28d641df8e6f634fe` |
| `stt_1786079390245_83ec4015.json` | 1,153,322 | `COMPLETED` | `0a8b0a524bf5d770a75133aefbf8df84d7c08791c2e15a72572fa7f8ab9a741e` |
| `stt_1786091088670_9efbcbe0.json` | 369 | `RUNNING` | `8c4a6b859ebf104a1a7b3c30cdb68fea4166331400d5ae5f40017b52916b3a54` |

## 로컬 백업

- 저장 위치: Git 제외 `local-evidence/device-baseline-20260807_1917/`
- tar SHA-256: `343423529f1d3c9868497ec8ff447361b37d97ef25df8d2b02f23bc37b85e314`
- tar를 다시 추출한 뒤 4개 JSON의 SHA-256이 기기 기준선과 모두 일치함
- 백업 디렉터리 권한: owner only
- checkpoint 원문은 Git 추적 문서에 포함하지 않음

## 안전 조건

- `RUNNING` 세션은 원본을 변경하지 않았다.
- 설치·앱 재시작·앱 데이터 삭제를 수행하지 않았다.
- 취소/중단 상태 영속화 수정과 native 빌드 고정 전까지 Samsung에 새 APK를 설치하지 않는다.
- 수정 이후에도 완료된 6시간 세션 SHA-256은 변경 금지 기준으로 사용한다.

## 2026-08-07 개발 빌드 설치 게이트

- 신규 Debug APK SHA-256: `1ac618929e659d416ff21f647323c0856e165eef9e46cc1db2bc75b181c329f6`
- 설치 APK signer SHA-256: `6bfd1a305ea7958397ca2d4c0a4917ea90b6b6fe75ea8b38b56001c05b4a1092`
- 신규 Debug signer SHA-256: `feec8e9daf4e22af8fb2b7cd4e7ed1329968bc3ade106ba3b19e0849f6b296c5`
- signer가 달라 `adb install -r`로 Samsung 데이터를 보존한 업데이트를 할 수 없다.
- 기존 debug keystore를 복구하기 전까지 Samsung 설치를 중단한다.
- uninstall/reinstall + 데이터 복원은 전체 앱 데이터 백업과 명시적 승인 없이는 수행하지 않는다.

## 2026-08-07 20:01 클린 재설치 결과

- 승인: 사용자가 기존 앱/데이터 삭제 후 재빌드·설치를 명시적으로 요청함
- 삭제 전 기기: 활성 `TranscriptionService` 없음
- `adb -s R3CY40PXCAP uninstall com.stt.benchmark`: 성공
- 최신 Debug 설치: 성공
- 설치 APK SHA-256: `1ac618929e659d416ff21f647323c0856e165eef9e46cc1db2bc75b181c329f6`
- 설치 Debug signer SHA-256: `feec8e9daf4e22af8fb2b7cd4e7ed1329968bc3ade106ba3b19e0849f6b296c5`
- 로컬 Debug APK와 설치 APK의 SHA-256 일치
- 설치 버전: `1.0.0` (`versionCode=1`, `targetSdk=34`)
- `MainActivity` 콜드 스타트: `Status: ok`, `LaunchState: COLD`, `TotalTime: 409ms`
- 콜드 스타트 후 활성 `TranscriptionService`: 없음
- 앱 process의 fatal/crash scan: 감지 없음
- 앱 내부 `stt_sessions`: 없음
- 후속 확인에서 `files/profileInstalled`만 생성됨

삭제된 4개 checkpoint는 `local-evidence/device-baseline-20260807_1917/` 백업으로만 복구할 수 있다.
백업 tar SHA-256은 `343423529f1d3c9868497ec8ff447361b37d97ef25df8d2b02f23bc37b85e314`이며,
완료 6시간 checkpoint 백업 SHA-256은 `0a8b0a524bf5d770a75133aefbf8df84d7c08791c2e15a72572fa7f8ab9a741e`다.
OAuth parity 또는 일반 앱 시험을 위해 이 민감 데이터 전체를 자동 복원하지 않는다.

## 2026-08-07 21:26 Phase 1 UI 갱신 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (현재 앱 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `69ab48a8f150ff113ab53cec613165884ed5e6ce301fdf897b07f3619786caff`
- 온보딩 1/2 → 2/2 → 녹음 route 전환: 성공
- 완료 flag 저장 뒤 콜드 재진입 시 온보딩 skip: 성공
- `녹음/전사/보관함/설정` route와 selected state 실기기 smoke: 성공
- 직접 녹음 control: 안전 recorder service 전까지 의도적으로 비활성
- 앱 process fatal/crash scan: 감지 없음

## 2026-08-07 21:46 Phase 2 파일 안전 계층 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (현재 앱 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `9d4aeb267285e54767af163297b27f8a520f297db82d95006a3e25e8008cacd3`
- Debug cold start: 성공 (`MainActivity`, 451ms), recovery fatal/crash 없음
- 단위 테스트: 45개 통과
- `.deviceTest` 실기기 instrumentation: 7개 통과, AICore opt-in probe 1개 skip
- Debug/deviceTest/deviceTestAndroidTest assemble: 성공
- Android Lint: 0 errors, 22 warnings
- 실제 마이크·foreground service: Phase 3 전까지 미활성

## 2026-08-07 22:30 Phase 3 녹음 서비스 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (현재 앱 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `f0abee298b5c672242921410d0c7b98296d6104a275ab1f9c2ef6f3cfafa9e85`
- Debug cold start: 성공 (`MainActivity`, 410ms), 자동 시작 service 및 fatal/crash 없음
- 단위 테스트: 53개 통과
- `.deviceTest` instrumentation: 10개 중 9개 통과, AICore opt-in probe 1개 skip
- 실제 마이크 opt-in smoke: 앱 정지 3초 녹음과 notification 정지 1.5초 녹음 모두 `SAVED`
- 두 녹음 모두 홈 이동 뒤 정지, 실제 codec/sample rate/channel/duration, SHA-256, final 파일 및 `.part` 부재 검증
- foreground service manifest type `microphone`, optional microphone feature, `stopWithTask=false` 계약 검증
- smoke 종료 후 `dumpsys activity services`: 없음
- smoke 종료 후 `dumpsys power`: active wake lock 0, `LongStt:Recorder` ACQ/REL 쌍 일치
- Debug/deviceTest/deviceTestAndroidTest assemble 및 Android Lint: 성공(0 errors, 23 warnings)
- 제품 `녹음` control: Phase 4 service/UI 상태 연결 전까지 의도적으로 비활성

## 2026-08-08 08:51 Phase 4 녹음 UI 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (현재 앱 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `102e9bc9fc5372204e362dff90999614ade35ceb35e705fa2abd6894f6d73c1c`
- Debug cold start: 성공 (`MainActivity`, 615ms), 자동 시작 service 및 fatal/crash 없음
- JVM/unit/Robolectric Compose: 65개 통과, 0 failure
- `.deviceTest` instrumentation: 11개 중 10개 통과, AICore opt-in probe 1개 skip
- 실제 마이크: 앱 정지, notification 정지, Activity recreation 복원 모두 `SAVED`
- 실제 Android 16 permission dialog에서 `앱 사용 중에만 허용` 결과 확인
- 녹음 중 `전사` route 이동 뒤 recording navigation dot과 background service 유지 확인
- 종료 뒤 `dumpsys activity services`: 없음, `dumpsys power`: active wake lock 0
- Debug/deviceTest/deviceTestAndroidTest assemble 성공
- Android Lint: 0 errors, 21 warnings
- README용 data-safe 캡처: `docs/screenshots/` 12개, 720×1560, 총 3,015,592 bytes
- 제품 Debug 녹음 control: 활성화
- 다음 미구현 경계: 녹음 세션의 MediaLibrary/group STT 연결(Phase 5)

## 2026-08-08 09:45 Phase 5 녹음 그룹 STT 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (제품 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `39dfbe6e32620a92eb6418be3e58fb84b12b4ad69521c2d4032d01717b69ce47`
- 최종 cold start: 성공 (`MainActivity`, 436ms), 앱 PID fatal/crash 없음
- JVM/unit/Robolectric Compose: 82개 통과, 0 failure/error
- `.deviceTest` instrumentation: 11개 중 10개 통과, AICore opt-in 1개 skip
- Debug/deviceTest/deviceTestAndroidTest assemble 성공
- Android Lint: 0 errors, 20 warnings
- 실제 6초 M4A: `SAVED`, READY 1개, MediaLibrary `직접 녹음 청크 1` 자동 등록
- 기존 로컬 증거 `ggml-base-q5_1.bin`을 앱 내부 모델로 stage해 실제 child STT를 실행함
- 실제 group: complete session, sequence 0, child STT schema v2 `COMPLETED`, total/current chunk 1/1
- 검증 명령과 문서에는 child transcript를 출력하지 않음
- terminal 뒤 `RecorderService`/`TranscriptionService`: 없음, wake lock 0
- 보관함 stale snapshot 이슈를 route 재진입 `refreshLibraries()`로 수정 후 원본 즉시 표시 확인
- 프로세스 시작 이전 checkpoint/part만 시작 복구하도록 제한해 새 녹음과 복구 검사의 경쟁을 방지
- README 캡처: 총 15개, 3,416,373 bytes; Phase 5 제품 Debug 화면 3개 추가
- 다음 경계: Phase 6 기존 전사 화면/ViewModel 책임 분리, Phase 7 장시간 1시간·6시간/릴리스 QA

## 2026-08-08 11:12 Phase 6 화면 route 분해 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (제품 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 APK SHA-256: `50d620aab748fb50af2d8da1e93599bd46e2d74b1207752487670806477db938`
- 최종 cold start: 성공 (`MainActivity`, 471ms), 앱 PID fatal/crash 없음
- JVM/unit/Robolectric Compose: 95개 통과, 0 failure/error/skip
- `.deviceTest` instrumentation: 11개 중 10개 통과, AICore opt-in 1개 skip
- Debug/deviceTest/deviceTestAndroidTest assemble 성공
- Android Lint: 0 errors, 20 warnings
- 전사·보관함·설정 route와 모델 catalog, SavedState dialog 복원, Debug 자동화 요청 계약을 확인
- 표시 오디오가 없는 상태에서 숨은 구 `audioPaths`가 실행 버튼을 활성화하던 불일치를 수정하고 `입력 필요`/비활성 상태를 실기기에서 확인
- 설치 전 Phase 5의 실제 6초 그룹 STT 데이터와 설치 모델이 `install -r` 뒤 보존됨
- terminal 뒤 `RecorderService`/`TranscriptionService`: 없음, active wake lock 0
- README 캡처: 총 17개, 3,699,226 bytes; transcript·OAuth 민감정보 없음
- 다음 경계: Phase 7 접근성·다중 화면 크기·1시간/6시간 장시간·릴리스 QA

## 2026-08-08 Phase 7 중간 검증 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (제품 데이터 보존), Debug-only metadata audit activity를 포함한 최신 Debug APK 설치 성공
- 로컬/설치 APK SHA-256: `d2735893fb5d2dab089d6ac6607c5290c41771867e577d1eeb436f9cab561cdc`
- Release unsigned APK SHA-256: `fa8f4e62ec8c5088aecdbdce6e62339056f1722e5a0f808ac365e87f40d75873`
- `:app:testDebugUnitTest`: 108 tests, failure/error/skip 0
- `assembleDebug`, `assembleRelease`, `assembleDeviceTest`, `assembleDeviceTestAndroidTest`, `lintDebug`, `lintRelease`, `verify16KbAlignment`, `verifyReleaseSurface`, `git diff --check` 통과
- lint 결과: Debug 0 errors/22 warnings, Release 0 errors/25 warnings
- Debug-only audit은 기기 내부에서 최근 completed 2-child group의 완료 상태·청크 count·50ms coverage 연속성만 계산한다. UI 결과는 `AUDIT_PASS_CHILDREN=2_COVERAGE=COMPLETE`이며 transcript, audio path, stable ID, error text를 표시하거나 추출하지 않는다.
- 20분 제품 녹음은 M4A 2개가 순서 `0,1`로 final/MediaLibrary에 등록됐고, duration 합계와 session elapsed 차이는 296ms였다. terminal 뒤 비격리 `.part`와 0-byte final은 없었다.
- 최초 background group의 child handoff는 Android 16 foreground-service background-start 제한으로 안전하게 FAILED terminal 저장됐다. terminal checkpoint 이후 service 내부에서 다음 child를 준비하고 종료 cleanup 뒤 직접 launch하도록 수정한 재시도는 홈 이동 상태에서 두 child와 parent group이 모두 `COMPLETED`로 끝났다.
- 최신 main activity 복귀 뒤 `RecorderService`/`TranscriptionService`는 없고 `dumpsys power`의 held Wake Locks는 size 0이다.
- 아직 1시간·6시간·8시간, 실제 lock/제품 notification stop, 외부 audio/interruption, 실제 OAuth 계정 시험은 완료하지 않았다.

## 2026-08-08 Phase 7 1시간 검증·최신 Debug 설치

- 설치 방식: `adb -s R3CY40PXCAP install -r` (기존 제품 데이터 보존)
- 설치 결과: 성공
- 로컬/설치 Debug APK SHA-256: `055df509ca451b957b8bcbccf4783b83d1ec1fa94ae8ce9cad47f586144a77ee`
- Release unsigned APK SHA-256: `2656eb44e4f6ef09da36ced0bd02d8c49542a71c3714aceee66cb4ba80e31249`
- `:app:testDebugUnitTest`: 109 tests, failure/error/skip 0
- 전체 static gate: Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint, 16KB ELF/APK alignment, Release surface, `git diff --check` 통과
- lint: Debug 0 errors/22 warnings, Release 0 errors/25 warnings
- Samsung SM-S931N(Android 16) 제품 녹음: 실제 1:03:54, final M4A 4개. Debug-only aggregate audit에서 MediaLibrary complete 및 duration consistent를 확인했다.
- background 4-child group STT 재시험: aggregate coverage complete. terminal 뒤 RecorderService/TranscriptionService 없음, held wake lock 0.
- 데이터 안전 수정: MediaLibrary index의 multi-instance write 경쟁을 process-wide lock과 12-instance 동시 등록 회귀 테스트로 해결했다. service handoff와 중복되던 ViewModel background FGS fallback도 제거했다.
- 아직 6시간·8시간, actual lock/제품 notification stop, 외부 audio/interruption, 실제 OAuth 계정 검증은 완료하지 않았다.

## 2026-08-08~09 Phase 7 6시간 녹음·19-child STT 검증

- 설치 방식: `adb -s R3CY40PXCAP install -r` (제품 데이터 보존)
- 최신 Debug APK 로컬/설치 SHA-256: `740f4f7c8dcf3d8d9d9ad4ce9c8300ae8d5116980962a0ed3c9717441838d181`
- audit refresh 보완 뒤 full static gate: JVM 109 tests(failure/error/skip 0), Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint(error 0; 22/25 warnings), 16KB, Release surface, `git diff --check` 통과
- Samsung 제품 녹음: 약 6시간, final M4A 19개. recording aggregate audit은 MediaLibrary complete 및 duration consistent를 통과했다.
- background 순차 STT: 19개 child가 aggregate coverage complete로 종료됐다.
- 30분·1·2·3·4·5시간 자원 checkpoint에서 RecorderService는 유지됐고, battery 99~100%(충전 상태), 온도 33.4~38.1°C, 여유 저장공간은 유지됐다.
- 녹음 및 STT terminal 뒤 RecorderService/TranscriptionService는 없고 held wake lock은 0이다.
- 서비스가 없는 안전 상태에서 process restart 후 녹음 checkpoint가 보존됨을 확인했다. 화면 자동 소등 뒤 wake-only aggregate audit도 통과했으나, actual lock/제품 notification stop은 별도 미완료 항목이다.
- 아직 8시간 soak, 외부 audio/interruption, existing import/단일 파일 장시간 STT 회귀, 실제 OAuth 계정 검증은 완료하지 않았다.

## 2026-08-09 Phase 7 8시간 minimum 초과 soak

- Samsung 제품 Debug 녹음: 8시간 minimum 이상, final M4A 35개
- Debug-only aggregate audit: `RECORDING_AUDIT_PASS_CHUNKS=35_MEDIA=COMPLETE_DURATION=CONSISTENT`
- 2/4/6시간 checkpoint: RecorderService 유지, battery 100%(충전 상태), 온도 31.6~36.1°C, 여유 저장공간 약 144.0~143.4GiB
- 6시간 checkpoint display: `Dreaming`; 화면 자동 소등 상태에서 recorder service 유지 확인
- terminal: RecorderService/TranscriptionService 없음, held wake lock 0
- 실제 lock/잠금 해제와 제품 notification stop은 이 soak과 별도로 아직 검증하지 않았다.

## 2026-08-09 제품 notification stop·화면 소등 검증

- 발견/수정: Android 13+ `POST_NOTIFICATIONS`은 manifest에만 선언돼 있었고 제품 녹음 route에서 runtime 요청하지 않아 foreground notification action을 사용할 수 없었다.
- 수정: 사용자가 녹음을 시작할 때 필요한 notification permission을 요청하고 result 뒤 recording을 시작한다. Android 12 이하/Android 13+ 미허용/기허용 정책 unit test 3개를 추가했다.
- Samsung 실기기: notification allow → RecorderService 시작 → 홈 background → foreground notification 확장 → `녹음 정지` → terminal service/wake lock 0 → 앱 재진입 start 가능 상태 확인
- 화면 소등: display `Dozing` 20초 동안 RecorderService 유지, wake/제품 control 종료 뒤 RecorderService·TranscriptionService·held wake lock 0
- 기기에 secure lock이 없어 actual lock/잠금 해제는 여전히 미검증이다.

## 2026-08-09 notification 보완 후 최신 build/install 기준

- Debug APK local/installed SHA-256: `d9f57b3ba5bc1fdfa954c68e53508ae0e3508978d9d429f44fb5b71113bcf8ed`
- Release unsigned APK SHA-256: `6ec9f056d04f77115c8bd4a6c3085149faed1b631ccf0110a680af39ab0e7cf4`
- `:app:testDebugUnitTest`: 112 tests, failure/error/skip 0
- Full gate: Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint, 16KB ELF/APK alignment, Release surface, asset provenance, `git diff --check` 통과
- lint: Debug 0 errors/24 warnings, Release 0 errors/27 warnings

## 2026-08-09 Android file import smoke

- 1초 무음 WAV test fixture를 Android DocumentsUI에서 실제 product import하고 제품 전사 화면에서 선택 확인
- 기존 단일 파일 TranscriptionService 실행 결과: `COMPLETED`; terminal TranscriptionService 없음, held wake lock 0
- 이 fixture smoke는 URI import/copy/selection 경계만 검증한다. 기존 장시간 단일 file STT 회귀는 별도 미완료 항목이다.

## 2026-08-09 최신 Debug STT audit refresh·설치 기준

- Debug-only STT audit이 기존 최상단 activity에 새 launch intent를 받을 때 stale aggregate를 표시하지 않도록 `onNewIntent` refresh를 적용했다. Debug audit은 completion/coverage 집계만 표시하며 transcript, audio path, stable ID, error payload는 출력하지 않는다.
- 최신 Debug audit: `AUDIT_PASS_CHILDREN=19_COVERAGE=COMPLETE`; 최신 recording audit: `RECORDING_AUDIT_PASS_CHUNKS=35_MEDIA=COMPLETE_DURATION=CONSISTENT`.
- 최신 Debug APK local/installed SHA-256: `011cf672f9289d66b467e467e6f3fc0f9be3ba4402def7abe4ad45643fd1b8bc` (일치)
- Release unsigned APK SHA-256: `6ec9f056d04f77115c8bd4a6c3085149faed1b631ccf0110a680af39ab0e7cf4`
- `:app:testDebugUnitTest`: 112 tests, failure/error/skip 0. Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint, asset provenance, 16KB ELF/APK alignment, Release surface, `git diff --check` 통과.
- lint: Debug 0 errors/24 warnings, Release 0 errors/27 warnings.
- 현재 종료 상태 집계: RecorderService 0, TranscriptionService 0, held wake lock `size=0`.
- secure lock, Bluetooth/USB, 전화/알람, 기존 장시간 단일 file STT, 실제 Codex OAuth는 검증 전이며 Release blocker를 유지한다.

## 2026-08-09 입력 route UI·최신 설치 기준

- `MediaRecorder`/`AudioRecord` audio-routing callback을 generic input category로 변환해 제품 녹음 화면과 TalkBack 상태 설명에 연결했다. device name/ID는 수집·출력하지 않는다.
- data-preserving Debug 설치 후 Samsung SM-S931N(Android 16) 제품 start/stop에서 route UI `내장 마이크`를 확인했다. terminal: RecorderService 0, TranscriptionService 0, global held wake lock `size=0`.
- Debug APK local/installed SHA-256: `30e2944fe7dde3336f6eecc75474f796d0864f72d452d07e50833c67bdddbdc0` (일치)
- Release unsigned APK SHA-256: `24a0cdc4ea80ec5b02e7b2cf7036acdb149953101383ab0568cf4b6f7f4c6835`
- `:app:testDebugUnitTest`: 113 tests, failure/error/skip 0. Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint, asset provenance, 16KB ELF/APK alignment, Release surface, `git diff --check` 통과.
- lint: Debug 0 errors/24 warnings, Release 0 errors/27 warnings.
- 이 기록은 내장 마이크 route smoke다. Bluetooth/USB, secure lock, 전화/알람, 기존 장시간 단일 file STT, 실제 Codex OAuth는 계속 별도 Release blocker다.

## 2026-08-09 기존 장시간 imported single-file readiness

- Debug-only aggregate audit은 파일명·경로·ID·duration·size·원문을 출력하지 않고, 6시간 이상 imported candidate 및 terminal STT coverage만 확인한다.
- Samsung data-preserving Debug 설치의 audit 결과: `LEGACY_LONG_SINGLE_STT_CANDIDATE_NONE`. 현재 제품 MediaLibrary에는 6시간 이상 실제 imported single-file candidate가 없다.
- Debug APK local/installed SHA-256: `0d386be97455922c35c655ca46a82ccd0f179447fcb87c0885914c289bf281d6` (일치)
- Release unsigned APK SHA-256: `24a0cdc4ea80ec5b02e7b2cf7036acdb149953101383ab0568cf4b6f7f4c6835`
- `:app:testDebugUnitTest`: 113 tests, failure/error/skip 0. Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint, asset provenance, 16KB ELF/APK alignment, Release surface, `git diff --check` 통과.
- 이 결과는 기존 장시간 single-file STT가 미완료임을 더 강하게 확인한다. source audio가 제공/선택되기 전에는 Bluetooth/USB, secure lock, 전화/알람, OAuth와 함께 Release blocker를 유지한다.

## 2026-08-09 Codex OAuth readiness

- Samsung 제품 설정 화면의 generic OAuth state: `signed out`.
- 실계정 로그인, token restore, non-sensitive probe, logout, storage boundary 검증은 실행하지 않았다. token/account/browser 화면을 읽거나 출력하지 않았으며 이 상태는 계속 Release blocker다.
