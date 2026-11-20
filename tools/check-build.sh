#!/usr/bin/env bash
# SK-01 수용 기준 확인: 저장소를 받아 한 번의 명령으로 빌드된다.
# git 에 올린(색인에 있는) 파일만으로 깨끗한 사본을 만들고, 거기서 tools/build.sh 하나만 돌린다.
# 올리지 않은 파일이나 내 작업 폴더의 빌드 산출물에 기대는 빌드는 여기서 실패한다.
# 쓰는 법: bash tools/check-build.sh   (Docker 가 켜져 있어야 한다)
set -euo pipefail

top="$(git rev-parse --show-toplevel)"

if [ -z "$(git -C "$top" ls-files | head -n 1)" ]; then
  echo "[실패] git 에 올린 파일이 없습니다. 먼저 git add 로 파일을 올리세요." >&2
  exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/tabnit-check-build.XXXXXX")"
trap 'rm -rf "$work"' EXIT

git -C "$top" checkout-index --all --prefix="$work/"

if [ ! -f "$work/tools/build.sh" ]; then
  echo "[실패] 깨끗한 사본에 tools/build.sh 가 없습니다. 빌드 명령 하나가 저장소에 올라가 있어야 합니다." >&2
  exit 1
fi

echo "깨끗한 사본에서 빌드: $work"
if (cd "$work" && bash tools/build.sh); then
  echo "[확인] 저장소를 받아 한 번의 명령(bash tools/build.sh)으로 빌드됩니다."
else
  echo "[실패] 깨끗한 사본에서 빌드가 실패했습니다. 위의 출력에서 원인을 확인하세요." >&2
  echo "       내 작업 폴더에서는 되는데 여기서만 실패하면, git 에 올리지 않은 파일에 기대고 있는 것입니다." >&2
  exit 1
fi
