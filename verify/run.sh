#!/usr/bin/env bash
# 모든 Pack 의 검증 질문을 돌려 질문별 통과와 실패를 낸다 (D4 5절).
#
# 쓰는 법 (저장소의 맨 위에서):  bash verify/run.sh
#
# Docker 가 켜져 있어야 한다. Pack 마다 시나리오마다 빈 PostgreSQL 과 제품을 띄워 HTTP 로 묻고,
# 같은 일을 한 번 더 해 두 번의 결과가 같은지 본다. 하나라도 실패하거나 두 번의 결과가 다르면 0 이 아닌 값으로 끝난다.
# 보고는 화면과 verify/build/verify-report.txt 에 남는다.
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "[실패] Docker 가 없거나 꺼져 있습니다. Docker Desktop 을 켠 뒤 다시 실행하세요. (bash tools/doctor.sh 로 확인)" >&2
  exit 1
fi

cd "$top/server"
exec ./gradlew :verify:verifyQuestions --console=plain --quiet
