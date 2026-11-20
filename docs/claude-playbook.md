# Claude Code 활용서 (Tabnit Base)

대외비. 이 문서는 세 가지와 번호로 맞물린다.

- **쇼츠** 「Tabnit을 만드는 Claude Code 세팅」의 장면 1~18
- **셋업 키트** `tabnit-setup-kit` 의 `GUIDE.md` 0~6단계
- **개발 보드**의 "Claude 활용" 화면과 작업 카드의 "Claude로 하는 법"

기준일 2026-11-19, Claude Code 2.1.32x. 도구의 이름과 명령은 그날 공식 문서와 각 저장소에서 확인했다. 도구는 자주 바뀌므로 분기마다 6절의 점검을 한다.

## 1. 한 장 요약

| 층 | 쓰는 것 | 하는 일 | 쇼츠 장면 |
|---|---|---|---|
| 지침 | `CLAUDE.md`, 모듈별 `CLAUDE.md`, `.claude/rules/` | Claude 가 매번 읽는 규칙. 모듈 폴더에 들어가면 그 모듈의 규칙이 더해진다 | 2 |
| 코드 이해 | 플러그인 `jdtls-lsp`, `typescript-lsp` | 고친 직후 형식 오류를 보고, 정의와 참조를 정확히 찾는다 | 3 |
| 일하는 방법 | 플러그인 `superpowers`, 스킬 `/tdd-ac` | 시험을 먼저 쓰고, 막히면 순서대로 원인을 찾는다 | 4 |
| 작업 흐름 | 스킬 `/card`, `/contract-first`, `/pack-check`, `/design-change`, `/impl-record`, `/pr-ready` | 작업 카드 하나를 읽기부터 PR 까지 같은 순서로 한다 | 5 |
| 데이터베이스 | MCP `tabnit-db` (DBHub, 읽기 전용) | 시점 조회와 재귀 질의를 개발 DB 에서 직접 확인하고 실행 계획을 본다 | 6 |
| 화면 | 플러그인 `playwright` (MCP) | 화면을 실제로 열어 눌러 보고, 흐름 시험을 만든다 | 7 |
| 성능 | MCP `chrome-devtools` | 관계 지도 같은 무거운 화면의 성능을 기록으로 잰다 | 8 |
| 최신 문서 | 플러그인 `context7` | 스프링 부트, 리액트 등 쓰는 버전의 문서를 확인한다 | 9 |
| 웹 표준 | 플러그인 `modern-web-guidance` | 구식 웹 코드를 새로 넣지 않게 한다 | 10 |
| 자동 검사 | 훅 `.claude/hooks/guard-edit.sh` | 금지어, 현재 시각·난수·외부 호출, 모듈 경계, 올라간 스키마 파일 수정을 편집 직후 막는다 | 11 |
| 사전 검토 | 서브에이전트 `boundary-guard`, `determinism-auditor`, `generality-auditor`, `core-reviewer` | 사람이 보기 전에 경계, 결정성, 일반성, 핵심 로직을 따로 본다 | 12 |
| 코드 검토 | `/code-review`, `/security-review`, 플러그인 `security-guidance`, `pr-review-toolkit` | 정확성 결함과 보안 문제를 PR 전에 찾는다 | 13 |
| 보호 | `.claude/settings.json` 의 권한과 샌드박스 | 대외비 저장소에서 할 수 있는 일을 정해 둔다 | 14 |
| 병렬 작업 | `claude --worktree <이름>` | 카드마다 작업 폴더를 따로 두고 동시에 진행한다 | 15 |
| 기준 | 스킬 `/verify-questions`, `/verify` | 검증 질문 통과 수와 실제 동작으로 끝났는지 판단한다 | 16 |

## 2. 작업 유형별 사용법

각 줄은 그대로 Claude Code 에 입력하는 문장이다. `<번호>` 만 바꾼다.

### 2-1. 모든 작업의 처음과 끝

```
/card <번호>
... 구현 ...
/pr-ready <번호>
```

### 2-2. core — 시점 조회, 영향 범위 (A-01 ~ A-10, T-01, SK-03, SK-05)

```
/card A-03
tabnit-db 로 관계 테이블의 열과 색인을 확인하고, 시작일 당일·종료일 전날·종료일 당일을 기준 날짜로 한 조회 결과를 각각 보여 줘.
이 재귀 질의의 실행 계획을 explain_sql 로 보고, 돌아오는 관계에서 끝나는지와 ORDER BY 가 있는지 확인해 줘.
determinism-auditor, generality-auditor, boundary-guard, core-reviewer 에게 이번 변경을 각각 검토시켜 줘.
```

