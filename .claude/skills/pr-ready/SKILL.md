---
name: pr-ready
description: PR 을 올리기 전 점검을 한 번에 돌리고 PR 본문을 양식대로 채운다. 빌드, 금지어, 일반성, 검증 질문, 코드 검토, 보안 검토를 포함한다. 작업 카드를 끝내고 PR 을 만들 때 쓴다.
disable-model-invocation: true
argument-hint: "[작업번호]"
---

# PR 준비: $ARGUMENTS

순서대로 한다. 하나라도 실패하면 고치고 그 단계부터 다시 한다.

1. `cd server && ./gradlew build` (화면을 고쳤으면 `cd web && npm run build && npx playwright test` 도)
2. `bash tools/check-forbidden.sh --all`
3. `bash tools/check-generality.sh --all`
4. `/verify-questions`
5. `/code-review` 로 변경분을 검토한다. 정확성 결함은 고친다. 취향 문제는 고치지 않는다.
6. 로그인, 권한, 감사 기록, 입력 파일 처리, SQL 을 건드렸으면 `/security-review` 도 돌린다.
7. core 나 rules 를 고쳤으면 `core-reviewer` 서브에이전트의 검토 결과를 PR 본문에 붙인다 (대표가 직접 검토하는 모듈이다).
8. `/impl-record $ARGUMENTS` 로 구현 기록을 만든다.
9. `.github/pull_request_template.md` 를 채운다. 제목은 `$ARGUMENTS 무엇을 했는가`. "무엇을 하지 않았는가"를 비우지 않는다.
10. 사람에게 변경 요약과 PR 본문을 보여 준다. 사람이 승인하면 커밋, push, `gh pr create` 를 한다. 승인 전에는 하지 않는다.

PR 이 병합되면 사람이 PR 주소를 개발 보드 작업의 "완료 근거"에 붙인다.
