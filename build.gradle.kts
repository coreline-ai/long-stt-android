// 최상위 빌드 파일 - 플러그인 버전 중앙 집중 관리
// AGP 8.10.1+: Android API 36 compile/target 및 16KB ZIP alignment 지원
// (Play 16KB page size 요구사항 — packaging 정렬)
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
