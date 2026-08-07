#!/usr/bin/env bash
# E1: 누락된 4인대화 10분 청크 순차 재전사
# 사용법:
#   ./rerun_missing_chunks.sh --dry-run
#   ./rerun_missing_chunks.sh
#   ./rerun_missing_chunks.sh --model /data/user/0/com.stt.benchmark/files/ggml-base.bin \
#                            --audio-dir /data/user/0/com.stt.benchmark/files \
#                            --wait-sec 90
set -euo pipefail

PKG="com.stt.benchmark"
ACTIVITY="${PKG}/.MainActivity"
# stage_to_app.sh 가 최종적으로 넣는 private filesDir
DEFAULT_FILES_DIR="/data/user/0/${PKG}/files"
MODEL="${DEFAULT_FILES_DIR}/ggml-base.bin"
AUDIO_DIR="${DEFAULT_FILES_DIR}"
WAIT_SEC=90
DRY_RUN=0
SERIAL=""

MISSING=(
  "chunk_00_01.wav"
  "chunk_00_03.wav"
  "chunk_01_00.wav"
  "chunk_01_05.wav"
  "chunk_02_03.wav"
  "chunk_02_05.wav"
  "chunk_04_04.wav"
  "chunk_05_02.wav"
  "chunk_05_04.wav"
)

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \?//'
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --audio-dir) AUDIO_DIR="$2"; shift 2 ;;
    --wait-sec) WAIT_SEC="$2"; shift 2 ;;
    --serial|-s) SERIAL="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage ;;
    *) echo "알 수 없는 인자: $1"; usage ;;
  esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

if ! command -v adb &>/dev/null; then
  echo "❌ adb 없음"
  exit 1
fi

if [[ $DRY_RUN -eq 0 ]]; then
  if ! "${ADB[@]}" get-state &>/dev/null; then
    echo "❌ 연결된 기기 없음"
    "${ADB[@]}" devices
    exit 1
  fi
fi

echo "=== E1 누락 청크 재전사 ==="
echo "model:     $MODEL"
echo "audio-dir: $AUDIO_DIR"
echo "wait-sec:  $WAIT_SEC"
echo "chunks:    ${#MISSING[@]}"
echo "dry-run:   $DRY_RUN"
echo

idx=0
for chunk in "${MISSING[@]}"; do
  idx=$((idx + 1))
  audio_path="${AUDIO_DIR}/${chunk}"
  note="missing_${chunk%.wav}"
  echo "[$idx/${#MISSING[@]}] $chunk"

  if [[ $DRY_RUN -eq 1 ]]; then
    echo "  DRY: start + broadcast note=$note audio=$audio_path"
    continue
  fi

  # Activity 기동 후 broadcast (VIEWMODEL 준비된 뒤 트리거 — 이전 벤치 방식과 동일)
  "${ADB[@]}" shell am start -n "$ACTIVITY" >/dev/null
  sleep 2
  "${ADB[@]}" shell am broadcast -a com.stt.benchmark.RUN_STT \
    --es model "$MODEL" \
    --es audio "$audio_path" \
    --es note "$note"
  echo "  → 대기 ${WAIT_SEC}s (전사 완료 여유)..."
  sleep "$WAIT_SEC"
done

echo
if [[ $DRY_RUN -eq 1 ]]; then
  echo "✅ dry-run 완료. 인자 없이 재실행하면 실제 전사 시작."
else
  echo "✅ 순차 실행 요청 완료."
  echo "결과 추출:"
  echo "  adb shell run-as $PKG cat files/stt_benchmark_results.csv > results_missing.csv"
fi
