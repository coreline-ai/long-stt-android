#!/usr/bin/env bash
# 오디오를 whisper.cpp 호환 포맷(16kHz mono PCM)으로 변환
# 사용법: ./prepare_audio.sh <input_audio>
set -euo pipefail

INPUT="${1:-}"
if [[ -z "$INPUT" ]]; then
    echo "사용법: $0 <input_audio>"
    echo "예:   $0 ~/Downloads/opic.m4a"
    exit 1
fi

if [[ ! -f "$INPUT" ]]; then
    echo "❌ 파일 없음: $INPUT"
    exit 1
fi

# ffmpeg 확인
if ! command -v ffmpeg &>/dev/null; then
    echo "❌ ffmpeg 필요. brew install ffmpeg"
    exit 1
fi

BASENAME=$(basename "$INPUT" | sed 's/\.[^.]*$//')
OUTPUT="/tmp/${BASENAME}_16k_mono.wav"
# 10분 분할 버전 (빠른 테스트용)
OUTPUT_10MIN="/tmp/${BASENAME}_10min_16k_mono.wav"

echo "=== 오디오 변환 시작 ==="
echo "입력: $INPUT"

# 전체 변환
ffmpeg -y -i "$INPUT" \
    -ar 16000 -ac 1 -c:a pcm_s16le \
    "$OUTPUT" 2>&1 | tail -3

# 10분 분할 (첫 10분)
ffmpeg -y -i "$INPUT" \
    -t 600 \
    -ar 16000 -ac 1 -c:a pcm_s16le \
    "$OUTPUT_10MIN" 2>&1 | tail -3

echo
echo "=== 변환 완료 ==="
echo "전체: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
echo "10분: $OUTPUT_10MIN ($(du -h "$OUTPUT_10MIN" | cut -f1))"
echo
echo "다음: ./push_to_device.sh \"$OUTPUT\""