- 성능(A-07): 측정값 없이 색인이나 캐시를 넣지 않는다. `explain_sql` 의 계획과 T-07 의 큰 자료로 잰 시간을 구현 기록에 남긴다.

### 2-3. pack, rules — Pack 읽기와 검증, 규칙 실행 (B-01 ~ B-13, T-03, T-04, T-05, P-01)

```
/card B-02
/pack-check
packs/test-mini 로 같은 시험을 한 번 더 돌려 줘. 코드 수정 없이 통과해야 해.
```

- Pack 의 유형 이름과 속성 이름이 생기면 `tools/pack-terms.txt` 에 더한다. 그 뒤로 편집 검사와 CI 가 엔진 코드에서 그 말을 찾는다.
- 정답 파일은 대표가 정한다. Claude 가 `packs/` 를 고치려 하면 권한 확인이 뜬다.

### 2-4. link — 파일 커넥터, 매핑, 판 비교 (C-01 ~ C-12, T-02)

```
/card C-05
/tdd-ac C-05
같은 파일을 두 번 넣은 결과와 한 번 넣은 결과가 같은지 시험으로 확인해 줘.
```

### 2-5. api — 계약과 호출 코드 (D-02, SK-07)

```
/card D-02
/contract-first
context7 로 지금 쓰는 스프링 부트 버전의 문서를 확인하고 구현해 줘.
```

### 2-6. web — 화면 연결 (D-01 ~ D-12, T-06, E-08)

```
/card D-05
playwright 로 http://localhost:5173 을 열어 새 판을 올리고, 대기 상태·오류 상태·결과 없음 상태를 각각 확인해 줘.
확인한 흐름을 Playwright 시험으로 남겨 줘.
chrome-devtools 로 관계 지도 화면의 성능을 기록하고 가장 오래 걸리는 구간을 알려 줘.   (D-08)
```

### 2-7. common, 통합 — 로그인, 감사 기록, 설치 (E-01 ~ E-16)

```
/card E-02
/security-review
```

- 로그인, 권한, 감사 기록을 건드린 변경은 `/security-review` 를 반드시 돌린다. `security-guidance` 플러그인은 Claude 가 고치는 동안 흔한 취약점을 그때그때 알려 준다.

### 2-8. 막혔을 때

```
superpowers 의 systematic-debugging 순서로 이 실패를 조사해 줘. 고치기 전에 다시 만드는 방법과 원인을 먼저 보여 줘.
/rewind          (방금 한 변경을 되돌린다)
/design-change <바꾸려는 것>     (설계를 바꿔야 하면)
```

## 3. 병렬 작업 (쇼츠 장면 15)

대표가 뼈대를 혼자 만드는 S0 에서 특히 쓴다. 서로 다른 모듈의 카드만 동시에 한다.

```
claude --worktree sk-04-pack-loader      # 터미널 1
claude --worktree sk-06-verify-runner    # 터미널 2
```

- 작업 폴더는 `.claude/worktrees/` 아래에 생기고 저장소에는 올라가지 않는다.
- 같은 모듈의 카드 두 개를 동시에 하지 않는다. 병합할 때 충돌한다.
- 서버를 띄우는 카드는 한 번에 하나만 한다. 개발 DB 포트(54329)를 함께 쓴다.

## 4. 결정표 — 고민하지 않게 정해 둔 것

