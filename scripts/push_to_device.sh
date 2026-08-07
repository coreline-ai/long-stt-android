#!/usr/bin/env bash
# 모델 및 오디오 파일을 안드로이드 기기로 전송
# 사용법: ./push_to_device.sh [audio_file]
set -euo pipefail

# adb 확인
if ! command -v adb &>/dev/null; then
    echo "❌ adb 필요. Android SDK platform-tools 설치"
    echo "   brew install --cask android-platform-tools"
    exit 1
fi

# 기기 연결 확인
if ! adb get-state &>/dev/null; then
    echo "❌ 연결된 기기 없음. USB 디버깅 활성화 후 재시도"
    adb devices
    exit 1
fi

DEVICE_DIR="/sdcard/Download"
echo "=== 대상 기기 ==="
adb devices
echo

# 1. 모델 파일 전송 (없으면 다운로드 안내)
MODELS=(
    "/tmp/ggml-base-q5_1.bin"
    "/tmp/ggml-small-q5_1.bin"
)

echo "=== 모델 파일 확인 ==="
MODEL_FOUND=false
for model in "${MODELS[@]}"; do
    if [[ -f "$model" ]]; then
        echo "전송: $model"
        adb push "$model" "$DEVICE_DIR/"
        MODEL_FOUND=true
    fi
done

if [[ "$MODEL_FOUND" == "false" ]]; then
    echo "⚠ 모델 파일 없음. 다운로드 권장:"
    echo "   curl -L https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin -o /tmp/ggml-base-q5_1.bin"
    echo "   그 후 이 스크립트 재실행"
fi
echo

# 2. 오디오 파일 전송 (인자로 받은 경우)
AUDIO="${1:-}"
if [[ -n "$AUDIO" ]]; then
    if [[ -f "$AUDIO" ]]; then
        echo "=== 오디오 전송 ==="
        echo "파일: $AUDIO"
        adb push "$AUDIO" "$DEVICE_DIR/"
    else
        echo "❌ 오디오 파일 없음: $AUDIO"
    fi
fi

echo
echo "=== 기기 내 파일 목록 ==="
adb shell ls -la "$DEVICE_DIR/" 2>/dev/null | grep -E "\.bin|\.wav|\.m4a"
echo
echo "✅ 완료. 앱에서 경로 입력:"
echo "   모델: $DEVICE_DIR/ggml-base-q5_1.bin"
echo "   오디오: $DEVICE_DIR/$(basename "${AUDIO:-audio_16k.wav}")"
