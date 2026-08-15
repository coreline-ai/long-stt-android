# voice-tracker-find 디자인·참조 소스 최종 적용 검토

> [!NOTE]
> 이 문서는 2026-08-07 적용 전 최종 검토 기록이다. 현재 GUI·녹음·전사·보안 구현 상태는 [`HANDOFF_20260815.md`](HANDOFF_20260815.md)를 우선한다.

- 작성 일시: 2026-08-07 KST
- 현재 프로젝트: `coreline-ai/long-stt-android`
- 참조 프로젝트: `coreline-ai/voice-tracker-find`
- 참조 기준: `main` / `34d0b4cee3b1ca61f9093365a90327f6caffa786`
- 현재 프로젝트 기준: `main` / HEAD `36e28aaa37b72f80b6e5d1481842fc759d51d5f9`
- 선행 검토: `docs/VOICE_TRACKER_FIND_PORT_REVIEW_20260807.md`
- 구현 계획: `dev-plan/implement_20260807_202614.md`
- 결론 상태: 적용 범위 확정, 실제 디자인·참조 소스 이식 미착수

## 1. 최종 결론

참조 앱의 **제품 전체를 병합하지 않고**, 아래 4개 묶음을 현재 앱 구조에 맞게 선별 적용한다.

1. **디자인 기반**: Quiet Archive color/type/spacing/motion, 시스템 바, 공통 Compose 컴포넌트
2. **제품 구조**: 2페이지 온보딩, `녹음 / 전사 / 보관함 / 설정` 4개 route, 녹음 첫 화면
3. **녹음 안전성**: microphone foreground service, 명령 직렬화, 20분 청크, `.part`, finalize,
   quarantine, 복구, 저장공간 정책, notification 정지
4. **LongSTT 전용 통합**: 기존 AtomicFile store와 단일 파일 `TranscriptionService`를 유지하면서
   녹음 세션 그룹 전사 coordinator와 통합 보관함을 새로 구현

다음은 이식하지 않는다.

- Hilt/Room/KSP/WorkManager 기반 전체 아키텍처
- 서버 upload/동기화/backend
- Notes 편집·WikiLink·conflict
- 예약 녹음
- 참조 Local AI/SenseVoice/Gemma
- 참조 OAuth SDK와 cloud account 모듈
- 참조 package/build 설정의 통복사

## 2. 이번 재검토에서 확정·수정된 핵심 판단

### 2.1 녹음 세션의 다중 청크 전사는 신규 구현이 필요

현재 `SttViewModel.runBatchBenchmark()`는 `audioPaths.size != 1`이면
“현재는 체크포인트 안전성을 위해 오디오를 한 개씩 전사합니다”로 종료한다. 따라서 이전 검토의
“기존 batch STT에 연결” 표현은 정확하지 않다.

최종안:

- 녹음은 장시간 손실 범위를 줄이기 위해 기본 20분 청크로 안전하게 확정한다.
- `RecordingTranscriptionGroupStore`가 부모 녹음/전사 그룹 상태를 원자 저장한다.
- `RecordingTranscriptionCoordinator`가 청크를 순서대로 기존 단일 파일
  `TranscriptionService`에 하나씩 전달한다.
- 각 child STT checkpoint는 그대로 보존한다.
- 보관함은 child 결과를 시간 offset과 함께 하나의 녹음 세션 결과로 결합해 보여준다.
- coordinator 자체가 Whisper engine을 직접 소유하지 않는다.

### 2.2 세 번째 탭은 `결과`보다 `보관함`이 적합

참조 앱의 Quiet Archive 방향과 현재 앱의 오디오·전사·요약 관계를 함께 담기 위해
최종 탭 이름은 `보관함`으로 한다.

| 탭 | 단일 책임 |
|---|---|
| 녹음 | 새 음성 기록과 현재 녹음 상태 |
| 전사 | 파일/녹음 선택, 모델 선택, 전사 계획·진행 |
| 보관함 | 녹음 원본, 전사 결과, 향후 요약·공유 |
| 설정 | 모델·Codex·권한·저장공간·앱/진단 |

### 2.3 참조 구조를 그대로 쓰지 않고 현재 경량 구조를 유지

현재 앱은 이미 `MediaLibraryStore`, `TranscriptionSessionStore`, `BenchmarkRecorder`를
`AtomicFile` 중심으로 운영한다. 녹음 기능 때문에 Room/Hilt를 도입하지 않는다.

- 녹음 세션: 신규 `RecordingSessionStore`
- 녹음 그룹 전사: 신규 `RecordingTranscriptionGroupStore`
- 설정 1차: 작은 `SharedPreferences` wrapper
- 장기 작업 arbitration: 신규 `DeviceWorkCoordinator`
- 화면별 상태: route별 ViewModel

## 3. 현재 프로젝트 기준선과 선행 정리

### 3.1 코드 구조

