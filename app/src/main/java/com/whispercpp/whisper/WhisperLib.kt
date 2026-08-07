package com.whispercpp.whisper

/**
 * whisper.cpp JNI 브리지.
 *
 * JNI 시그니처가 jni.c에서 Java_com_whispercpp_whisper_WhisperLib$Companion_* 로
 * 고정되어 있으므로, 이 패키지/클래스명을 변경하면 안 됨.
 *
 * native 메서드는 companion object에 선언해야 $Companion 시그니처와 일치함.
 */
class WhisperLib private constructor() {

    companion object {
        init {
            System.loadLibrary("whisper")
        }

        /** 모델 파일 경로로 컨텍스트 초기화 */
        external fun initContext(modelPath: String): Long

        /**
         * 전사 실행 (오디오는 16kHz mono float32 배열).
         *
         * @return whisper.cpp의 반환 코드. 0은 성공이며, 음수 값은 JNI 입력/컨텍스트 오류다.
         */
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray): Int

        /** 전사된 세그먼트 수 반환 */
        external fun getTextSegmentCount(contextPtr: Long): Int

        /** 지정 인덱스의 세그먼트 텍스트 반환 */
        external fun getTextSegment(contextPtr: Long, index: Int): String

        /** 세그먼트 시작 시각 (10ms 단위) */
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long

        /** 세그먼트 종료 시각 (10ms 단위) */
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

        /** 시스템 정보 문자열 */
        external fun getSystemInfo(): String

        /** 컨텍스트 해제 */
        external fun freeContext(contextPtr: Long)
    }
}
