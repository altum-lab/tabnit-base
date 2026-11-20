#!/usr/bin/env bash
# 금지어 검사. 고객사 이름처럼 저장소에 들어가면 안 되는 말을 찾는다.
#   tools/check-forbidden.sh --staged   커밋하려는 변경만 (커밋 전 검사)
#   tools/check-forbidden.sh --all      저장소 전체 (자동 빌드)
# 금지어는 저장소에 올리지 않는다:
#   내 Mac  : tools/forbidden-words.local.txt (한 줄에 하나)
#   자동 빌드: 저장소 비밀값 FORBIDDEN_WORDS (쉼표로 구분)
set -o pipefail
cd "$(git rev-parse --show-toplevel)"
mode="${1:---staged}"
words=()
if [ -f tools/forbidden-words.local.txt ]; then
  while IFS= read -r w; do w="${w%%#*}"; w="$(echo "$w" | xargs)"; [ -n "$w" ] && words+=("$w"); done < tools/forbidden-words.local.txt
fi
if [ -n "${FORBIDDEN_WORDS:-}" ]; then
  IFS=',' read -ra extra <<< "$FORBIDDEN_WORDS"
  for w in "${extra[@]}"; do w="$(echo "$w" | xargs)"; [ -n "$w" ] && words+=("$w"); done
fi
if [ "${#words[@]}" -eq 0 ]; then
  echo "금지어 목록이 없어 검사를 건너뜁니다. (tools/forbidden-words.local.txt 를 만드세요)"; exit 0
fi
found=0
for w in "${words[@]}"; do
  if [ "$mode" = "--all" ]; then
    hits=$(git grep -n -i -I -F -e "$w" -- . 2>/dev/null | cut -c1-160)
  else
    hits=$(git diff --cached -U0 --no-color | grep '^+' | grep -v '^+++' | grep -i -F -e "$w" | cut -c1-160)
  fi
  if [ -n "$hits" ]; then
    found=1
    echo "금지어가 들어 있습니다 (목록의 $(printf '%s' "$w" | wc -m | xargs)글자 단어):"
    echo "$hits" | sed 's/^/    /' | head -10
  fi
done
if [ "$found" -eq 1 ]; then
  echo; echo "고객 이름이나 실제 자료가 들어가지 않게 고친 뒤 다시 하세요."; exit 1
fi
echo "금지어 검사 통과 (${#words[@]}개 단어)"
