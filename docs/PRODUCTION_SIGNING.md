# 프로덕션 서명 파이프라인

기준일: `2026-08-15 KST`<br>
상태: **구현·fail-closed·일회성 외부 JKS 종단간 검증 완료**

> [!IMPORTANT]
> 이 저장소는 production keystore·비밀번호를 생성하거나 저장·출력·커밋하지 않는다. Coreline upload key는 승인된 secret 저장소에서 백업·접근 통제한다. 분실·교체 시 Play Console의 upload key 복구 절차가 필요하다.

## 파이프라인 동작

`./gradlew :app:productionReleaseBundle`은 production 전용 fail-closed gate다.

1. 모든 외부 signing input을 먼저 확인한다.
2. `release` build type을 `productionRelease` signing config에 연결한다.
3. `app/build/outputs/bundle/release/app-release.aab`를 생성한다.
4. JDK `jarsigner`로 AAB 서명을 검증한다.
5. `app/build/outputs/security/release-provenance.json`에 artifact SHA-256, version, source revision, 생성 시각과 서명 검증 결과를 기록한다.

provenance에는 key·alias·password가 포함되지 않는다. signing 값이 하나라도 없거나 keystore 파일을 읽을 수 없으면 `:app:verifyProductionSigning`이 bundle 작업 전에 일반 안내만 출력하고 실패한다.

일반 `:app:bundleRelease`는 개발·자동 검증용 unsigned AAB를 계속 만들 수 있다. 이 파일은 배포본이 아니다.

## 로컬 릴리스 절차

아래 환경 변수 또는 동등한 Gradle property를 사용한다.

| 환경 변수 | Gradle property |
|---|---|
| `LONG_STT_RELEASE_STORE_FILE` | `longSttReleaseStoreFile` |
| `LONG_STT_RELEASE_STORE_PASSWORD` | `longSttReleaseStorePassword` |
| `LONG_STT_RELEASE_KEY_ALIAS` | `longSttReleaseKeyAlias` |
| `LONG_STT_RELEASE_KEY_PASSWORD` | `longSttReleaseKeyPassword` |

값을 Git 추적 `gradle.properties`에 기록하지 않는다.

```bash
export LONG_STT_RELEASE_STORE_FILE="/secure/path/coreline-upload.jks"
export LONG_STT_RELEASE_STORE_PASSWORD="<keystore-password>"
export LONG_STT_RELEASE_KEY_ALIAS="<key-alias>"
export LONG_STT_RELEASE_KEY_PASSWORD="<key-password>"

./gradlew :app:productionReleaseBundle
```

업로드 전 AAB와 JSON provenance를 함께 보관하고 독립 확인한다.

```bash
"$JAVA_HOME/bin/jarsigner" -verify -certs \
  app/build/outputs/bundle/release/app-release.aab

shasum -a 256 app/build/outputs/bundle/release/app-release.aab
cat app/build/outputs/security/release-provenance.json
```

## GitHub Actions

[`.github/workflows/production-release.yml`](../.github/workflows/production-release.yml)은 `workflow_dispatch` 전용이며 보호된 GitHub `production` Environment를 사용한다.

| Secret | 용도 |
|---|---|
| `LONG_STT_RELEASE_STORE_BASE64` | upload keystore Base64; ephemeral runner temp에만 복호화 |
| `LONG_STT_RELEASE_STORE_PASSWORD` | keystore password |
| `LONG_STT_RELEASE_KEY_ALIAS` | upload key alias |
| `LONG_STT_RELEASE_KEY_PASSWORD` | upload key password |

production Environment에는 reviewer 보호와 최소 release operator만 설정한다. Pull Request에는 secret을 제공하지 않는다.

workflow는 signed AAB와 non-secret provenance를 14일 보존 artifact로 올리며 Google Play에 자동 게시하지 않는다. Release operator가 artifact identity, Play Console track, release notes, 개인정보 안내, Data safety, staged rollout을 별도로 확인한다.

## 검증 기록

- secret 미주입: production task가 `verifyProductionSigning`에서 의도대로 실패하고 bundle 단계로 진행하지 않음
- 저장소 밖 일회성 JKS: signed AAB 생성, `jarsigner` 검증, SHA-256 provenance 생성 성공
- 검증 뒤: test keystore와 test provenance 삭제, 로컬 AAB를 일반 unsigned 검증 산출물로 복원
- Git 위생: `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `/signing/`, `/release-signing.properties` ignore 적용

이 검증은 파이프라인 코드의 동작을 확인한 것이다. 실제 Coreline upload key를 사용한 Play track 업로드는 릴리스 운영 시 수행한다.

## 키 관리 규칙

- 원본 upload keystore는 Coreline 소유의 제한된 secret 시스템에 백업한다.
- production Environment 접근자는 최소화하고 reviewer 승인을 적용한다.
- key rotation·복구는 배포 플랫폼의 공식 계정 절차로만 수행한다.
- keystore, password, secret screenshot, signed artifact, production provenance를 소스 커밋에 포함하지 않는다.
