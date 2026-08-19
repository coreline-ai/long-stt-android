# Long STT Android 문서 안내

기준일: `2026-08-19 KST`

이 디렉터리는 현재 운영 문서와 과거 검토·실험 기록을 함께 보존한다. 상태가 충돌하면 아래 우선순위를 따른다.

## 현재 정본

| 순서 | 문서 | 역할 |
|---:|---|---|
| 1 | [`../README.md`](../README.md) | 사용자 관점 기능·빌드·검증·라이선스 요약 |
| 2 | [`HANDOFF_20260815.md`](HANDOFF_20260815.md) | 다음 작업자가 사용할 최신 기술·운영 상태 |
| 3 | [`VERIFICATION_COMPLETION_20260814.md`](VERIFICATION_COMPLETION_20260814.md) | 사용자·실장비 검증 완료 확인 |
| 4 | [`SECURITY_REVIEW_20260815.md`](SECURITY_REVIEW_20260815.md) | 배포 전 보안 검토와 4개 필수 구현 결과 |
| 5 | [`PRODUCTION_SIGNING.md`](PRODUCTION_SIGNING.md) | 외부 secret 기반 signed AAB 생성 절차 |

## 진행 예정 개발

| 문서 | 현재 상태 | 범위 |
|---|---|---|
| [`../dev-plan/implement_20260819_160712.md`](../dev-plan/implement_20260819_160712.md) | 계획 검토·보완 완료, 구현 미착수 | 개인 Google Drive 직접·자동 업로드와 Drive 앱 공유 폴백 |

진행 예정 문서는 현재 구현 정본이 아니다. 기능 완료 여부는 README와 최신 HANDOFF를 우선한다.

## 빌드·플랫폼 문서

| 문서 | 현재 기준 |
|---|---|
| [`BUILD_WHISPER.md`](BUILD_WHISPER.md) | whisper.cpp `1.9.2` 고정 commit, API 36, NDK r28 빌드 |
| [`ANDROID_16KB_PAGE_SIZE.md`](ANDROID_16KB_PAGE_SIZE.md) | AGP 8.10.1 / Gradle 8.11.1 / 16KB ZIP·ELF 검증 |
| [`DEVICE_16KB_TEST_REPORT.md`](DEVICE_16KB_TEST_REPORT.md) | 2026-08-06 당시 실기기 증적; 현재 도구 버전 정본이 아님 |
| [`DEVICE_BASELINE_20260807_1917.md`](DEVICE_BASELINE_20260807_1917.md) | 2026-08-07 당시 설치·데이터 기준선 |

## 기능·실험 기록

`BATCH_MULTI_FILE_PLAN.md`, `ONDEVICE_M4A_MP3_STT_PLAN.md`, `EXPERIMENT_PLAN.md`, `RESULT_SUMMARY_5H_CHUNK.md`, `EXTERNAL_LLM_SUMMARY_6H.md`는 기능 설계 또는 당시 실험 결과를 보존한다. 현재 완료 여부는 README와 최신 handoff를 우선한다.

`VOICE_TRACKER_FIND_PORT_REVIEW_20260807.md`, `VOICE_TRACKER_FIND_FINAL_APPLICATION_REVIEW_20260807.md`, `IMAGEGEN_ASSET_PROMPTS_20260811.md`는 GUI·녹음 이식 및 에셋 생성의 의사결정 기록이다. 현재 앱 구현 상태를 나타내는 정본은 아니다.

## 과거 handoff와 개발 계획

- [`HANDOFF_20260807.md`](HANDOFF_20260807.md), [`HANDOFF_20260810.md`](HANDOFF_20260810.md)는 작성 당시의 상태를 보존하는 역사 문서다.
- `../dev-plan/implement_*.md`의 미완료·대기 표기는 해당 계획 실행 시점의 기록이며 소급 수정하지 않는다.
- 개발 계획의 최신 인덱스는 [`../dev-plan/README.md`](../dev-plan/README.md)를 따른다.

## 문서 유지 규칙

1. 현재 버전·테스트 수·릴리스 절차는 README, 최신 handoff, 보안·서명 문서에만 정본으로 기록한다.
2. 날짜가 포함된 실험·기기 보고서는 당시 사실을 변경하지 않고 상단에 역사 문서임을 표시한다.
3. 계정, token, 전사 원문, 사용자 파일명·경로, keystore와 비밀번호는 문서에 기록하지 않는다.
4. 실제 production AAB의 해시·provenance는 보호된 릴리스 artifact로 관리하고 Git에 커밋하지 않는다.
