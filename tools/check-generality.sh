#!/usr/bin/env bash
# 일반성 검사: 엔진 코드(main)에 예시 Pack 의 용어가 들어갔는지 찾는다.
#   tools/check-generality.sh            저장소 전체
#   tools/check-generality.sh --staged   커밋하려는 변경만
# 용어 목록: tools/pack-terms.txt (한 줄에 하나). Pack 의 유형 이름, 속성 이름처럼 코드에 나오면 안 되는 말.
set -o pipefail
cd "$(git rev-parse --show-toplevel)"
[ -f tools/pack-terms.txt ] || { echo "tools/pack-terms.txt 가 없어 검사를 건너뜁니다."; exit 0; }
mode="${1:---all}"; found=0
paths="server/common/src/main server/core/src/main server/pack/src/main server/link/src/main server/rules/src/main server/api/src/main verify"
while IFS= read -r w; do
  w="${w%%#*}"; w="$(echo "$w" | xargs)"; [ -n "$w" ] || continue
  if [ "$mode" = "--staged" ]; then
    hits=$(git diff --cached -U0 --no-color -- $paths 2>/dev/null | grep '^+' | grep -v '^+++' | grep -i -F -e "$w" | cut -c1-160)
  else
    hits=$(git grep -n -i -I -F -e "$w" -- $paths 2>/dev/null | cut -c1-160)
  fi
  if [ -n "$hits" ]; then found=1; echo "Pack 용어 '$w' 가 엔진 코드에 있습니다:"; echo "$hits" | sed 's/^/    /' | head -10; fi
done < tools/pack-terms.txt
[ "$found" -eq 0 ] && { echo "일반성 검사 통과"; exit 0; }
echo; echo "업무 영역의 말은 Pack 에 둡니다. 코드는 Pack 이 정의한 유형을 값으로 받아 처리해야 합니다."; exit 1