| 항목 | 현재 확인 | 영향 |
|---|---|---|
| 메인 Compose 화면 | `SttBenchmarkScreen.kt` 1,167행 | 신규 UI를 추가하기 전에 route별 분리 필요 |
| 메인 ViewModel | `SttViewModel.kt` 851행 | 녹음 책임 추가 금지 |
| 화면 navigation | 없음 | Navigation Compose 최소 추가 필요 |
| UI lifecycle 수집 | `collectAsState()` | `collectAsStateWithLifecycle()`로 변경 권장 |
| 디자인 리소스 | launcher 외 raster/font 없음 | theme/font/asset 체계 신규 도입 |
| STT Service | 단일 파일 + 내부 10분 transcription chunk | 그대로 유지하고 group coordinator가 외부 순차 실행 |
| 미디어 인덱스 | AtomicFile, 오디오 단일 entry | source/session/sequence 확장 필요 |
| OAuth | 앱 dependency 및 첫 화면 GUI 연결됨 | 설정 route로 이동 |
| UI test | Compose UI test dependency/테스트 없음 | 참조 test pattern 도입 필요 |

### 3.2 구현 전 반드시 처리할 기준선 이슈

1. 현재 작업 트리에 큰 미커밋 변경이 있으므로 UI 이식 전에 안정화 checkpoint commit을 만든다.
2. README에는 OAuth가 앱 미연결이라고 쓰였으나 실제 `:app` dependency와 GUI가 존재하므로 문서를 수정한다.
3. 현재 Debug/Release가 같은 application ID를 사용하므로 instrumentation이 제품 데이터를 건드릴 수 있다.
4. 선행 STT 수명주기 계획의 남은 실기기 gate를 확인한 뒤 recorder 설치를 진행한다.
5. 현재 `runBatchBenchmark()`가 다중 입력을 지원하지 않는 상태를 unit test로 먼저 고정한다.
6. 빌드 실행 환경의 잘못된 `JAVA_HOME`과 누락된 SDK 경로를 개발 문서/스크립트로 정리한다.

### 3.3 현재 빌드 확인

- 최초 기본 환경 실행은 잘못된 `JAVA_HOME`과 누락된 SDK 경로 때문에 실패했다.
- Android Studio JBR과 Android SDK 경로를 명시한 뒤
  `:app:testDebugUnitTest :app:assembleDebug`는 성공했다.
- 현재 앱 unit `@Test` 12개, app androidTest `@Test` 7개가 존재한다.
- 참조 앱 Android 모듈에는 unit/instrumentation 합계 56개의 `@Test`가 있다.

## 4. 디자인 적용 최종안

### 4.1 그대로 가져올 수 있는 디자인 원칙

- 기록 시작/정지를 가장 큰 단일 주 동작으로 만든다.
- 상태를 색만으로 표현하지 않고 라벨·아이콘·진행 정보로 함께 표현한다.
- 이미지는 분위기와 빈 상태만 보조한다.
- 카드 대시보드보다 section label과 hairline divider를 우선한다.
- 사용자 파일 보존을 기능 실패보다 우선한다.
- 장식 animation보다 상태 전환의 명료성을 우선한다.

### 4.2 Theme token

| 토큰 | Dark | Light | 적용 위치 |
|---|---:|---:|---|
| ArchiveInk | `#151614` | `#24241F` | 앱 배경·본문 |
| ArchivePaper | `#EEE8DC` | `#F7F3EA` | 보관함 상세·소개 |
| ArchiveCopper | `#C07148` | `#A95632` | 녹음·주 동작 |
| ArchiveNoteCopper | `#8C452B` | `#8C452B` | 밝은 paper 위 링크·규칙선 |
| ArchiveMoss | `#7E8C78` | `#5F6D58` | 완료·안정 |
| ArchiveFog | `#A9AAA3` | `#6E706A` | 보조 텍스트 |
| ArchiveError | `#D06A60` | `#A73F39` | 오류 |
| ArchiveHairline | `#343530` | `#DDD6CA` | divider |

최종 적용:

- source `Theme.kt` 구조와 contrast test는 적용한다.
- 기존 Material 3 blue theme는 교체한다.
- system dark/light를 따르되 소개 hero의 scrim 위 텍스트는 항상 명시적 dark surface로 처리한다.
- color 상수를 임의로 직접 쓰기보다 `MaterialTheme.colorScheme`의 의미 역할로 노출한다.

### 4.3 Typography

- UI·상태·숫자: Pretendard Regular/SemiBold
- 짧은 소개 heading: MaruBuri SemiBold
- timer: 48~56sp, tabular number
- body: 16/24sp
- secondary: 13~14sp
- title: 28~32sp
- font scale 200%에서 고정 높이로 텍스트를 잘라내지 않는다.

폰트를 포함할 경우 OFL 1.1 전문을 APK에 함께 배포한다. 승인이 끝나기 전에는 시스템 sans/serif
fallback으로 먼저 구현해 레이아웃을 막지 않는다.

### 4.4 Motion·shape·spacing

- spacing: 4/8/12/16/24/32/48dp
- primary control: 184dp 기준, 작은 화면에서 176dp까지 축소 가능
- touch target: 최소 48dp
- 화면 전환: 180~240ms
- 상태 전환: 220~320ms
- 지속 pulse/glow, parallax, decorative Lottie 제외
- reduced motion에서는 파형을 정적 음량 상태로 대체

## 5. 화면별 최종 적용 항목

### 5.1 온보딩

**적용 등급: 변형 적용 / P0**

| 항목 | 적용 |
|---|---|
| 2페이지 full-screen 구조 | 적용 |
| vertical image + gradient scrim | 적용 |
| `1 / 2`, `2 / 2` 표시 | 적용 |
| 다음/시작 주 버튼 | 적용 |
| 완료 상태 저장 | 적용 |
| source 서버/노트 문구 | 제거 |

