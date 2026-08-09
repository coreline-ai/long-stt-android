#!/usr/bin/env bash
# 저장소에 고정된 whisper.cpp commit을 third_party/whisper.cpp에 준비한다.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/third_party/whisper.cpp.lock"
SOURCE_DIR="${WHISPER_CPP_DIR:-$ROOT_DIR/third_party/whisper.cpp}"
VERIFY_ONLY=0

if [[ "${1:-}" == "--verify-only" ]]; then
  VERIFY_ONLY=1
elif [[ $# -gt 0 ]]; then
  echo "사용법: $0 [--verify-only]" >&2
  exit 2
fi

if [[ ! -f "$LOCK_FILE" ]]; then
  echo "오류: lock 파일이 없습니다: $LOCK_FILE" >&2
  exit 1
fi

# shellcheck source=/dev/null
source "$LOCK_FILE"
: "${WHISPER_CPP_REMOTE:?lock에 WHISPER_CPP_REMOTE가 필요합니다}"
: "${WHISPER_CPP_COMMIT:?lock에 WHISPER_CPP_COMMIT이 필요합니다}"
: "${WHISPER_CPP_VERSION:?lock에 WHISPER_CPP_VERSION이 필요합니다}"

if [[ ! -d "$SOURCE_DIR/.git" ]]; then
  if [[ $VERIFY_ONLY -eq 1 ]]; then
    echo "오류: whisper.cpp 소스가 없습니다: $SOURCE_DIR" >&2
    echo "먼저 ./scripts/setup_whisper.sh 를 실행하세요." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$SOURCE_DIR")"
  git clone --filter=blob:none --no-checkout "$WHISPER_CPP_REMOTE" "$SOURCE_DIR"
  git -C "$SOURCE_DIR" fetch --depth 1 origin "$WHISPER_CPP_COMMIT"
  git -C "$SOURCE_DIR" checkout --detach "$WHISPER_CPP_COMMIT"
fi

actual_commit="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
if [[ "$actual_commit" != "$WHISPER_CPP_COMMIT" ]]; then
  echo "오류: whisper.cpp commit 불일치" >&2
  echo "  expected: $WHISPER_CPP_COMMIT" >&2
  echo "  actual:   $actual_commit" >&2
  echo "기존 디렉터리를 직접 변경하지 말고 다른 위치로 이동한 뒤 다시 준비하세요." >&2
  exit 1
fi

if [[ -n "$(git -C "$SOURCE_DIR" status --porcelain --untracked-files=no)" ]]; then
  echo "오류: whisper.cpp tracked source에 로컬 변경이 있습니다." >&2
  git -C "$SOURCE_DIR" status --short >&2
  exit 1
fi

if ! grep -Eq "project\(\"whisper\.cpp\" VERSION ${WHISPER_CPP_VERSION//./\\.}\)" "$SOURCE_DIR/CMakeLists.txt"; then
  echo "오류: whisper.cpp version이 lock과 일치하지 않습니다: $WHISPER_CPP_VERSION" >&2
  exit 1
fi

echo "whisper.cpp 준비 완료"
echo "  source:  $SOURCE_DIR"
echo "  remote:  $WHISPER_CPP_REMOTE"
echo "  commit:  $actual_commit"
echo "  version: $WHISPER_CPP_VERSION"
