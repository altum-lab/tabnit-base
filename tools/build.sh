#!/usr/bin/env bash
# 저장소 전체를 한 번에 빌드한다. 순서는 .github/workflows/ci.yml 과 같다.
# 쓰는 법: bash tools/build.sh   (Docker 가 켜져 있어야 한다. 서버 시험이 PostgreSQL 컨테이너를 띄운다)
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"

echo "1) 서버 빌드와 시험"
if ! (cd "$top/server" && ./gradlew build); then
  echo "[실패] 서버 빌드 또는 시험이 실패했습니다." >&2
  echo "       Docker 가 꺼져 있으면 시험이 실패합니다. bash tools/doctor.sh 로 환경을 먼저 확인하세요." >&2
  exit 1
fi

echo "2) 화면 빌드"
if [ -f "$top/web/package-lock.json" ]; then
  if ! (cd "$top/web" && npm ci && npm run build); then
    echo "[실패] 화면 빌드가 실패했습니다. 위의 출력에서 원인을 확인하세요." >&2
    exit 1
  fi
else
  echo "   web/package-lock.json 이 없어 건너뜁니다. (화면 프로젝트는 아직 만들지 않았습니다)"
fi

echo "[확인] 빌드가 끝났습니다."