LongSTT 문구:

1. `긴 대화를 안전하게 담습니다`
   - 화면을 벗어나도 녹음을 유지하고 완료된 청크만 보관
2. `녹음에서 전사와 요약까지`
   - Whisper 전사는 기기에서 처리하고, 외부 요약은 사용자가 선택할 때만 실행

권한은 온보딩에서 미리 요청하지 않고 실제 녹음 버튼을 누를 때 요청한다.

### 5.2 녹음 첫 화면

**적용 등급: 큰 폭 변형 / P0**

적용:

- hero + scrim
- 상태 pill
- 실제 경과 timer
- `SoundThread`
- 176~200dp record control
- 마이크 없음/권한 필요/준비/녹음/마감/완료/실패 상태
- 저장공간
- 최근 녹음 세션
- 접을 수 있는 진단 영역

제거·변경:

- upload queue 제거
- 서버 sync 상태 제거
- Local AI mic busy 문구는 `DeviceWorkCoordinator`의 현재 작업 문구로 대체
- 최근 목록은 개별 청크가 아니라 녹음 세션 단위로 그룹화
- source의 Composable 내부 `MediaPlayer`는 그대로 복사하지 않고 playback controller로 분리
- `RecordControl` semantics에 `Role.Button`, `stateDescription`, disabled reason을 보강

### 5.3 전사 화면

**적용 등급: 현재 기능 재배치 / P0**

- 오디오 가져오기와 기존 오디오 선택
- 녹음 세션 선택
- Whisper 모델 선택·다운로드·전환
- 예상 10분 transcription chunk 계획
- 전사 진행 단계·coverage·안전 저장 위치
- 안전하게 중지·재개

참조 앱에서 가져올 것은 section list, 상태 pill, storage/error 표현뿐이며 Local AI 화면은 가져오지 않는다.

### 5.4 보관함 화면

**적용 등급: Notes 화면의 시각 문법만 변형 적용 / P1**

- Notes 목록의 editorial header, filter, date/folder grouping, row typography를 재사용한다.
- filter는 `전체 / 녹음 / 전사 / 요약`으로 변경한다.
- 노트 동기화, 새 노트 작성, sync conflict는 제거한다.
- 상세의 paper surface와 serif heading은 전사·요약 장문 가독성에 적용한다.
- Markdown renderer는 향후 요약 결과에만 선택 적용한다.
- WikiLink와 YAML frontmatter는 현재 범위에서 제외한다.
- 빈 상태에서는 승인된 `empty_notes_desk`를 보관함용 문구로 변형하거나 신규 이미지를 사용한다.

### 5.5 설정 화면

**적용 등급: section 구조 변형 적용 / P1**

권장 section:

1. 녹음: 품질 설명, 기본 청크, 권한 상태
2. 전사 모델: 현재 모델, 설치·삭제, 저장 크기
3. 요약 계정: 기존 `CodexAuthViewModel`의 로그인/복원/로그아웃
4. 저장공간: 녹음·오디오·모델·전사 결과별 사용량
5. 개인정보: 로컬 전사와 외부 요약 경계
6. 앱 정보: 버전, source provenance, license
7. 개발 진단: Debug에서만 device/benchmark/automation 정보

제외:

- 예약 시간 창
- 자동 sync/Wi-Fi only
- receiver server URL/user/token
- 참조 cloud account SDK와 provider selection

## 6. 공통 Compose 컴포넌트 적용표

| 참조 컴포넌트 | 최종 판단 | 수정 사항 |
|---|---|---|
| `SoundThread` | 적용 | lifecycle, reduced motion, 음량 단계 semantics 보강 |
| `RecordControl` | 적용 | Button role/stateDescription/disabled reason 추가 |
| `StatusPill` | 적용 | good boolean 대신 명시적 status tone 사용 |
| `SectionLabel` | 적용 | 전사/설정/보관함 공통 사용 |
| `ImageState` | 적용 | 보관함·모델·오류 빈 상태에 사용 |
| `formatDuration` | 적용 | 공통 formatter로 이동 |
| `SystemBarAppearance` | 적용 | route/theme별 상태바·내비게이션 아이콘 조정 |
| bottom navigation state restore | 적용 | 4개 LongSTT route로 변경 |
| `collectAsStateWithLifecycle` | 적용 | 모든 화면 StateFlow 수집에 사용 |

## 7. 녹음 소스 파일별 적용 판단

| 참조 파일/클래스 | 판단 | 현재 프로젝트 적용 방식 |
|---|---|---|
| `RecordingRuntime` | 거의 직접 적용 | amplitude와 command error를 별도 StateFlow로 유지 |
| `RecorderController` | 거의 직접 적용 | start/stop intent만 담당 |
| `RecorderCommandActor` | 적용 | START/STOP/completion FIFO와 연타 테스트 |
| `RecordingSessionOutcome` | 적용 | capture failure가 cleanup 성공으로 덮이지 않게 함 |
| `RecordingFileManager` | 패턴 적용 | per-session directory, fsync/reconcile/rename 실패 검증 보강 |
| `RecorderService` | 큰 폭 변형 | Room/Hilt/AppPreferences 제거, AtomicFile store/coordinator 연결 |
| `AppStorageMonitor` | 변형 적용 | recordings/audio/models/stt/summary 디렉터리로 계산 |
| `StoragePolicy` | 변형 적용 | AAC/WAV worst-case와 모델/STT 여유 공간 통합 |
| `RecordingRepository` | 직접 복사 금지 | 현재 store를 source of truth로 하는 작은 repository 작성 |
| `RecordingWindow` | 제외 | 예약 녹음 범위 제외 |
| `MainRecordingSourceGatewayImpl` | 일부 패턴 | verified final file 복사 계약만 필요 시 사용 |

