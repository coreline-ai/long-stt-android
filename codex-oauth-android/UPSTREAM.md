# Codex OAuth Android upstream 기록

## 고정 원본

| 항목 | 값 |
|---|---|
| 저장소 | `https://github.com/coreline-ai/alpine-llm-gateway.git` |
| commit | `3389fcbf92207bb632dac002d7bb501d0901ff22` |
| 원본 모듈 | `android` |
| 원본 package | `dev.alpine.llm` |
| 현재 모듈 | `:codex-oauth-android` |
| 이식 목적 | STT 앱과 분리된 Codex OAuth/Responses 기반 모듈의 source parity 검증 |

이 모듈은 Alpine runtime, PRoot, chat UI, 다른 Provider 전용 contract를 포함하지 않는다.
현재 `:app`은 요약·전사 채팅의 명시적 사용자 동의 경계 안에서 이 모듈에 의존한다. 이 모듈의
manifest는 OAuth redirect activity와 인터넷 권한을 제공하며, 로그인 또는 외부 요청은 사용자의
명시적 조작 없이는 시작하지 않는다.

## 이식한 production source 19개

원본의 `android/src/main/java/dev/alpine/llm/`에서 package와 공개 API를 유지해 가져왔다. 이후 현재 앱의 보안 기준에 맞춰 아래 명시적 hardening 차이를 적용했다.

```text
CodexOAuthContract.kt
CodexResponsesOAuthAdapter.kt
GatewayOperations.kt
HostLlmBridge.kt
JwtClaimMetadataTokenResponseAdapter.kt
OAuthCallbackRegistry.kt
OAuthCallbackServer.kt
OAuthDiscovery.kt
OAuthFailure.kt
OAuthHttpLlmBridge.kt
OAuthManager.kt
OAuthPkce.kt
OAuthProviderConfig.kt
OAuthRedirectActivity.kt
OAuthRefreshCoordinator.kt
OAuthTokenRequestAdapter.kt
OAuthTokenResponseAdapter.kt
OAuthTokenStore.kt
ProviderProtocolAdapters.kt
```

함께 이식한 파일:

- `src/main/AndroidManifest.xml`
- `src/test/.../CodexResponsesOAuthAdapterTest.kt`
- `src/test/.../GatewayOperationsTest.kt`
- `src/test/.../OAuthCoreTest.kt`
- `src/test/.../OAuthHttpLlmBridgeTest.kt`
- `src/test/.../StreamingBridgeTest.kt`
- `src/androidTest/.../OAuthTokenStoreInstrumentedTest.kt`

## 의도적인 차이

### Kotlin 1.9.22 호환

```diff
--- upstream/ProviderProtocolAdapters.kt
+++ local/ProviderProtocolAdapters.kt
@@
                 "none" -> Unit
+                else -> Unit
```

원본은 Kotlin 2.x의 더 정교한 smart cast/exhaustiveness 분석에서는 컴파일되지만 현재 프로젝트의
Kotlin 1.9.22에서는 nullable JSON 문자열에 대한 `when`을 exhaustive로 인정하지 않는다. 알 수 없는
`tool_choice` 값도 기존처럼 아무 동작을 하지 않게 하므로 wire 동작은 바뀌지 않는다.

빌드 의존성은 현재 앱 기준에 맞췄으며, source 차이는 위 Kotlin 호환 변경과 아래 state-first hardening으로 한정한다.

| 항목 | upstream | 현재 모듈 |
|---|---:|---:|
| compile/target SDK | 36 | 36 |
| min SDK | 26 | 26 |
| Kotlin | 2.x 계열 | 1.9.22 |
| AndroidX Browser | 1.10.0 | 1.8.0 |
| coroutines | 1.9.0 | 1.7.3 |
| JVM | 17 | 17 |

### OAuth loopback state-first hardening

2026-08-15 보안 검토 후 다음 방어를 local source에 명시적으로 추가했다.

