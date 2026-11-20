#!/usr/bin/env bash
# 개발 환경 점검. 어디서든 실행: bash scripts/01-doctor.sh   (저장소 안에서는 bash tools/doctor.sh)
ok=0; bad=0; opt=0
pass(){ printf '  [확인] %s\n' "$1"; ok=$((ok+1)); }
fail(){ printf '  [필요] %s\n         → %s\n' "$1" "$2"; bad=$((bad+1)); }
note(){ printf '  [권장] %s\n         → %s\n' "$1" "$2"; opt=$((opt+1)); }
has(){ command -v "$1" >/dev/null 2>&1; }

echo "Tabnit Base 개발 환경 점검"
echo "1) 기본 도구"
has git   && pass "git $(git --version | awk '{print $3}')" || fail "git 없음" "brew install git"
has gh    && pass "gh $(gh --version | head -1 | awk '{print $3}')" || fail "GitHub CLI 없음" "brew install gh"
if has gh; then gh auth status >/dev/null 2>&1 && pass "GitHub 로그인됨" || fail "GitHub 로그인 안 됨" "gh auth login"; fi
has jq    && pass "jq $(jq --version 2>/dev/null)" || fail "jq 없음 (편집 검사가 쓴다)" "brew install jq"
if has java; then
  v=$(java -version 2>&1 | grep -m1 version | sed -E 's/.*"([0-9]+).*/\1/')
  [ "$v" = "21" ] && pass "Java 21" || fail "Java 버전이 $v (21 필요)" "brew install --cask temurin@21"
else fail "Java 없음" "brew install --cask temurin@21"; fi
if has node; then
  n=$(node -v | sed -E 's/v([0-9]+).*/\1/')
  [ "$n" -ge 22 ] && pass "Node $(node -v)" || fail "Node 버전이 낮음 ($(node -v))" "brew install node@24 && brew link --force --overwrite node@24"
else fail "Node 없음" "brew install node@24 && brew link --force --overwrite node@24"; fi
if has docker; then
  docker info >/dev/null 2>&1 && pass "Docker 실행 중" || fail "Docker가 꺼져 있음" "Docker Desktop을 실행하고 고래 아이콘이 멈출 때까지 기다리기"
else fail "Docker 없음" "Docker Desktop 설치 (가이드 1단계)"; fi

echo "2) Claude Code"
if has claude; then
  pass "Claude Code $(claude --version 2>/dev/null | head -1)"
else fail "Claude Code 없음" "curl -fsSL https://claude.ai/install.sh | bash  (설치 뒤 새 터미널)"; fi
has jdtls && pass "Java 언어 서버 (jdtls)" || fail "Java 언어 서버 없음 (jdtls-lsp 플러그인이 쓴다)" "brew install jdtls"
has typescript-language-server && pass "TypeScript 언어 서버" || fail "TypeScript 언어 서버 없음 (typescript-lsp 플러그인이 쓴다)" "npm install -g typescript-language-server typescript"
if has claude; then
  plist="$(claude plugin list 2>/dev/null || true)"
  for p in jdtls-lsp typescript-lsp superpowers security-guidance pr-review-toolkit context7 playwright modern-web-guidance skill-creator; do
    printf '%s' "$plist" | grep -q "$p" && pass "플러그인 $p" || fail "플러그인 $p 없음" "저장소 안에서 bash ~/Downloads/tabnit-setup-kit/scripts/04-claude.sh"
  done
fi

if git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "3) 이 저장소"
  top=$(git rev-parse --show-toplevel)
  [ "$(git config core.hooksPath)" = ".githooks" ] && pass "커밋 전 검사 켜짐" || fail "커밋 전 검사 꺼짐" "git config core.hooksPath .githooks"
  [ -f "$top/CLAUDE.md" ] && pass "CLAUDE.md 있음" || fail "CLAUDE.md 없음" "bash ~/Downloads/tabnit-setup-kit/scripts/02-scaffold.sh"
  [ -f "$top/tools/forbidden-words.local.txt" ] && pass "금지어 목록 있음" || fail "금지어 목록 없음" "대표에게 받아 tools/forbidden-words.local.txt 로 저장"
  [ -f "$top/.claude/settings.json" ] && jq -e . "$top/.claude/settings.json" >/dev/null 2>&1 && pass ".claude/settings.json 읽힘" || fail ".claude/settings.json 없음 또는 형식 오류" "bash ~/Downloads/tabnit-setup-kit/scripts/02-scaffold.sh"
  [ -f "$top/.mcp.json" ] && jq -e '.mcpServers["tabnit-db"]' "$top/.mcp.json" >/dev/null 2>&1 && pass ".mcp.json 읽힘 (tabnit-db, chrome-devtools)" || fail ".mcp.json 없음 또는 형식 오류" "bash ~/Downloads/tabnit-setup-kit/scripts/02-scaffold.sh"
  [ -x "$top/.claude/hooks/guard-edit.sh" ] && pass "편집 검사 실행 가능" || fail "편집 검사에 실행 권한 없음" "chmod +x .claude/hooks/guard-edit.sh"
  n=$(find "$top/.claude/skills" -name SKILL.md 2>/dev/null | wc -l | xargs); [ "${n:-0}" -ge 8 ] && pass "프로젝트 스킬 ${n}개" || fail "프로젝트 스킬이 ${n:-0}개 (8개 필요)" "bash ~/Downloads/tabnit-setup-kit/scripts/02-scaffold.sh"
  n=$(find "$top/.claude/agents" -name '*.md' 2>/dev/null | wc -l | xargs); [ "${n:-0}" -ge 4 ] && pass "서브에이전트 ${n}개" || fail "서브에이전트가 ${n:-0}개 (4개 필요)" "bash ~/Downloads/tabnit-setup-kit/scripts/02-scaffold.sh"
  if [ -f "$top/server/api/compose.yaml" ]; then
    grep -q "54329:5432" "$top/server/api/compose.yaml" && pass "개발 DB 포트 54329 고정" || fail "개발 DB 포트가 고정되지 않음 (tabnit-db MCP 가 못 붙는다)" "bash ~/Downloads/tabnit-setup-kit/scripts/04-claude.sh"
  else note "server/ 가 아직 없음" "bash ~/Downloads/tabnit-setup-kit/scripts/03-server.sh"; fi
fi
echo; echo "확인 $ok건, 필요 $bad건, 권장 $opt건"; [ "$bad" -eq 0 ]
