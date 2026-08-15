# Long STT Android 검증 완료 현황

- 사용자·실장비 완료 확인일: `2026-08-14 KST`
- 최신 소스 자동 검증 반영일: `2026-08-15 KST`

상태: **기능·보안 소스·자동·사용자/실장비 검증 완료 / Coreline 릴리스 결정 가능**

## 완료 기준

프로젝트 담당자가 모든 테스트 완료를 확인했다. 자동 품질 게이트와 기존 Samsung 합성 smoke 결과에 더해, 이전 문서에서 사용자 조작 또는 실제 하드웨어가 필요해 대기로 분리했던 검증도 완료 상태로 전환한다.

| 검증 범위 | 현재 상태 |
|---|---|
| 앱 JVM 255건·OAuth JVM 44건, 실패/오류/skip 0 | 완료 |
| API 36 Release lint 0 errors / 37 warnings | 완료 |
| Debug/Release/deviceTest 산출물, Release surface, 16KB 검증 | 완료 |
| 모델 고정 revision·크기·SHA-256·허용 host 검증 | 완료 |
| OAuth loopback state-first 검증 | 완료 |
| production signing fail-closed·일회성 외부 JKS signed AAB/provenance | 완료 |
| 실제 OAuth lifecycle | 완료 확인 |
| 실제 전사 기반 LLM 채팅·근거 확인·복귀 | 완료 확인 |
| 실제 외부 앱 요약/TXT 전달 | 완료 확인 |
| 장시간 원본 import/STT·저장공간 부족·process recreation | 완료 확인 |
| secure lock·Bluetooth/USB route·전화/알람 interruption·백그라운드 유지 | 완료 확인 |

## 증적 및 개인정보 경계

- 이 문서는 프로젝트 담당자의 완료 확인을 기록한다.
- OAuth 계정, 전사 원문, 녹음 파일, 외부 앱 대상, 기기 식별자, 원시 로그와 스크린샷은 저장소에 포함하지 않는다.
- 이전 개발 계획의 “대기/미검증” 문구는 각 계획의 작성·실행 당시 상태를 보존한 이력이다. 현재 상태는 이 문서, README, 최신 HANDOFF를 우선한다.

## 남은 절차

소스 구현과 테스트 항목은 남아 있지 않다. 실제 릴리스 시 다음 운영 절차를 수행한다.

1. Coreline이 릴리스 범위·배포 채널을 결정
2. 프로젝트 고유 코드와 제3자 구성요소의 고지·라이선스 적용 범위를 확인
3. 보호된 upload key로 `:app:productionReleaseBundle` 실행
4. signed AAB·서명·SHA-256 provenance와 배포 채널 검증

구체적인 secret 주입·CI·artifact 절차는 [`PRODUCTION_SIGNING.md`](PRODUCTION_SIGNING.md)를 따른다. 일반 `bundleRelease`가 만드는 unsigned AAB는 배포본이 아니다.

Codex 관련 연동만으로 별도의 “Codex 정식 배포 승인”이 필요한 것은 아니다. 다만 적용되는 서비스 약관과 제3자 구성요소의 조건은 각 릴리스에 맞게 준수한다.
