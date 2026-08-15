# 개발 계획 인덱스

기준일: `2026-08-15 KST`

`implement_*.md`는 각 요청 시점의 범위·판정·검증 기록이다. 오래된 문서의 미완료 체크박스는 당시 상태를 보존하므로 현재 잔여 작업으로 해석하지 않는다.

## 최신 완료 기준

| 문서 | 완료 범위 |
|---|---|
| [`implement_20260815_080304.md`](implement_20260815_080304.md) | API 36, 모델 무결성, OAuth state-first, production signing pipeline |
| [`implement_20260814_092607.md`](implement_20260814_092607.md) | 녹음 입력 전환 안전 분할과 UI 상태 |
| [`implement_20260814_080041.md`](implement_20260814_080041.md) | 자동 품질 게이트와 기기 검증 기준선 |
| [`implement_20260813_160122.md`](implement_20260813_160122.md) | 전사 근거 확인·대화 복귀 GUI |
| [`implement_20260813_100053.md`](implement_20260813_100053.md) | 전체 전사 기반 LLM 채팅 |

현재 전체 상태는 [`../docs/HANDOFF_20260815.md`](../docs/HANDOFF_20260815.md), 검증 완료 확인은 [`../docs/VERIFICATION_COMPLETION_20260814.md`](../docs/VERIFICATION_COMPLETION_20260814.md)를 우선한다.

## 유지 규칙

- 과거 계획은 소급해서 모두 체크 완료로 바꾸지 않는다.
- 새 구현 요청은 새 `implement_YYYYMMDD_HHMMSS.md`로 범위를 고정한다.
- 현재 수치·버전·운영 절차는 최신 handoff와 README에 반영한다.
