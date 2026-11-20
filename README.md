# Tabnit Base

대외비. 변동이 생기면 영향을 찾고 해야 할 일을 만들어 주는 엔진과 관리 화면.

## 처음 받았을 때

```bash
bash tools/doctor.sh          # 환경 점검
cd server && ./gradlew build  # 서버 빌드와 시험 (Docker 가 켜져 있어야 함)
./gradlew :api:bootRun        # 서버 실행 → http://localhost:8080/api/ping
cd ../web && npm ci && npm run dev   # 화면 실행 → http://localhost:5173
```

## 폴더

| 폴더 | 내용 |
|---|---|
| docs | 설계 문서 D1~D9. 코드와 다르면 문서가 기준 |
| packs | Pack (제조 안전, 퇴직연금, 시험용). 가상 데이터만 |
| server | common, core, pack, link, rules, api |
| web | 관리 화면 |
| verify | 검증 질문 실행기 |
| deploy | 컨테이너, 설치 스크립트 |
| tools | 점검 스크립트, 개발 보드 |

## 규칙

- 작업은 개발 보드의 작업 번호로 한다. 브랜치 `feat/A-03-짧은-설명`, PR 제목 `A-03 무엇을 했는가`.
- main 에 직접 올리지 않는다. PR, 검토 1명, 자동 빌드 통과.
- 고객 자료와 실제 현장 자료는 넣지 않는다.
- Claude Code 는 `CLAUDE.md` 를 따른다.
