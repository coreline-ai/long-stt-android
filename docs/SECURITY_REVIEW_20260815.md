# Android 보안 검토 보고서

- **대상:** Long STT Android (`main`, 2026-08-15 정적 검토)
- **방법:** Android manifest·Kotlin/JNI·OAuth·파일/네트워크 경로·빌드 설정·의존성 메타데이터 검토, `lintRelease` 실행
- **제외:** 악성 앱을 설치한 실기기 침투 시험, 루팅/잠금 해제 기기, 실제 OAuth 계정·네트워크 패킷, 서버/외부 제공자 인프라 및 법률·약관 검토

현재 전체 완료 상태와 릴리스 운영 기준은 [`HANDOFF_20260815.md`](HANDOFF_20260815.md)를 우선한다. 이 보고서의 SEC-004/005/007/008/009는 선택적 방어 심화·제품 정책 후보이며 현재 합의된 배포 필수 4개 항목에는 포함되지 않는다.

## 요약

현재 소스는 앱 내부 저장소 사용, 백업/기기 이전 제외, 제한된 `FileProvider`, 비공개 서비스, Debug 로그 경계, Android Keystore 기반 OAuth 토큰 암호화, PKCE/state 검증 등 **좋은 기본 방어선**을 갖추고 있다. 저장소에서 알려진 API 키·개인 키 패턴도 발견되지 않았다.

초기 정적 검토에서 공개 배포 전 우선 처리 대상으로 확인한 **모델 다운로드 무결성 검증**, **Google Play 대상 SDK 36 전환**, **OAuth loopback 콜백의 로컬 DoS 차단**, **프로덕션 signing pipeline**은 이 문서의 후속 구현으로 반영했다. 민감 음성/전사 보관 정책 등 나머지 권고는 별도 제품·보안 범위다. 이 검토에서 계정 탈취·원격 코드 실행으로 이어지는 확정 취약점은 발견하지 못했다.

## 2026-08-15 후속 구현 상태

아래 4개는 이번 변경에서 소스와 자동 검증에 반영됐다. 각 SEC 절의 기존 근거는 **수정 전 정적 검토 기록**으로 보존한다.

| 항목 | 상태 | 반영 내용 |
|---|---|---|
| SEC-001 · API 36 | 완료 | app/OAuth module을 `compileSdk`/`targetSdk` 36으로 통일하고 AGP 8.10.1·Gradle 8.11.1로 전환 |
| SEC-002 · 모델 공급망 | 완료 | immutable Hugging Face revision, 정확한 byte size·SHA-256, HTTPS/허용 host/manual redirect/streaming digest 검증 |
| SEC-003 · OAuth loopback | 완료 | socket callback 경계에서 expected state를 constant-time 비교하고 state 없는 error callback도 차단 |
| SEC-006 · 프로덕션 서명 | 완료 | 저장소 밖 secret 주입, fail-closed signing gate, signed AAB 검증·SHA-256 provenance, protected Environment용 수동 workflow |

이번 변경의 자동 검증은 API 36 Release build, 모델 무결성 단위 테스트, OAuth loopback state-first 단위 테스트, keyless signing gate의 의도된 실패, 저장소 밖의 일회성 test keystore를 사용한 signed AAB/provenance 종단간 성공을 포함한다. 테스트 keystore는 생성 직후 삭제했으며 저장소에 남기지 않았다.

## 심각도 기준

| 등급 | 의미 |
|---|---|
| P0 | 예정된 공개 배포를 막는 플랫폼/보안 기준 미충족 |
| P1 | 공개 배포 전 수정 또는 통제 수립이 필요한 높은 위험 |
| P2 | 다음 보안 하드닝 릴리스에서 처리할 중간 위험 |
| P3 | 방어 심화 또는 제품 정책 개선 |

## 확인된 항목 (수정 전 근거와 남은 권고)

### SEC-001 — P0 · `targetSdk` 34는 공개 Play 배포 기준 미충족 · **해결됨**

**근거**

