#!/usr/bin/env bash
# Claude Code 가 파일을 고친 직후 도는 검사 (PostToolUse: Edit|Write).
# 문제가 있으면 종료 코드 2 로 끝내 Claude 에게 이유를 돌려준다. Claude 는 같은 차례에서 고친다.
# 사람이 손으로 돌려 볼 때:  echo '{"tool_input":{"file_path":"server/core/src/main/java/X.java"}}' | bash .claude/hooks/guard-edit.sh
set -u
top="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
command -v jq >/dev/null 2>&1 || exit 0          # jq 가 없으면 검사를 건너뛴다 (doctor 가 알려 준다)
file="$(jq -r '.tool_input.file_path // empty' 2>/dev/null)"
[ -n "$file" ] || exit 0
case "$file" in /*) abs="$file" ;; *) abs="$top/$file" ;; esac
[ -f "$abs" ] || exit 0
rel="${abs#"$top"/}"
msgs=""
add(){ msgs="${msgs}- $1
"; }

# 1) 금지어: 고객 이름, 실제 자료
if [ -f "$top/tools/forbidden-words.local.txt" ]; then
  while IFS= read -r w; do
    w="${w%%#*}"; w="$(printf '%s' "$w" | xargs 2>/dev/null)"; [ -n "$w" ] || continue
    if lines=$(grep -n -i -I -F -e "$w" "$abs" 2>/dev/null | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$lines" ]; then
      add "금지어가 들어 있다 ($rel ${lines}행). 고객 이름이나 실제 자료를 지어낸 값으로 바꾼다."
    fi
  done < "$top/tools/forbidden-words.local.txt"
fi

# 2) 엔진 코드: 같은 입력에는 같은 결과 (CLAUDE.md 8절)
case "$rel" in
  server/core/src/main/*|server/pack/src/main/*|server/link/src/main/*|server/rules/src/main/*)
    if h=$(grep -n -E '(LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|Instant|Clock)\.(now|systemDefaultZone|systemUTC)\(|System\.(currentTimeMillis|nanoTime)\(' "$abs" | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$h" ]; then
      add "엔진 코드에서 현재 시각을 읽는다 ($rel ${h}행). 기준 날짜는 밖에서 받아 인자로 넘긴다."
    fi
    if h=$(grep -n -E 'new Random\(|Math\.random\(|ThreadLocalRandom|UUID\.randomUUID\(' "$abs" | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$h" ]; then
      add "엔진 코드에서 난수를 쓴다 ($rel ${h}행). 결과가 실행마다 달라지면 안 된다."
    fi
    if h=$(grep -n -E 'java\.net\.(http|URL|HttpURLConnection)|RestTemplate|WebClient|RestClient|okhttp3|org\.springframework\.ai|com\.anthropic|com\.openai' "$abs" | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$h" ]; then
      add "엔진 코드에서 외부 네트워크나 AI 모델을 부른다 ($rel ${h}행). 엔진은 모델과 네트워크 없이 동작한다."
    fi
    ;;
esac

# 3) 모듈 경계: core 는 link, rules, pack 을 모른다
case "$rel" in
  server/core/src/*)
    if h=$(grep -n -E '^import +com\.altumlab\.tabnit\.(link|rules|pack|api)\.' "$abs" | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$h" ]; then
      add "core 가 다른 모듈을 import 한다 ($rel ${h}행). 반대 방향 의존이다. 멈추고 설계 변경 절차(/design-change)로 간다."
    fi ;;
  server/common/src/*)
    if h=$(grep -n -E '^import +com\.altumlab\.tabnit\.(core|link|rules|pack|api)\.' "$abs" | cut -d: -f1 | head -5 | tr '\n' ' '); [ -n "$h" ]; then
      add "common 이 다른 모듈을 import 한다 ($rel ${h}행). common 은 어디에도 의존하지 않는다."
    fi ;;
esac

# 4) 이미 올라간 스키마 변경 파일은 고치지 않는다
case "$rel" in
  server/*/src/main/resources/db/migration/V*.sql)
    if git -C "$top" cat-file -e "origin/main:$rel" 2>/dev/null && ! git -C "$top" diff --quiet origin/main -- "$rel" 2>/dev/null; then
      add "main 에 이미 올라간 스키마 변경 파일을 고쳤다 ($rel). 되돌리고 새 V번호 파일을 추가한다."
    fi ;;
esac

if [ -n "$msgs" ]; then
  { echo "Tabnit 편집 검사에 걸렸습니다. 아래를 고친 뒤 계속하세요."; printf '%s' "$msgs"; } >&2
  exit 2
fi
exit 0