- `OAuthCallbackServer`가 expected state를 받아 constant-time 비교한 뒤 일치 요청만 callback으로 전달한다.
- state 누락·불일치 요청은 HTTP 400으로 거부하고 실제 브라우저 callback을 계속 기다린다.
- `OAuthManager`의 host cancel도 active transaction state를 사용한다.
- `OAuthCallbackValidator`는 provider error보다 transaction 만료와 state를 먼저 검증한다.
- hostile callback 뒤 정상 callback이 성공하는 JVM 회귀 테스트를 추가했다.

이 변경은 PKCE/token exchange 공개 계약을 바꾸지 않고 동일 기기 로컬 프로세스가 잘못된 callback으로 로그인 future를 선점하는 경로를 차단한다.

## 2026-08-07 검증 결과

```bash
./gradlew \
  :codex-oauth-android:testDebugUnitTest \
  :codex-oauth-android:assembleDebug \
  :codex-oauth-android:assembleDebugAndroidTest
```

- upstream 포팅 JVM 테스트: **43/43 통과**
  - OAuth core 18
  - OAuth HTTP bridge 8
  - streaming bridge 4
  - Codex Responses adapter 6
  - gateway operations 7
- Debug AAR 조립: 성공
- AndroidTest APK 조립: 성공
- Samsung SM-S931N (`R3CY40PXCAP`) Keystore 계측: **2/2 통과**
  - AES-GCM token 저장 → store 재생성 → 복원 → 삭제
  - preference/파일 평문 비노출과 AndroidKeyStore key non-exportability 확인

이 절의 결과는 2026-08-07 당시 source-parity 자동 검증 기록이다. 이후 앱 wrapper와 UI가 연결됐고, 실제 OAuth lifecycle과 전사 기반 LLM 기능은 프로젝트 담당자 확인 기준 완료됐다. 민감한 계정·원문·응답 증적은 저장소에 남기지 않는다. 최신 상태는 [`../docs/VERIFICATION_COMPLETION_20260814.md`](../docs/VERIFICATION_COMPLETION_20260814.md)를 따른다.

## 2026-08-15 후속 검증 결과

- OAuth JVM: **44 passed / 0 failed / 0 errors / 0 skipped**
- state 누락/불일치 loopback error callback 차단과 정상 callback 후속 수용 확인
- app Release surface와 API 36 build 통과
- Android Keystore token 보호 경계 유지

## 추가로 검토 가능한 hardening

loopback state-first는 적용 완료했다. 아래 항목은 현재 필수 배포 blocker가 아니라 향후 threat model·upstream 변경 시 재평가할 방어 심화 후보다.

- redirect activity의 추가 수명주기·verified App Links 전환 가능성
- token/transaction 저장 완료 시점과 process-death 복구 보강
- HTTP request/response/SSE 크기 및 시간 제한 재검토
- 로그·오류 메시지 redaction 회귀 테스트 확대
- OAuth 동시 실행, refresh 경쟁, logout 경쟁의 앱 수준 직렬화

추가 변경은 한 항목씩 적용하고 현재 44개 JVM 테스트와 Keystore 계측을 다시 통과시킨다.

## 배포 및 라이선스 gate

고정 원본 저장소에는 프로젝트 전체 코드에 적용되는 루트 `LICENSE`가 없고, 원본의
`distribution/PROJECT_LICENSE_STATUS.md`도 현 산출물을 내부 개발·검증용으로 제한한다.
위 상태는 제3자에게 프로젝트 코드의 일반 배포 권한을 부여하지 않는다는 뜻이다. Coreline은 이
source-parity 구성요소를 포함한 자체 릴리스·배포 여부를 결정할 수 있으며, Codex 관련 연동만으로
별도의 “Codex 정식 배포 승인”이 필요한 것은 아니다. 실제 릴리스 시에는 제3자 구성요소의 고지·
라이선스와 적용 서비스 약관을 확인한다. 이 문서는 제3자에게 별도 라이선스를 부여하지 않는다.
