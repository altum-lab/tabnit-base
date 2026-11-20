---
name: pack-check
description: 엔진이 어떤 Pack 에도 동작하는지 확인한다. 엔진 코드에 예시 Pack 의 용어나 숫자가 들어갔는지 찾고, 시험용 작은 Pack 으로 같은 기능을 한 번 더 돌린다. Pack 이나 엔진 모듈을 고친 뒤에 쓴다.
allowed-tools: Bash(bash tools/check-generality.sh *) Bash(./gradlew *) Read Grep Glob
---

# 일반성 확인

1. `bash tools/check-generality.sh --all` 을 돌린다. 걸린 곳은 Pack 의 값을 코드 밖에서 받도록 고친다.
2. 변경한 코드에서 다음을 찾는다 (Grep):
   - 특정 유형 이름과 비교하는 조건문 (`equals("...")`, `switch` 의 문자열 분기)
   - 개체 수나 결과 수를 뜻하는 숫자 상수
   - 예시 Pack 에서만 생기는 경우를 덮는 예외 처리
3. 같은 기능을 `packs/test-mini` 로 한 번 더 돌린다. 코드 수정 없이 통과해야 한다.
4. Pack 을 고쳤다면 그 Pack 의 검증 질문을 다시 돌린다 (`/verify-questions`). 정답 파일은 고치지 않는다.
5. Pack 형식이 모자라서 생긴 문제면 코드로 덮지 말고 `/design-change` 로 D6 을 고친다.

끝나면 "걸린 곳 / 고친 곳 / 다른 Pack 으로 확인한 결과"를 세 줄로 적는다.
