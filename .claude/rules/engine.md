---
paths:
  - "server/core/**"
  - "server/pack/**"
  - "server/link/**"
  - "server/rules/**"
---

# 엔진 코드 규칙 (core, pack, link, rules)

이 폴더의 파일을 읽거나 고칠 때 적용한다. 근거는 맨 위 `CLAUDE.md` 8절이다.

- 같은 입력에는 같은 결과. 목록은 정렬 기준을 정하고, 기준이 같으면 식별자 순으로 둔다. `HashMap`, `HashSet` 의 순회 순서에 결과가 달라지면 안 된다. 순서가 결과에 닿으면 `LinkedHashMap`, `TreeMap`, 명시적 정렬을 쓴다.
- 현재 시각을 읽지 않는다. 기준 날짜는 인자로 받는다. 편집 직후 검사(`.claude/hooks/guard-edit.sh`)가 `now()` 호출을 막는다.
- 관계를 읽는 조회는 기준 날짜 인자가 있어야 한다. 유효기간은 시작일 포함, 종료일 제외.
- 재귀 질의(`WITH RECURSIVE`)는 끝나는 조건, 방문한 대상 제외, 최대 깊이, `ORDER BY` 가 모두 있어야 한다.
- AI 모델과 외부 네트워크를 부르지 않는다.
- 예시 Pack 의 유형 이름, 개체 수, 정답 숫자를 코드에 넣지 않는다. 확인: `bash tools/check-generality.sh`.
- SQL 을 새로 쓰거나 고쳤으면 `tabnit-db` MCP 의 `explain_sql` 로 실행 계획을 보고, `execute_sql` 로 개발 DB 에서 경계 날짜의 결과를 확인한다. 색인과 캐시는 측정값이 있을 때만 넣는다.