### 7.1 참조 구현에서 보강해야 하는 부분

- `renameTo()` 반환값만 믿지 않고 확정 전/후 경로와 metadata checkpoint를 함께 검증한다.
- hash 계산 도중 실패해 final 파일만 남은 경우 다음 시작에서 복구할 수 있어야 한다.
- quarantine rename 실패를 성공으로 보고하지 않는다.
- per-session 하위 디렉터리를 허용하는 canonical containment 검사를 구현한다.
- final 파일의 실제 container/codec/sample rate/channel/duration을 검사한다.
- `MediaRecorder` 설정은 제조사가 무시할 수 있으므로 요청값과 실제값을 구분해 저장한다.
- PCM fallback은 약 115MB/시간 수준이므로 청크마다 저장공간을 재검사한다.

## 8. 권한·서비스·알림 최종안

추가 manifest 요소:

```xml
<uses-feature
    android:name="android.hardware.microphone"
    android:required="false" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

`microphone required=false`인 이유는 마이크 없는 기기에서도 기존 파일 가져오기·전사를 사용할 수
있어야 하기 때문이다.

서비스:

```xml
<service
    android:name=".recording.RecorderService"
    android:exported="false"
    android:foregroundServiceType="microphone"
    android:stopWithTask="false" />
```

정책:

- microphone FGS는 사용자가 화면에서 녹음 버튼을 누른 시점에만 시작한다.
- `RECORD_AUDIO`가 없으면 서비스 시작 전에 권한 UI로 돌아간다.
- 알림 권한 거절을 녹음 권한 거절과 동일 취급하지 않는다.
- recording/STT notification channel, ID, action namespace를 분리한다.
- recording notification에는 앱 열기와 안전 정지 action을 제공한다.
- 현재 STT notification에도 참조 패턴을 적용해 앱 열기와 안전 중지 action을 추가한다.
- 녹음 terminal checkpoint 저장 성공 후 notification과 coordinator owner를 해제한다.

Android 정책 검증 기준은 공식 문서의
[foreground service type 안내](https://developer.android.com/develop/background-work/services/fgs/service-types)와
[notification runtime permission 안내](https://developer.android.com/develop/ui/views/notifications/notification-permission)를 따른다.

## 9. 데이터 모델 최종안

### 9.1 녹음 session

`RecordingSessionStore`:

- `schemaVersion`
- `sessionId`
- `state`: `PREPARING/RECORDING/FINALIZING/STOPPED/FAILED`
- `startedAtMs`, `stoppedAtMs`
- `currentChunkId`, `lastError`
- `chunks[]`

청크:

- `id`, `sequence`, `state`
- `partPath`, `finalPath`
- `createdAtMs`, `finalizedAtMs`
- `durationMs`, `sizeBytes`, `sha256`
- `container`, `codec`, `sampleRate`, `channels`
- `mediaEntryId`

### 9.2 MediaLibrary 확장

기존 `AudioEntry`에 기본값을 가진 필드 추가:

- `source = IMPORTED | RECORDED`
- `recordingSessionId`
- `sequence`
- `sha256`
- `container`, `codec`

기존 media index는 읽기만 해도 동일하게 복원되어야 하며, UI 개편 때문에 일괄 재저장하지 않는다.

### 9.3 녹음 그룹 전사

`RecordingTranscriptionGroupStore`:

- `groupId`, `recordingSessionId`
- `orderedMediaEntryIds`
- `childSttSessionIds`
- `currentMediaIndex`
- `status`: `QUEUED/RUNNING/PAUSED/COMPLETED/FAILED/CANCELLED`
- `createdAtMs`, `updatedAtMs`, `lastError`

결합 결과는 child checkpoint를 복사하지 않고 ID로 참조한다. 표시할 때 앞 청크 duration의 누계를
더해 전역 timestamp를 계산한다.

## 10. 참조 화면·데이터 소스 전체 결정표

### 10.1 UI

| 참조 소스 | 등급 | 결정 |
|---|---|---|
| `ui/theme/Theme.kt` | A | token/type 구조 적용 |
| `ui/common/Components.kt` | A/B | 공통 컴포넌트 적용 후 접근성 보강 |
| `ui/ThinkTankApp.kt` | B | onboarding/nav/system bar 구조만 적용 |
| `ui/recording/RecordingScreen.kt` | B | upload 영역 제거, LongSTT 상태로 재작성 |
| `ui/notes/NotesScreen.kt` | B/P2 | 보관함 목록·상세 시각 문법만 적용 |
| `ui/settings/SettingsScreen.kt` | B | LongSTT section으로 재작성 |
| `ui/AppViewModels.kt` | Pattern | StateFlow combine/stateIn/tick/storage refresh 패턴 |
| `ui/CloudAccountsViewModel.kt` | X | 기존 Codex 구현과 중복 |
| OnDevice UI module | X | 기존 Whisper 엔진과 제품 범위 중복 |

### 10.2 Data/background

| 참조 소스 | 등급 | 결정 |
|---|---|---|
| `data/settings/AppPreferences` | Pattern | onboarding/기본 녹음 설정만 경량 구현 |
| `data/settings/TokenCipher` | X | 기존 Codex Keystore 구현 사용 |
| `data/storage/AppStorageMonitor` | B | 현재 경로와 정책으로 재작성 |
| Room entities/DAO/database | X | 현재 AtomicFile 구조 유지 |
| `RecordingRepository` | Pattern | 상태 계약만 참고 |
| remote gateway/API | X | 서버 기능 제외 |
| SyncWorker/Scheduler | X | 업로드 기능 제외 |
| WorkManager 설정 | X | 현재 요구 없음 |

### 10.3 Build/운영

| 참조 항목 | 등급 | 결정 |
|---|---|---|
| Navigation Compose | A | 현재 toolchain 호환 버전만 추가 |
| Lifecycle runtime Compose | A | lifecycle-aware collect에 추가 |
| source AGP/Kotlin/SDK/BOM 전체 버전 | X | UI 이식과 동시에 업그레이드 금지 |
| `deviceTest` 격리 build type | A/P0 | instrumentation 전용 package 추가 |
| `.qa` persistent preview | B/P1 | 현재 package 데이터 백업 후 도입 검토 |
| fixed-serial QA script | A | Samsung 외 기기 오실행 방지 |
| `resolve_java_home.sh` 패턴 | A | JBR/SDK 환경 재현성 개선 |
| asset/font verify Gradle task | A | provenance/hash/license gate 적용 |
| debug cleartext exception | X | 현재 서버 HTTP 기능 없음 |
| explicit no-backup extraction rules | B | 민감 오디오/전사 이동 차단 강화 |

Navigation 구현은 Android 공식
[Navigation with Compose 안내](https://developer.android.com/develop/ui/compose/navigation)의 route와
back stack 원칙을 따르되 참조 앱의 버전 묶음을 통째로 올리지 않는다.

## 11. 에셋 최종 결정표

참조 WebP 7개의 실제 SHA-256은 `asset-manifest.json`과 모두 일치함을 다시 확인했다.

| 에셋 | 적용 판단 | 용도 |
|---|---|---|
| `hero_recording_chamber.webp` | 조건부 적용 | 녹음 hero |
| `onboarding_record.webp` | 조건부 적용 | 온보딩 1 |
| `onboarding_archive.webp` | 조건부 변형 | 온보딩 2/보관함 소개 |
| `empty_notes_desk.webp` | 조건부 변형 | 보관함 empty state |
| `texture_archive_paper.webp` | 조건부 적용 | 전사·요약 상세 paper surface |
| `empty_sync_bridge.webp` | 제외 | 서버 sync 기능 없음 |
| `error_server_offline.webp` | 제외 | 서버 연결 화면 없음 |
| source launcher icons | 제외 | LongSTT 브랜드 별도 필요 |
| `ic_notification_mic.xml` | 구조 참고 | LongSTT용 vector로 재작성 |

조건:

- 참조 저장소 루트의 전체 코드 LICENSE는 확인되지 않았다.
- 같은 조직이라도 소유자 승인 또는 명시적 사내 정책을 확인한다.
- 승인 시 원본 commit, manifest, license 문서를 함께 보존한다.
- 불명확하면 동일 시각 방향으로 LongSTT 전용 이미지를 새로 생성한다.

## 12. 테스트·QA 적용 최종안

### 12.1 거의 그대로 변형 가능한 테스트

| 참조 테스트 | 적용 |
|---|---|
| `ThemeContrastTest` | Archive token AA 회귀 |
| `RecorderCommandActorTest` | START/STOP FIFO |
| `RecordingRuntimeTest` | amplitude reset과 command error 분리 |
| `RecordingSessionOutcomeTest` | 실패 terminal 보존 |
| `StoragePolicyTest` | 저장공간 경계 |
| `ComposeScreensTest` | idle/recording/light/storage/semantics |
| `RecorderServiceDeviceTest` | duplicate start/stop/final file/no `.part` |

### 12.2 강화해서 적용할 테스트

- FileManager finalize 성공/0바이트/rename 실패/hash 실패/quarantine 실패/reconcile
- AtomicFile 원본·backup·unknown schema
- permission 허용/거절/영구 거절
- notification stop과 앱 stop의 동일 terminal 결과
- Activity recreation과 service snapshot 복원
- 녹음/STT/요약 coordinator 상호 배제
- group coordinator 0/1/N 청크, child 실패, 중단·재개, 순서 보존
- 기존 6시간 STT checkpoint와 media index read regression
- 360×800, 412×915, font scale 100/130/200%, light/dark/landscape

### 12.3 기기 QA 격리

참조 앱의 다음 운영 패턴은 현재 앱에 적극 적용한다.

- 승인된 Samsung serial/manufacturer/model을 모두 확인한 뒤 실행
- instrumentation은 `.deviceTest` package만 설치·삭제
- persistent preview/제품 package 데이터는 읽거나 초기화하지 않음
- user transcript를 logcat 증거에 수집하지 않음
- device facts, APK hash, Android version, battery, storage를 별도 evidence directory에 기록

## 13. 최종 우선순위

### Gate 0 — 구현 전

- 작업 트리 checkpoint commit
- README OAuth 상태 정정
- 기존 STT 실기기 상태 gate 확인
- 참조 코드/에셋 재사용 승인
- `deviceTest` package 격리

### Phase 1 — 디자인 기반과 App shell

- Archive theme/font fallback/contrast test
- common components
- 4 route navigation
- 2페이지 onboarding
- 기존 화면을 임시 전사 route에 유지

### Phase 2 — 녹음 안전 core

- recording state/store/file manager/storage policy/coordinator
- actor/controller/service/notification
- unit + service device test

### Phase 3 — 녹음 첫 화면

- hero/status/timer/wave/control
- 권한·저장공간·최근 세션·진단
- lifecycle/accessibility tests

### Phase 4 — LongSTT 통합

- MediaLibrary schema 확장
- recording group store/coordinator
- 단일 파일 TranscriptionService 순차 호출
- 통합 timestamp/result

### Phase 5 — 보관함·설정 재편

- Notes 시각 문법을 보관함에 적용
- 기존 결과/모델/Codex/diagnostics 재배치
- 1,167행 screen과 851행 ViewModel 분해

### Phase 6 — 최종 QA

- START/STOP 20회
- background/lock/rotation/process recreation
- 20분 경계 gap/overlap
- 1시간 → 6시간 녹음 + 그룹 순차 STT
- Debug/Release/lint/16KB/accessibility/license gate

## 14. 최종 승인안

### 적용

- Quiet Archive 디자인 시스템
- 온보딩
- 녹음 첫 화면
- 4개 탭 App shell
- 실제 microphone FGS와 파일 안전성
- 저장공간·최근 녹음·재생·진단
- Notes의 목록/장문 가독성 문법을 보관함으로 변형
- Settings section 구조
- 테스트·에셋 검증·기기 격리 패턴

### 새로 구현

- AtomicFile 녹음 세션 store
- 녹음/STT/요약 `DeviceWorkCoordinator`
- 녹음 그룹 전사 store/coordinator
- 녹음 session 중심 media schema
- route별 ViewModel과 통합 보관함

### 제외

- 서버/업로드/동기화/backend
- Room/Hilt/WorkManager
- 예약 녹음
- Notes 편집·WikiLink
- Local AI 모듈
- 참조 OAuth/cloud 계정 모듈
- toolchain 전체 업그레이드

이 결정안이 현재 기능 보존, 장시간 녹음 안전성, GUI 품질, 개발 복잡도 사이의 균형이 가장 좋다.

## 15. 2026-08-08 실제 적용 결과

- 참조 commit은 설계 비교 기준으로만 유지하고 라이선스가 불명확한 코드·raster 이미지를 직접 복사하지 않았다.
- Quiet Archive semantic theme, 2페이지 onboarding, 4 route app shell과 procedural copper thread artwork를 신규 구현했다.
- AtomicFile recording store, file finalize/quarantine/reconcile, coordinator, typed microphone FGS와 AAC/WAV fallback을 구현했다.
- Phase 4에서 `RecordingViewModel`, 실제 timer/amplitude, 권한 3단계, 저장공간, 최근 녹음, navigation indicator와 reduced motion을 연결했다.
- Samsung Android 16에서 앱/notification stop, 홈·탭 이동, Activity recreation을 포함한 실제 마이크 smoke를 통과했다.
- 적용 화면 12개와 최신 구조·검증 상태는 루트 `README.md`와 `docs/screenshots/`에 기록했다.
- 현재 권장 순서의 다음 항목은 녹음 session을 기존 MediaLibrary와 그룹 순차 STT에 연결하는 Phase 5다.

## 16. 2026-08-08 Phase 5 적용 결과

- READY 녹음 청크의 실제 크기·SHA-256 재검사와 MediaLibrary idempotent 등록을 구현했다.
- 완전 세션과 일부 보존 세션을 구분하고 partial은 제외 범위 확인 뒤에만 실행한다.
- 부모 group/ordered child checkpoint와 STT schema v2 연결 메타데이터를 AtomicFile로 저장한다.
- coordinator는 참조 앱 구현을 복사하지 않고 기존 단일 파일 `TranscriptionService`만 순차 호출하도록 신규 구현했다.
- 보관함은 부모 그룹, child 결과, 직접 녹음 원본을 함께 표시하면서 숨기기·원본 삭제·결과 삭제를 분리한다.
- Samsung Android 16 실제 6초 녹음과 base-q5_1 모델로 READY → MediaLibrary → group/child `COMPLETED`를 확인했다.
- README/실기기 캡처/검증 문서를 Phase 5 기준으로 갱신했다.
- 다음 권장 구현은 Phase 6 기존 전사/결과/설정 UI와 ViewModel의 책임 분리다.

## 17. 2026-08-08 Phase 6 적용 결과

- 대형 `SttBenchmarkScreen`을 얇은 호환 wrapper와 전사 전용 screen으로 분해했다.
- 전사에는 모델 선택·오디오 선택·실행·진행만 남기고, 결과는 보관함, 모델 설치·기기 정보·Codex·개발 진단은 설정으로 이동했다.
- 전사·보관함·설정의 dialog/선택 상태를 route별 `SavedStateHandle` ViewModel로 분리하면서 service/native engine 소유권은 기존 계층에 유지했다.
- 보관함은 stable ID/path 기반 복원과 stale 대상 dismiss를 적용하고 기존 결과·그룹·원본 삭제 경계를 보존했다.
- 설정을 section label·hairline divider 문법으로 정리했으며 성능 기록과 자동화는 Debug/diagnostics 경계 안에 유지했다.
- Samsung Android 16에서 신규 route, 모델 catalog와 표시 오디오 실행 계약을 확인했다. JVM 95개와 계측 10개가 통과했고 AICore 1개는 opt-in skip이다.
- README와 화면 캡처 17개를 Phase 6 기준으로 갱신했다.
- 다음 권장 구현은 Phase 7 접근성·다중 화면 크기·1시간/6시간 장시간·릴리스 QA다.

## 18. 2026-08-08 Phase 7 적용 결과 (중간)

- 참조 GUI의 readable navigation 원칙을 유지하면서 Samsung 200% font scale의 bottom label 충돌을 수정했다. icon content description은 유지하고 visible label만 compact 처리해 접근성 의미를 줄이지 않았다.
- 참조 앱의 lifecycle 아이디어를 그대로 복사하지 않고, 현재 AtomicFile terminal checkpoint와 typed foreground service 구조 안에서 recorder start/stop race와 group child handoff race를 해결했다.
- Samsung Android 16에서 20회 녹음 반복, 20분 rollover, 홈 이동 후 2-child sequential STT를 실제 검증했다. group 첫 시도에서 Android 16 background foreground-service 제한을 재현했고, terminal 저장 뒤 service 내부에서 다음 child launch를 준비하는 최소 수정으로 재시도 성공을 확인했다.
- Release에는 Debug audit/probe activity가 들어가지 않으며, debug audit도 transcript/path/ID를 노출하지 않고 completion/coverage aggregate만 표시한다.
- 적용 결과는 디자인 이식 완료나 release-ready 선언이 아니다. 1시간·6시간·8시간 장시간, 실제 잠금/제품 notification stop, external audio/interruption, OAuth 실계정 검증은 남아 있다.

## 19. 2026-08-08 Phase 7 1시간 제품 검증 후속

- Quiet Archive 녹음 화면의 실제 제품 흐름으로 Samsung Android 16에서 `1:03:54` 녹음과 4개 final M4A 청크를 생성했다. 사용자 화면·원문을 수집하지 않는 Debug-only aggregate audit으로 파일 final, MediaLibrary, duration 정합성을 확인했다.
- 4개 청크 그룹은 홈 화면 background 상태에서 순차 STT를 완료했고 aggregate 결과는 `AUDIT_PASS_CHILDREN=4_COVERAGE=COMPLETE`다. completion 뒤 두 foreground service와 held wake lock은 모두 남지 않았다.
- 이번 장시간 경로는 UI 참조 소스를 복사하지 않고 현재 앱의 AtomicFile/foreground service 계약을 보완했다. MediaLibraryStore instance 간 index update 유실은 process-wide lock으로, service와 ViewModel의 중복 child launch는 ViewModel fallback 제거로 수정했고 JVM 회귀 테스트를 추가했다.
- 업데이트된 static evidence: 109 JVM tests, Debug/Release/deviceTest/deviceTestAndroidTest assemble, Debug/Release lint error 0, 16KB alignment, Release debug-surface gate, `git diff --check` 통과.
- 이 검증은 1시간 범위만 충족한다. 6시간·8시간, 실제 잠금/제품 notification stop, external route/interruption, 실제 OAuth 계정은 여전히 Release blocker다.

## 20. 2026-08-08~09 Phase 7 6시간 제품 검증 후속

- Quiet Archive 녹음 화면에서 약 6시간 제품 녹음을 유지해 19개 final M4A 청크를 만들었다. 30분~5시간 checkpoint에서 recorder foreground service가 유지됐고, 결과는 MediaLibrary/duration aggregate audit으로 확인했다.
- 해당 세션은 제품 목록의 순차 STT action으로 시작해 홈 background에서 19개 child를 모두 완료했다. `AUDIT_PASS_CHILDREN=19_COVERAGE=COMPLETE` 이후 foreground service와 held wake lock은 남지 않았다.
- 이번 결과는 녹음 직접 경로의 장시간 실증이다. reference GUI 코드를 복사하지 않았고, 앱의 AtomicFile checkpoint·MediaLibrary·typed foreground service 계약을 그대로 사용했다.
- top-most debug audit Activity가 이전 audit 결과를 재표시하지 않도록 새 intent에서 aggregate 결과를 refresh하는 보완을 추가했다. 이 debug-only surface는 Release gate에서 계속 제외된다.
- 보완 뒤 full static gate는 109 JVM tests(failure/error/skip 0), 모든 Debug/Release/deviceTest assemble, lint error 0, 16KB, Release surface, `git diff --check`를 다시 통과했다.
- 8시간 soak, actual lock/제품 notification stop, external input/interruption, existing file import/단일 파일 장시간 STT, 실제 OAuth는 아직 미완료라 Release-ready 선언이 아니다.

## 21. 2026-08-09 8시간 minimum 초과 soak 후속

- Quiet Archive 녹음 화면으로 8시간 minimum을 초과하는 제품 soak을 수행해 35개 final M4A를 생성했다. 파일 final/MediaLibrary/duration은 debug-only aggregate audit으로 일치했다.
- 2·4·6시간 checkpoint에서 typed recorder foreground service가 살아 있었고, 화면 자동 소등(`Dreaming`) 상태에서도 유지됐다. 충전 상태에서 battery 100%, 온도 31.6~36.1°C, 여유 저장공간 약 144.0~143.4GiB를 기록했다.
- finalization 후 recorder/transcription foreground service와 held wake lock은 남지 않았다. 이 결과는 duration/thermal/storage 신뢰성을 강화하지만 actual lock/notification stop, external input/interruption, import 회귀, OAuth의 미완료 상태를 바꾸지 않는다.

## 22. 2026-08-09 notification action 제품 보완

- foreground recorder notification의 `녹음 정지` action을 제품에서 실제 사용 가능하게 만들기 위해 Android 13+ notification runtime permission을 녹음 시작 흐름에 연결했다. 이 변경은 참조 GUI가 아니라 현재 recorder service의 사용자 종료 경계를 완성하는 작업이다.
- Samsung Android 16에서 permission allow, home background, notification expand, `녹음 정지`, re-entry를 실제 수행해 terminal service/wake lock cleanup을 확인했다.
- 보완 뒤 full static gate는 112 JVM tests(failure/error/skip 0), 모든 Debug/Release/deviceTest assemble, lint error 0(Debug/Release warnings 24/27), 16KB, Release surface, `git diff --check`를 통과했다.
- 화면 소등 상태에서도 recorder service는 유지됐지만 기기에 secure lock이 없어 actual lock/잠금 해제 QA는 여전히 남아 있다. external audio/interruption, import 회귀, OAuth도 Release blocker로 유지한다.

## 23. 2026-08-09 Android file import 실제 smoke

- Long STT의 app-owned AudioPicker dialog와 시스템 DocumentsUI를 거쳐 외부 무음 WAV fixture를 실제 import했다. 이후 기존 단일-file foreground STT가 완료되고 terminal service/wake lock이 정리됐다.
- 이 증거는 reference GUI가 아닌 현재 MediaLibrary URI copy 경계를 검증한다. 장시간 단일 file 회귀, external audio/interruption, secure lock, OAuth는 여전히 별도 Release blocker다.

## 24. 2026-08-09 Debug audit freshness 보완

- 장시간 STT 결과를 원문 없이 검증하는 Debug-only `DebugSttAuditActivity`가 기존 최상단 상태에서 새 launch intent를 받을 경우에도 최신 aggregate를 다시 계산하도록 보완했다. Compose state로 결과를 갱신하며 Release source set에는 포함되지 않는다.
- 최신 제품 audit은 19-child coverage complete와 35-chunk recording consistency만 aggregate로 확인했고, 최신 full gate는 112 tests, lint error 0(Debug/Release 24/27 warnings), Debug/Release/deviceTest assemble, 16KB, Release surface, `git diff --check`을 통과했다.
- 이 보완은 검사 결과의 freshness만 높인다. actual secure lock, external audio/interruption, 기존 장시간 단일 file STT, 실제 OAuth는 여전히 Release blocker다.

## 25. 2026-08-09 입력 route 관측 경계

- 현재 앱의 recorder backend에 Android audio-routing callback을 연결해 generic input category만 표시한다. 이 방식은 참조 앱의 hardware UI를 복사하지 않고, 현재 `MediaRecorder`/`AudioRecord` lifecycle 및 Compose semantics 안에서 실제 route QA의 관측성을 높인다.
- Samsung 내장 마이크 제품 smoke에서 route UI와 terminal cleanup을 확인했고, generic mapping unit test를 추가했다. Bluetooth/USB physical input, phone/alarm interruption은 연결된 실제 장비·상황 없이 완료로 취급하지 않는다.
- 최신 정적 근거는 113 tests, Debug/Release lint error 0(24/27 warnings), Debug/Release/deviceTest assemble, 16KB, Release surface, `git diff --check`이다.

## 26. 2026-08-09 기존 장시간 single-file QA 준비 경계

- Debug-only readiness audit으로 현재 MediaLibrary의 existing imported long single-file candidate 유무를 filename/path/content 노출 없이 확인한다. Release gate는 이 audit activity를 Release manifest에서 배제한다.
- Samsung 실기기 aggregate 결과는 candidate 없음이다. 따라서 이미 완료된 short import smoke나 agent-owned synthetic data를 existing long single-file regression의 증거로 대체하지 않는다.
- source audio가 실제로 준비되기 전에는 long single-file STT와 external hardware/interruption/OAuth를 Release blocker로 유지한다.

## 27. 2026-08-09 OAuth readiness 경계

- 제품 설정 route의 generic authentication state는 signed out으로 확인했다. 이 read-only readiness 확인은 실제 authorization, browser 상호작용, token restore/probe/logout을 실행하지 않는다.
- 실계정 검증 전에는 Codex OAuth 동작 또는 외부 전사 전송 완료로 표현하지 않으며, token/account/transcript는 계속 UI·로그·문서 범위 밖에 둔다.
