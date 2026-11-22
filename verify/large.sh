#!/usr/bin/env bash
# 큰 시험 자료를 만들고 제품에 넣어, 넣은 뒤의 수가 셈한 수와 같은지 본다 (보드 T-07). 성능 점검(A-07)에 쓴다.
#
# 쓰는 법 (저장소의 맨 위에서):
#   bash verify/large.sh [개체의 수] [관계의 수] [층의 수] [변동의 수] [run 또는 only]
#   bash verify/large.sh                     개체 1000, 관계 3000, 층 3, 변동 10 으로 만들어 넣어 본다
#   bash verify/large.sh 20000 60000 4 20    더 큰 자료
#   bash verify/large.sh 1000 3000 3 10 only 만들기만 한다 (Docker 가 없어도 된다)
#
# 자료는 층으로 나뉜 모양이다: 개체를 층마다 고르게 나누고, 위층의 개체가 바로 아래층의 개체 여럿에 이어진다.
# 변동은 첫 층에서 나고 영향은 아래층으로 퍼진다. 난수를 쓰지 않는다: 같은 값에는 글자까지 같은 자료가 나온다.
# 모든 이름과 값은 지어낸 것이다. 만든 Pack 과 보고는 verify/build/large/ 아래에 남고 저장소에 올리지 않는다.
# 넣어 볼 때는 Docker 가 켜져 있어야 한다: 빈 PostgreSQL 과 제품을 띄워 HTTP 로 넣고 묻는다.
# 수가 다르면 0 이 아닌 값으로 끝난다. 단계마다 걸린 시간이 함께 나온다 (목표와 견주는 일은 A-07 이 한다).
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"
entities="${1:-1000}"
relations="${2:-3000}"
layers="${3:-3}"
changes="${4:-10}"
mode="${5:-run}"

if [ "$mode" != "run" ] && [ "$mode" != "only" ]; then
  echo "[실패] 다섯째 값은 run (넣어 본다) 또는 only (만들기만 한다) 입니다." >&2
  exit 2
fi
if [ "$mode" = "run" ]; then
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "[실패] Docker 가 없거나 꺼져 있습니다. Docker Desktop 을 켠 뒤 다시 실행하세요. 만들기만 하려면 다섯째 값에 only 를 주세요." >&2
    exit 1
  fi
fi

cd "$top/server"
exec ./gradlew :verify:largePack --console=plain --quiet \
  -Pentities="$entities" -Prelations="$relations" -Players="$layers" -Pchanges="$changes" -Pmode="$mode"