- `app/build.gradle.kts:216,224`은 `compileSdk = 34`, `targetSdk = 34`이다.
- 이번 검토에서 실행한 `lintRelease`도 `OldTargetApi` 경고를 보고했다.
- Google Play는 2026-08-31부터 신규 앱·업데이트에 Android 16(API 36) 이상을 요구한다. [공식 대상 API 안내](https://developer.android.com/google/play/requirements/target-sdk)

**영향**

이는 코드 침해 취약점은 아니지만, Android 최신 보안 동작을 opt-in하지 못하고 공개 Play 업로드가 차단되는 배포 차단 항목이다.

**권장 조치**

1. `compileSdk`/`targetSdk`를 API 36으로 올린다.
2. Android 15·16에서 마이크 foreground service, 알림, 파일 선택, OAuth Custom Tab 및 장시간 전사를 회귀 시험한다.
3. API 26~36 기기 매트릭스와 release AAB로 최종 확인한다.

---

### SEC-002 — P1 · 내려받는 Whisper 모델의 암호학적 무결성 검증 부재 · **해결됨**

**근거**

- `app/src/main/java/com/stt/benchmark/data/ModelDownloader.kt:22,26-32`는 mutable `main` 경로의 모델 URL과 파일명/표시 크기만 보관한다.
- 같은 파일 `86-125`는 HTTPS 다운로드와 `Content-Length` 일치만 확인하고 SHA-256·서명·고정 revision을 확인하지 않는다. 리다이렉트도 허용한다.
- 해당 모델은 `app/src/main/cpp/jni.c:145-153`에서 네이티브 `whisper_init_from_file_with_params()`의 입력이 된다.

**영향**

전송 TLS는 보호되지만, upstream 변경·공급망 사고·예상치 못한 리다이렉트로 다른 모델이 제공되어도 앱은 정상 모델로 취급한다. 대용량 바이너리를 네이티브 파서로 넘기므로 무결성 검증은 특히 중요하다.

**권장 조치**

1. 각 `ModelInfo`에 **정확한 SHA-256과 바이트 상한/기대 크기**를 정본으로 추가한다.
2. 모델 URL을 immutable commit/revision으로 고정하고, 다운로드 뒤 스트리밍 SHA-256이 일치할 때만 `.part → final` rename한다.
3. redirect를 끄거나 HTTPS + 허용 host 목록으로 한정한다. 실패한 파일은 삭제하고 사용자에게 무결성 실패만 노출한다.
4. 정상·해시 불일치·크기 초과·redirect host 변경·취소 테스트를 추가한다.

---

### SEC-003 — P2 · OAuth loopback 콜백의 state 검증 시점으로 인한 로컬 로그인 DoS · **해결됨**

**근거**

- `codex-oauth-android/src/main/AndroidManifest.xml:6-35`의 `OAuthRedirectActivity`는 브라우저 콜백을 위해 exported 되어 있다.
- `OAuthRedirectActivity.kt:23-30`은 Activity 경로에서는 path/state를 확인하는 좋은 방어를 갖고 있다.
- 그러나 `OAuthCallbackServer.kt:105-138`은 loopback socket에서 받은 callback을 state 확인 없이 `onCallback`에 전달한다.
- `OAuthManager.kt:73-80,103-110`은 첫 callback으로 `CompletableDeferred`를 완료한 뒤 state를 검증한다.
- `OAuthFailure.kt:50-75`는 `error=access_denied`를 state 비교보다 먼저 사용자 취소로 분류한다. 현재 단위 테스트도 state 없는 error callback을 정상 수신으로 검증한다(`OAuthCoreTest.kt:49-85,363-397`).

**영향**

동일 기기의 악성 앱/로컬 프로세스가 로그인 진행 중 loopback 포트에 먼저 `error=access_denied` 또는 잘못된 state를 보내면 해당 로그인 시도를 실패시킬 수 있다. PKCE verifier와 최종 state 검증 때문에 **토큰 탈취 근거는 발견하지 못했으며**, 위험은 로그인 거부(DoS)로 한정된다.

**권장 조치**

1. `OAuthCallbackServer`가 예상 state를 받아 constant-time 비교를 **callback 완료 전** 수행하게 한다. `code`와 `error` 경로 모두 state가 일치해야 한다.
2. state 불일치/누락 요청은 400을 반환하고 listener를 유지하여 실제 브라우저 콜백을 기다린다.
3. localhost 직접 socket, exported Activity explicit intent, 포트 선점, 중복 callback을 포함한 악성 앱 시나리오 instrumentation test를 추가한다.
4. 제공자가 지원한다면, 소유 도메인의 검증된 Android App Links 또는 등록된 앱 전용 redirect 방식으로 전환 가능성을 검토한다. App Links는 도메인 소유 검증으로 다른 앱의 링크 가로채기를 줄인다. [공식 App Links 안내](https://developer.android.com/training/app-links/about)

---

### SEC-004 — P2 · 민감 원본/전사/채팅 파생 데이터의 평문 보관 및 보관 기한 정책 부재

**근거**

- 원본 음성과 전사 checkpoint가 앱 내부 `filesDir`에 보관된다. 예: `SttViewModel.kt:660-677`, `TranscriptionSessionStore.kt:13-47`.
- 요약·대화·정밀 탐색 파생 데이터도 JSON으로 저장된다. 예: `SummarySessionStore.kt:8-23,62-82`, `TranscriptChatSessionStore.kt:11-37,86-105`, `TranscriptPreciseSearchStore.kt:11-29,49-60`.
- 반면 `app/src/main/AndroidManifest.xml:27-38`와 `data_extraction_rules.xml:2-14`는 Android backup/device transfer를 차단한다. 이는 올바른 기본 방어다.

**영향**

일반 앱 간 접근은 sandbox와 기기 파일 기반 암호화로 제한되지만, 루팅·잠금 해제·물리 접근 뒤의 기기 위협 모델, 고감도 회의/고객 음성에 대한 별도 보호 요구에는 충분하지 않을 수 있다. 또한 `forget`은 일부 항목을 목록에서만 숨기도록 설계되어 있어(`SttViewModel.kt:966` 부근) 보존 범위를 사용자가 혼동할 수 있다.

**권장 조치**

1. 출시 전 개인정보 안내에 **로컬 보관 대상·외부 전송 조건·삭제 범위·보관 기간**을 명시한다.
2. 고감도 모드가 필요하면 앱 잠금(생체 인증)과 Android Keystore 기반 파일 암호화/키 폐기를 설계한다. native 엔진이 파일을 요구하므로 복호화는 수명 제한 cache에서만 수행하고 작업 직후 제거한다.
3. 오디오·전사·요약·채팅 인덱스를 함께 지우는 명시적 “모든 데이터 삭제”와 항목별 실제 삭제 UI를 제공한다. 플래시 저장소에서 overwrite를 보장한다고 주장하지 말고 암호키 폐기와 논리 삭제 범위를 안내한다.

---

### SEC-005 — P2 · SAF 오디오 가져오기에 크기/저장공간 한도가 없음

**근거**

- `SttViewModel.kt:660-671`은 선택한 `content://` URI 스트림을 앱 내부 파일로 복사한다.
- `AudioImportFileWriter.kt:8-23`은 `input.copyTo(output)`에 파일 크기 상한·사용 가능 저장공간 확인을 두지 않는다.
- 외부 provider의 데이터는 신뢰할 수 없는 입력이며, 뒤에서 디코더/네이티브 전사 경로로 전달된다.

**영향**

사용자가 대용량 파일을 선택하거나 악성/오류 provider가 끝나지 않는 스트림을 제공하면 저장공간 고갈, 긴 처리, 후속 전사 실패가 발생할 수 있다. 직접 권한 상승 취약점은 아니지만, 민감 기록의 정상 저장과 가용성에 영향을 준다.

**권장 조치**

1. 지원 최대 바이트/최대 재생 시간과 최소 여유 공간을 제품 기준으로 정하고, 복사 전 metadata와 `StatFs.availableBytes`를 확인한다.
2. metadata가 없거나 거짓이어도 복사 중 누적 바이트 상한을 강제하고, 초과 시 `.part`를 삭제한다.
3. 허용 컨테이너 MIME/헤더를 검사하고, malformed audio/model을 대상으로 decoder·JNI fuzz/smoke test를 CI 또는 정기 보안 테스트에 넣는다. Android는 native code에 들어가는 파일/네트워크/IPC 입력을 모두 검증하라고 권고한다. [Android Security checklist](https://developer.android.com/privacy-and-security/security-tips)

---

### SEC-006 — P1 · 프로덕션 서명 체계가 소스/산출물에 아직 구성되지 않음 · **해결됨**

**근거**

- 현재 확인된 release 산출물은 `app/build/outputs/apk/release/app-release-unsigned.apk`다.
- `app/build.gradle.kts:255-273`에는 release `signingConfig`가 없다. 저장소에 keystore나 signing password가 없는 점 자체는 올바르다.

**영향**

서명되지 않은 APK는 신뢰할 수 있는 프로덕션 배포물이 아니다. 수동 서명 과정이 비표준이면 키 유출·교체·재현성 문제가 생길 수 있다.

**권장 조치**

1. upload key는 저장소 밖의 비밀 관리자/보안 CI에 보관하고, release는 signed AAB를 만들도록 배포 파이프라인을 정의한다.
2. CI에서 release certificate SHA-256, APK/AAB artifact SHA-256, versionCode, Git commit을 산출물 증적에 기록한다.
3. Play App Signing과 upload key rotation/분실 대응 책임자를 문서화한다. [Android App Signing 안내](https://developer.android.com/studio/publish/app-signing)

---

### SEC-007 — P2 · 의존성 무결성·취약점 감시 자동화 부족

**근거**

- `third_party/whisper.cpp.lock`은 whisper.cpp commit을 40자리 SHA로 고정하는 좋은 조치다.
- 반면 Gradle dependency lockfile 및 `verification-metadata.xml`은 저장소에서 확인되지 않았다.
- `lintRelease`는 AndroidX/Compose/Coroutine 등 여러 runtime dependency가 최신 버전보다 오래되었다고 보고했다. 이는 알려진 CVE의 확정 판정은 아니다.

**영향**

공개 배포 후 의존성 취약점·변조·업데이트 누락을 추적하기 어렵다. 특히 native whisper.cpp는 정기 업데이트와 malformed model/audio 회귀 검증이 필요하다.

**권장 조치**

1. Gradle dependency locking과 dependency verification metadata를 도입하고, CI에서 변경 diff를 검토한다.
2. Dependabot/Renovate 또는 동등한 SCA를 사용해 Gradle·native upstream 보안 업데이트를 알림/PR로 만든다.
3. 릴리스마다 CycloneDX/SPDX SBOM, native lock commit, license 목록, SHA-256을 보관한다.
4. 버전 업데이트는 보안 패치 우선으로 작은 묶음으로 올리고, OAuth·녹음·전사 회귀 시험 후 배포한다.

---

### SEC-008 — P3 · release R8 축소/난독화 비활성화

**근거**

- `app/build.gradle.kts:255-262`에서 `isMinifyEnabled = false`다.

**영향**

난독화는 비밀 보관 수단이 아니며 API token을 소스에 두어서는 안 된다. 다만 release 역분석 난이도와 공격 표면 정보를 조금 줄이는 방어 심화 수단은 된다.

**권장 조치**

R8를 별도 release 후보에서 켜고, JNI keep rule·OAuth redirect·Compose·반사 사용 여부를 release 실기기로 검증한 뒤 기본값으로 전환한다. `mapping.txt`는 접근 통제된 CI artifact로만 보관한다.

---

### SEC-009 — P3 · 화면 노출 보호는 선택적 제품 정책으로 검토

**근거**

- 전사/대화/요약은 민감할 수 있으나, source에서 `FLAG_SECURE` 사용은 확인되지 않았다.
- Android backup 차단과 제한된 공유 `FileProvider`는 이미 구현되어 있다.

**권장 조치**

회의·의료·고객 정보 등의 사용 사례가 대상이면 “최근 앱 화면 가리기 / 스크린샷 차단” 옵션을 제공하고, 해당 모드에서만 `FLAG_SECURE`를 적용한다. 기본 적용 전에는 사용자 export·지원 절차에 미치는 영향을 검토한다.

## 이미 잘 처리된 방어 항목

| 영역 | 확인 내용 |
|---|---|
| 백업 | `allowBackup=false`, cloud backup/device transfer 전체 제외 |
| 컴포넌트 | 전사/녹음 서비스와 `FileProvider`가 `exported=false`; 동적 상태 receiver도 `RECEIVER_NOT_EXPORTED` |
| 파일 공유 | cache 하위 `transcript_exports/`만 공유하고 read URI grant를 사용 |
| OAuth 토큰 | Android Keystore AES-GCM, private SharedPreferences, PKCE/state, 응답 크기·timeout 제한 |
| 네트워크 | OAuth/LLM endpoint 설정이 HTTPS를 요구하고, 헤더 CR/LF 주입을 차단 |
| LLM 경계 | 전사 전송 전 명시 동의, payload/응답 상한, LLM에 도구 권한을 부여하지 않음 |
| 로그 | `AppLog`는 Debug 전용이고 throwable 상세를 노출하지 않도록 경계화 |
| 릴리스 표면 | `verifyReleaseSurface`가 Debug automation activity/receiver와 direct Android Log 사용을 검사 |

Android는 exported component가 다른 앱에 의해 실행될 수 있으므로, 필요한 경우만 공개하고 입력을 검증해야 한다고 안내한다. 현재 기본 서비스/provider 격리는 이 권고에 부합한다. [android:exported 보안 안내](https://developer.android.com/privacy-and-security/risks/android-exported)

## 권장 실행 순서

1. **완료:** API 36 전환 및 Release build 회귀 검증.
2. **완료:** model SHA-256/revision/host 검증과 signed AAB 배포 파이프라인 구현.
3. **완료:** OAuth callback state-first 변경과 hostile loopback callback 회귀 테스트 추가.
4. **선택적 P2:** threat model이 확대되면 import 크기/여유공간 상한, 데이터 보관·실제 삭제 정책을 재평가.
5. **선택적 P2/P3:** 조직 보안 정책에 따라 SCA·SBOM·dependency verification, R8, 화면 보호를 재평가.

## 검증 결과

| 항목 | 결과 |
|---|---|
| 민감 파일명/대표 secret 패턴 정적 스캔 | 추적 파일에서 발견 없음 (값은 출력하지 않음) |
| release manifest/컴포넌트/backup/공유 경로 정적 검토 | 완료 |
| app JVM tests | **255 passed / 0 failed / 0 skipped** — 모델 고정 revision·digest·host 정책 포함 |
| OAuth JVM tests | **44 passed / 0 failed / 0 skipped** — state 누락/불일치 loopback callback 차단 포함 |
| `lintRelease` | **0 errors, 37 warnings** — `OldTargetApi`는 제거됐으며, 기존 minSdk/의존성/리소스 품질 경고가 남음 |
| Release artifact gates | `assembleRelease`, `bundleRelease`, `verify16KbAlignment`, `verifyReleaseSurface` 통과 |
| production signing | secret 미주입 gate fail-closed, 일회성 외부 JKS로 signed AAB·`jarsigner`·provenance 종단간 통과 후 key 삭제 |
| 실제 OAuth 계정 공격·네트워크 MITM·루팅 기기 침투 시험 | 이번 범위에서는 미실행 |

이 보고서는 코드 정적 검토와 위 자동 검증 결과다. 합의된 배포 전 필수 구현 4개는 완료됐다. 실제 Coreline upload key/Play Console을 쓰는 릴리스는 보호된 운영 환경에서 수행하며, 별도 악성 앱 동반 침투 시험은 threat model 확대 시 선택적으로 진행한다.
