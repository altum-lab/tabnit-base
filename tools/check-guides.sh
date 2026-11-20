#!/usr/bin/env bash
# SK-02 수용 기준 확인: 지침 파일에 아키텍처 요약, 모듈 경계, 금지 사항, 작업 전에 읽을 문서가 적혀 있다.
# 모듈 목록과 읽을 문서는 맨 위 CLAUDE.md 의 4절·5절 표에서 읽는다. 이 스크립트에 모듈 이름을 적지 않는다.
# 쓰는 법: bash tools/check-guides.sh
set -euo pipefail

top="$(cd "$(dirname "$0")/.." && pwd)"
root="$top/CLAUDE.md"
fail=0

bad() { echo "[필요] $1" >&2; fail=1; }

[ -f "$root" ] || { echo "[필요] CLAUDE.md 가 저장소 맨 위에 없습니다." >&2; exit 1; }

# 1) 맨 위 파일: 네 가지가 절 제목으로 있는가
need_heading() { # $1 찾을 말, $2 무엇에 해당하는가
  grep -q -E "^## .*$1" "$root" || bad "CLAUDE.md: '$1' 절이 없습니다 ($2). 절 제목을 되살리거나 이 검사의 제목을 함께 고치세요."
}
need_heading "무엇을 만드는가" "아키텍처 요약"
need_heading "기술 스택" "아키텍처 요약"
need_heading "모듈 경계" "모듈 경계"
need_heading "반드시 지키는 규칙" "금지 사항"
need_heading "작업 전에 읽을 것" "작업 전에 읽을 문서"

# 절 하나의 표에서 자료 행만 꺼낸다 (제목 행과 구분선 제외)
table_rows() { # $1 절 제목에 들어 있는 말
  awk -v key="$1" '
    /^## / { inside = (index($0, key) > 0); rows = 0; next }
    inside && /^\|/ { rows++; if (rows > 2) print }
  ' "$root"
}

# 문서 번호(D1~D9)만 뽑아 정렬해 한 줄로
doc_ids() { { grep -o -E 'D[0-9]+' || true; } | sort -u | tr '\n' ' ' | sed 's/ $//'; }

# 모듈 폴더 찾기: server 아래에 있으면 그곳, 아니면 맨 위
module_dir() {
  if [ -d "$top/server/$1" ]; then echo "server/$1"; elif [ -d "$top/$1" ]; then echo "$1"; fi
}

# 지침 파일 하나의 공통 검사: 읽을 문서, 금지 사항
check_guide() { # $1 저장소 기준 경로
  local f="$top/$1"
  grep -q -E '^읽을 문서:' "$f" || bad "$1: '읽을 문서:' 줄이 없습니다. 이 폴더에서 일하기 전에 읽을 설계 문서를 적으세요."
  grep -q '않는다' "$f" || bad "$1: 금지 사항이 없습니다. '지킬 것' 또는 '하지 않는 것' 에 하지 말아야 할 일을 적으세요."
}

# 2) 4절 표의 모듈마다 지침 파일이 있는가
modules="$(table_rows "모듈 경계" | awk -F'|' '{ gsub(/ /, "", $2); if ($2 != "") print $2 }')"
[ -n "$modules" ] || bad "CLAUDE.md: '모듈 경계' 절의 표에서 모듈을 하나도 읽지 못했습니다."

for m in $modules; do
  dir="$(module_dir "$m")"
  if [ -z "$dir" ]; then bad "모듈 '$m': 폴더가 없습니다 (server/$m 또는 $m)."; continue; fi
  if [ ! -f "$top/$dir/CLAUDE.md" ]; then bad "$dir/CLAUDE.md: 모듈 '$m' 의 지침 파일이 없습니다."; continue; fi

  # 3) 5절 표의 읽을 문서와 모듈 파일의 읽을 문서가 같은가
  want="$(table_rows "작업 전에 읽을 것" | awk -F'|' -v m="$m" '
    { n = split($2, names, ","); for (i = 1; i <= n; i++) { gsub(/ /, "", names[i]); if (names[i] == m) print $3 } }' | doc_ids)"
  have="$({ grep -E '^읽을 문서:' "$top/$dir/CLAUDE.md" || true; } | sed 's/관련 작업.*//' | doc_ids)"
  if [ -z "$want" ]; then
    bad "CLAUDE.md: '작업 전에 읽을 것' 절의 표에 모듈 '$m' 이 없습니다."
  elif [ -n "$have" ] && [ "$want" != "$have" ]; then
    bad "$dir/CLAUDE.md: 읽을 문서가 맨 위 CLAUDE.md 5절과 다릅니다 (5절: $want / 이 파일: $have)."
  fi
done

# 4) 저장소의 모든 폴더별 지침 파일에 읽을 문서와 금지 사항이 있는가
guides="$(cd "$top" && git ls-files -co --exclude-standard -- '*/CLAUDE.md' | grep -v '^\.claude/' | sort)"
count=0
for g in $guides; do
  check_guide "$g"
  count=$((count + 1))
done

if [ "$fail" -ne 0 ]; then
  echo "지침 파일 검사 실패. 위의 [필요] 항목을 고치세요." >&2
  exit 1
fi
echo "[확인] 지침 파일 검사 통과 (맨 위 CLAUDE.md, 폴더별 지침 파일 ${count}개)"
