# whisper.cpp 네이티브 심볼 보호 해제
-keep class com.stt.benchmark.whisper.** { *; }
-keepclassmembers class * {
    native <methods>;
}
