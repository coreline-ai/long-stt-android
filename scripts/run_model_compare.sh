#!/usr/bin/env bash
# E2: 동일 샘플 × 다중 모델 RTF/품질 비교 실행
# 사용법:
#   ./run_model_compare.sh --dry-run
#   ./run_model_compare.sh --wait-sec 120
set -euo pipefail

PKG="com.stt.benchmark"
ACTIVITY="${PKG}/.MainActivity"
FILES_DIR="/data/user/0/${PKG}/files"
WAIT_SEC=120
DRY_RUN=0
SERIAL=""

# 비교 모델 (filesDir 기준 파일명). 존재하는 것만 실행.
MODELS=(
  "ggml-base.bin"
  "ggml-base-q5_1.bin"
  "ggml-small-q5_1.bin"
)

# 대표 샘플 3개 (베이스라인에 있던 파일)
SAMPLES=(
  "chunk_00_00.wav"
  "chunk_02_00.wav"
  "chunk_04_03.wav"
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --wait-sec) WAIT_SEC="$2"; shift 2 ;;
    --serial|-s) SERIAL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help)
      echo "Usage: $0 [--dry-run] [--wait-sec N] [--serial SERIAL]"
      exit 0
      ;;
    *) echo "알 수 없는 인자: $1"; exit 1 ;;
  esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

if [[ $DRY_RUN -eq 0 ]]; then
  if ! "${ADB[@]}" get-state &>/dev/null; then
    echo "❌ 연결된 기기 없음"
    "${ADB[@]}" devices
    exit 1
  fi
fi

echo "=== E2 모델 비교 매트릭스 ==="
echo "models:  ${MODELS[*]}"
echo "samples: ${SAMPLES[*]}"
echo "wait:    ${WAIT_SEC}s / job"
echo "dry-run: $DRY_RUN"
echo

total=$(( ${#MODELS[@]} * ${#SAMPLES[@]} ))
n=0
for model in "${MODELS[@]}"; do
  model_path="${FILES_DIR}/${model}"
  model_tag="${model#ggml-}"
  model_tag="${model_tag%.bin}"
  for sample in "${SAMPLES[@]}"; do
    n=$((n + 1))
    audio_path="${FILES_DIR}/${sample}"
    sample_tag="${sample%.wav}"
    note="cmp_${model_tag}_${sample_tag}"
    echo "[$n/$total] model=$model sample=$sample note=$note"

    if [[ $DRY_RUN -eq 1 ]]; then
      echo "  DRY: broadcast note=$note model=$model sample=$sample"
      continue
    fi

    "${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null
    sleep 2
    "${ADB[@]}" shell am broadcast -a com.stt.benchmark.RUN_STT \
      --es model "$model_path" \
      --es audio "$audio_path" \
      --es note "$note"
    echo "  → 대기 ${WAIT_SEC}s..."
    sleep "$WAIT_SEC"
  done
done

echo
if [[ $DRY_RUN -eq 1 ]]; then
  echo "✅ dry-run 완료."
  echo "주의: 기기에 해당 모델 파일이 없으면 로드 실패합니다."
  echo "  앱 UI 다운로드 또는 scripts/download_model.sh + push 사용."
else
  echo "✅ 비교 실행 요청 완료."
  echo "  adb shell run-as $PKG cat files/stt_benchmark_results.csv > results_compare.csv"
  echo "note 필터: cmp_base_*, cmp_base-q5_1_*, cmp_small-q5_1_*"
fi
