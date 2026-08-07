// 최상위 빌드 파일 - 플러그인 버전 중앙 집중 관리
// AGP 8.5.1+ 필수: uncompressed .so 를 APK 안에서 16KB zip-align
// (Play 16KB page size 요구사항 — packaging 정렬)
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
