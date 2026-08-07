#!/usr/bin/env bash
# Download → (shell) external staging → (run-as) app private filesDir
#
# 배경:
# - 앱 프로세스는 shell 소유 Android/data 파일을 File.exists() 로 못 봄
# - run-as 는 /sdcard/Download 를 cat 못 함 (Permission denied)
# - shell 은 Download 를 읽고, run-as 는 shell 이 쓴 external app 파일을 private 로 복사 가능
#
# 사용법:
#   ./stage_to_app.sh --serial R3CY40PXCAP --missing-only
#   ./stage_to_app.sh --serial R3CY40PXCAP --all-chunks --model ggml-base.bin
set -euo pipefail

PKG="com.stt.benchmark"
SERIAL=""
SRC_DIR="/sdcard/Download"
EXT_DIR="/sdcard/Android/data/${PKG}/files"
MISSING_ONLY=0
ALL_CHUNKS=0
MODEL="ggml-base.bin"
EXTRA_FILES=()

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

COMPARE_SAMPLES=(
  "chunk_00_00.wav"
  "chunk_02_00.wav"
  "chunk_04_03.wav"
)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial|-s) SERIAL="$2"; shift 2 ;;
    --src) SRC_DIR="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --missing-only) MISSING_ONLY=1; shift ;;
    --all-chunks) ALL_CHUNKS=1; shift ;;
    --file) EXTRA_FILES+=("$2"); shift 2 ;;
    -h|--help)
      sed -n '2,14p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *) echo "알 수 없는 인자: $1"; exit 1 ;;
  esac
done

ADB=(adb)
if [[ -n "$SERIAL" ]]; then
  ADB=(adb -s "$SERIAL")
fi

if ! "${ADB[@]}" get-state &>/dev/null; then
  echo "❌ 기기 없음"; "${ADB[@]}" devices; exit 1
fi
if ! "${ADB[@]}" shell pm path "$PKG" &>/dev/null; then
  echo "❌ $PKG 미설치"; exit 1
fi

"${ADB[@]}" shell "mkdir -p '$EXT_DIR'"
"${ADB[@]}" shell "run-as $PKG mkdir -p files" >/dev/null

stage_one() {
  local name="$1"
  local src="${SRC_DIR}/${name}"
  local ext="${EXT_DIR}/${name}"
  echo -n "→ $name ... "

  if ! "${ADB[@]}" shell "[ -f '$src' ]"; then
    echo "⚠ 소스 없음 ($src)"
    return 1
  fi

  # already in private with matching size?
  local priv_sz src_sz
  src_sz=$("${ADB[@]}" shell "stat -c %s '$src'" | tr -d '\r')
  priv_sz=$("${ADB[@]}" shell "run-as $PKG stat -c %s files/${name} 2>/dev/null" | tr -d '\r' || true)
  if [[ -n "${priv_sz:-}" && "$priv_sz" == "$src_sz" && "$priv_sz" != "0" ]]; then
    echo "skip (private 동일 ${priv_sz}B)"
    return 0
  fi

  # 1) shell: Download → external
  if ! "${ADB[@]}" shell "cp '$src' '$ext'"; then
    echo "FAIL (shell cp)"
    return 1
  fi

  # 2) run-as: external → private filesDir
  if ! "${ADB[@]}" shell "run-as $PKG cp '$ext' files/${name}"; then
    echo "FAIL (run-as cp)"
    return 1
  fi

  priv_sz=$("${ADB[@]}" shell "run-as $PKG stat -c %s files/${name}" | tr -d '\r')
  if [[ "$priv_sz" != "$src_sz" ]]; then
    echo "FAIL (size $priv_sz != $src_sz)"
    return 1
  fi
  echo "OK (${priv_sz}B → private files/)"
}

echo "=== stage_to_app (shell cp + run-as cp) ==="
echo "serial: $SERIAL"
echo "src:    $SRC_DIR"
echo "via:    $EXT_DIR"
echo "dst:    /data/user/0/${PKG}/files/"
echo

fail=0
if [[ -n "$MODEL" ]]; then
  stage_one "$MODEL" || fail=$((fail + 1))
fi

files_to_stage=()
if [[ $ALL_CHUNKS -eq 1 ]]; then
  # portable: avoid mapfile for bash3
  while IFS= read -r line; do
    base=$(basename "$line" | tr -d '\r')
    case "$base" in chunk_*.wav) files_to_stage+=("$base") ;; esac
  done < <("${ADB[@]}" shell "ls ${SRC_DIR}/chunk_*.wav" 2>/dev/null)
else
  files_to_stage=("${MISSING[@]}" "${COMPARE_SAMPLES[@]}")
fi
for f in "${EXTRA_FILES[@]:-}"; do
  [[ -n "$f" ]] && files_to_stage+=("$f")
done

unique=()
for f in "${files_to_stage[@]}"; do
  [[ -z "$f" ]] && continue
  dup=0
  for u in "${unique[@]:-}"; do
    if [[ "$u" == "$f" ]]; then dup=1; break; fi
  done
  [[ $dup -eq 1 ]] && continue
  unique+=("$f")
done

echo
echo "오디오 ${#unique[@]}개"
for f in "${unique[@]}"; do
  stage_one "$f" || fail=$((fail + 1))
done

echo
echo "=== private filesDir ==="
"${ADB[@]}" shell "run-as $PKG ls -la files" | head -40
echo
if [[ $fail -gt 0 ]]; then
  echo "⚠ 실패 ${fail}개"
  exit 2
fi
echo "✅ 스테이징 완료 (앱이 읽을 수 있는 private files/)"
echo "다음:"
echo "  ./scripts/rerun_missing_chunks.sh --serial ${SERIAL:-SERIAL} --wait-sec 90"
