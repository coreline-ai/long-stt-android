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

원본의 `android/src/main/java/dev/alpine/llm/`에서 package와 공개 API를 변경하지 않고 가져왔다.

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

production source의 upstream 대비 차이는 Kotlin 1.9.22 컴파일 호환을 위한 아래 1건뿐이다.

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

빌드 환경만 현재 앱 기준에 맞췄다.

| 항목 | upstream | 현재 모듈 |
|---|---:|---:|
| compile/target SDK | 36 | 34 |
| min SDK | 26 | 26 |
| Kotlin | 2.x 계열 | 1.9.22 |
| AndroidX Browser | 1.10.0 | 1.8.0 |
| coroutines | 1.9.0 | 1.7.3 |
| JVM | 17 | 17 |

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

이 결과는 실제 OAuth 로그인이나 Codex Responses 호출 성공을 의미하지 않는다. 앱 wrapper와 UI를
연결한 뒤 login/restore/refresh/logout 및 비민감 고정 요청을 별도 parity 단계에서 검증해야 한다.

## 보류한 hardening

source parity를 먼저 고정하기 위해 다음 변경은 아직 적용하지 않았다.

- redirect activity/loopback callback의 추가 발신자·수명주기 방어
- token/transaction 저장 완료 시점과 process-death 복구 보강
- HTTP request/response/SSE 크기 및 시간 제한 재검토
- 로그·오류 메시지 redaction 회귀 테스트 확대
- OAuth 동시 실행, refresh 경쟁, logout 경쟁의 앱 수준 직렬화

위 변경은 실제 upstream parity가 통과한 뒤 한 항목씩 적용하고 43개 테스트와 Keystore 계측을 매번
다시 통과시킨다.

## 배포 및 라이선스 gate

고정 원본 저장소에는 프로젝트 전체 코드에 적용되는 루트 `LICENSE`가 없고, 원본의
`distribution/PROJECT_LICENSE_STATUS.md`도 현 산출물을 내부 개발·검증용으로 제한한다.
위 상태는 제3자에게 프로젝트 코드의 일반 배포 권한을 부여하지 않는다는 뜻이다. Coreline은 이
source-parity 구성요소를 포함한 자체 릴리스·배포 여부를 결정할 수 있으며, Codex 관련 연동만으로
별도의 “Codex 정식 배포 승인”이 필요한 것은 아니다. 실제 릴리스 시에는 제3자 구성요소의 고지·
라이선스와 적용 서비스 약관을 확인한다. 이 문서는 제3자에게 별도 라이선스를 부여하지 않는다.
