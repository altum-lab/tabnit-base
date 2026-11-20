---
paths:
  - "server/**/db/migration/**"
---

# 스키마 변경 파일 규칙

- 변경은 `V번호__설명.sql` 파일을 새로 추가한다. main 에 올라간 파일은 고치지 않는다.
- 테이블과 열은 `docs/D5` 와 같아야 한다. 다르면 코드를 맞추지 말고 `/design-change` 로 간다.
- 관계 테이블에는 유효기간 열(시작, 종료)과 출처가 있다. 행을 지우는 문장(`DELETE`, `TRUNCATE`)을 변경 파일에 쓰지 않는다.
- 추가한 뒤 빈 데이터베이스에서 처음부터 적용되는지 확인한다: `cd server && ./gradlew :api:test`.
