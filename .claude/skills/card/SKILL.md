---
name: card
description: Tabnit 작업 카드 하나를 시작한다. 작업 번호(예 A-03)를 받아 카드와 설계 문서를 읽고, 계획을 세우고, 수용 기준을 시험으로 먼저 쓴 뒤 구현한다. 작업 번호로 일을 시작할 때 쓴다.
argument-hint: "[작업번호 예: A-03]"
disable-model-invocation: true
---

# 작업 카드 $0 시작

카드 없이 시작하지 않는다. 아래 순서를 건너뛰지 않는다.

## 1. 읽기 (코드를 고치기 전에)

1. `docs/D9-dev-conventions.md` 에서 작업 `$0` 의 제목, 수용 기준, 선행 작업, 담당 모듈을 찾는다. 없으면 멈추고 "카드 $0 이 D9 에 없다"고 알린다. 추측으로 수용 기준을 만들지 않는다.
2. 선행 작업이 main 에 병합됐는지 본다: `git log --oneline --grep="<선행 번호>" origin/main | head`. 안 됐으면 멈추고 알린다.
3. 맨 위 `CLAUDE.md` 5절의 표에서 이 모듈이 읽을 설계 문서를 골라 읽는다. 그 모듈 폴더의 `CLAUDE.md` 도 읽는다.
4. 현재 브랜치: !`git branch --show-current`
   `main` 이면 `feat/$0-짧은-영문-설명` 브랜치를 만든다 (사람에게 이름을 확인받는다).

## 2. 계획 (plan mode)

고칠 파일, 추가할 시험, 건드리지 않을 것을 5줄 안팎으로 적어 보여 준다. 다음에 해당하면 구현하지 않고 `/design-change` 로 넘긴다.

- 다른 모듈의 코드를 고쳐야 한다
- API 계약(D7), 데이터 모델(D5), Pack 형식(D6), 규칙 실행 방식(D8)을 바꿔야 한다
- 새 라이브러리가 필요하다

## 3. 시험 먼저

`/tdd-ac $0` 의 절차대로 수용 기준을 실패하는 시험으로 쓴다. 실패를 확인한 뒤 구현한다.

## 4. 구현

가장 얇게 끝까지 잇는다. 카드 범위 밖의 리팩터링, 파일 이동, 서식 변경을 하지 않는다. 같은 문제에 두 번 실패하면 방법을 바꾸거나 멈춘다.

## 5. 확인

- 모듈 시험: `cd server && ./gradlew :<모듈>:test` (화면이면 `cd web && npx playwright test`)
- 엔진 모듈이면 `determinism-auditor`, `generality-auditor`, `boundary-guard` 서브에이전트에게 변경분 검토를 맡긴다. core 나 rules 를 고쳤으면 `core-reviewer` 도 부른다.
- `/verify-questions` 로 검증 질문 통과 수가 줄지 않았는지 본다.

## 6. 마무리

`/pr-ready $0` 을 실행한다. 커밋과 push 는 사람이 승인한다.