| 질문 | 결정 | 이유 |
|---|---|---|
| GitHub 는 MCP 로 붙이나 | 붙이지 않는다. `gh` 명령을 쓴다 | 접속 키를 설정에 넣지 않아도 되고, 이미 권한 규칙이 있다 |
| Serena 를 쓰나 | 쓰지 않는다 | 언어 서버 플러그인 두 개가 같은 일을 한다. 겹치면 Claude 가 도구를 고르느라 느려진다 |
| 클라우드 검토 봇(`/ultrareview`, GitHub 의 Claude 리뷰, 외부 리뷰 서비스)을 쓰나 | 12월 30일까지 쓰지 않는다 | 대외비 저장소다. 검토는 내 Mac 에서 `/code-review` 와 서브에이전트로 한다 |
| 권한 확인을 끄나 | 끄지 않는다 | 샌드박스와 허용 목록으로 확인 횟수를 줄인다. bypass 모드는 금지 |
| 샌드박스가 빌드를 막으면 | `./gradlew` 와 `docker` 는 처음부터 샌드박스 밖에서 돈다. 그래도 막히면 `/sandbox` 로 끄고 대표에게 알린다 | Testcontainers 가 Docker 를 쓴다 |
| context7 은 외부로 무엇을 보내나 | 찾는 라이브러리 이름과 질문만 보낸다 | 질문에 고객 이름이나 내부 용어를 넣지 않는다 |
| chrome-devtools 는 외부로 무엇을 보내나 | 사용 통계를 끈 상태로 등록했다 (`--no-usage-statistics`) | 개발 화면은 가상 데이터만 보여 준다 |
| 엔진 안에서 AI 를 부르나 | 부르지 않는다 | 엔진은 모델 없이 같은 결과를 낸다. Claude 는 만드는 도구이지 제품의 부품이 아니다 |
| Claude 가 틀린 설계를 제안하면 | `docs/` 가 기준이다. `/design-change` 로만 바꾼다 | 설계 문서 수정은 권한 확인이 뜨고 대표만 승인한다 |
| 새 플러그인이나 MCP 가 좋아 보이면 | 임의로 붙이지 않는다. 대표에게 요청하고, 승인되면 `.claude/settings.json` 과 이 표에 올린다 | 모두가 같은 환경에서 같은 결과를 내야 한다 |
| 보안 정적 분석(semgrep, sonarqube 플러그인)은 | S5 기능 동결 뒤 E-16 보안 점검 대응 때 검토한다 | 지금 넣으면 경고를 처리하느라 필수 범위가 밀린다 |
| 한 카드가 하루를 넘기면 | 멈추고 카드를 쪼갠다. `/card` 의 계획 단계에서 범위를 다시 적는다 | 큰 변경은 검토가 안 된다 |

## 5. 쇼츠 장면과 자료의 대응

| 장면 | 내용 | 셋업 키트 | 저장소의 파일 | 보드 작업 |
|---|---|---|---|---|
| 1 | 왜 세팅이 먼저인가 | GUIDE 0단계 | | SK-10 |
| 2 | 지침 파일 | 2단계 | `CLAUDE.md`, `*/CLAUDE.md`, `.claude/rules/` | SK-02 |
| 3 | 언어 서버 | 4단계 | `.claude/settings.json` 의 `enabledPlugins` | SK-10 |
| 4 | 시험 먼저 | 4단계 | `.claude/skills/tdd-ac/` | T-01 ~ T-06 |
| 5 | 작업 카드 스킬 | 5단계 | `.claude/skills/card/` | 전 작업 |
| 6 | 개발 DB 읽기 | 4단계 | `.mcp.json`, `tools/dbhub.toml` | A-03, A-04, A-07 |
| 7 | 화면 직접 확인 | 4단계 | `web/CLAUDE.md`, `.claude/rules/web.md` | D-03 ~ D-08, E-08 |
| 8 | 성능 기록 | 4단계 | `.mcp.json` | D-08, A-06 |
| 9 | 최신 문서 | 4단계 | `.claude/skills/contract-first/` | D-02, E-02 |
| 10 | 웹 표준 안내 | 4단계 | `.claude/rules/web.md` | D-01, D-09 |
| 11 | 편집 직후 검사 | 2단계 | `.claude/hooks/guard-edit.sh` | SK-10 |
| 12 | 서브에이전트 | 2단계 | `.claude/agents/` | A-04, B-05 |
| 13 | 코드와 보안 검토 | 5단계 | `.claude/skills/pr-ready/` | 전 작업 |
| 14 | 권한과 샌드박스 | 2단계 | `.claude/settings.json` | SK-10 |
| 15 | 병렬 작업 | 5단계 | 이 문서 3절 | S0 의 SK 작업 |
| 16 | 검증 질문이 기준 | 5단계 | `.claude/skills/verify-questions/` | SK-06, E-04 |
| 17 | 결정표 | 6단계 | 이 문서 4절 | |
| 18 | 정리 | 전체 | | E-17 |

## 6. 분기마다 하는 점검

1. `claude --version`, `/doctor`, `/plugin` 의 Errors 탭.
2. `/skill-doctor` 로 안 쓰는 스킬과 플러그인을 찾아 끈다. 쓰지 않는 도구도 매 요청의 문맥을 차지한다.
3. 4절의 결정표를 다시 읽고, 바뀐 것이 있으면 대표가 고친다.
4. 프로젝트 스킬이 실제로 따라지는지 `skill-creator` 플러그인의 평가로 확인한다.
