#!/usr/bin/env bash
# whisper.cpp 양자화 모델 다운로드
set -euo pipefail

OUT_DIR="${1:-/tmp}"
BASE_URL="https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

echo "=== whisper.cpp 모델 다운로드 ==="
echo "저장 위치: $OUT_DIR"
echo

# 다운로드할 모델 목록 (q5_1 양자화 = 속도/정확도 균형)
declare -A MODELS=(
    ["ggml-tiny-q5_1.bin"]="37MB   (가장 빠름, 정확도 낮음)"
    ["ggml-base-q5_1.bin"]="75MB   (권장 시작점)"
    ["ggml-small-q5_1.bin"]="500MB (정확도 높음, 느림)"
)

for model in "${!MODELS[@]}"; do
    read -p "다운로드 ${model}? [y/N] " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "다운로드: ${MODELS[$model]}"
        curl -L "${BASE_URL}/${model}" -o "${OUT_DIR}/${model}"
        echo "✅ ${OUT_DIR}/${model}"
    fi
done

echo
echo "=== 다운로드된 모델 ==="
ls -lh "${OUT_DIR}"/ggml-*.bin 2>/dev/null || echo "없음"
