# 시연 대본

두 예시 Pack 의 시나리오를 화면으로 처음부터 끝까지 보여 주는 차례다 (D-10). 화면 시험 `web/tests/rehearsal.spec.ts` 가 이 차례 그대로 돈다.
아래의 수는 그 시험이 2026-11-25 에 화면에서 읽은 값이다 (시험이 `web/test-results/rehearsal/` 에 남긴 기록에서 옮겼다). 값이 맞는지는 검증 질문과 전수 대조가 따로 본다 (`bash verify/run.sh`).
자료나 규칙을 바꾸면 수가 달라진다. 그때는 시험을 다시 돌려 이 문서를 고친다. 모든 이름과 값은 지어낸 것이다.

## 준비

- 제품을 띄우고 두 Pack 을 적재해 둔다 (관리자). 판은 하나도 올리지 않은 상태에서 시작한다: 같은 시나리오를 두 번 올릴 수는 없다 (적용일이 앞 판보다 늦어야 한다).
- 담당자로 로그인한다. 올릴 파일은 저장소의 `packs/<Pack>/data/` 에 있다.
- 화면은 넷이다: 변동 감지, 영향 범위, 조치, 관계 지도. 위의 메뉴로 오간다.
- 아래 표의 "입력 자료"는 식별자다. 화면에서는 이름표와 함께 보인다 (예: `safety-sheet` 는 "물질안전자료 목록 (safety-sheet, CSV)").

## 화면에서 하는 일

1. **변동 감지**에서 Pack 과 입력 자료를 고르고, 적용일을 넣고, 파일을 골라 "올리기"를 누른다. "새 판을 받아들였습니다"와 받아들인 항목의 수가 나온다.
2. 첫 판에는 바뀐 항목이 없다 (견줄 앞 판이 없다). 둘째 판부터 "바뀐 항목" 표에 추가, 변경, 삭제와 바뀐 값(이전 값 → 새 값)이 나온다.
3. 바뀐 항목의 "영향 범위 보기"를 누르면 **영향 범위**: 영향 대상의 수, 유형별·깊이별 수, 대상마다 근거 경로.
4. 영향 대상의 "관계 지도"를 누르면 **관계 지도**: 그 개체를 가운데에 둔 그림과 관계 목록. 시점 막대로 날짜를 옮겨 본다.
5. 영향 범위 화면의 "이 변동의 조치 보기"를 누르면 **조치**: 기한, 우선순위, 해야 할 일, 대상, 근거 조항과 그 판.
6. 조치의 "근거와 이력 보기"를 열어 근거 경로를 확인하고 "확정"을 누른다 (보류는 사유를 적는다). 상태가 바뀌고 이력에 누가 했는지 남는다.
   - 근거 경로를 보여 주려면 **대상이 근거 조항과 다른 조치**를 연다 (대상이 작업인원, 공정설비, 연금계좌 같은 것). 바뀐 개체 자신에게 건 조치에는 "근거 경로가 없습니다"가 나온다 (예: 제조 안전 27단계의 실란 자료에서는 목록의 맨 위 조치가 그렇다).

7. 지난 판을 다시 본다: **변동 감지** 화면에서 Pack 을 고르면 아래에 "지난 판"이 최근에 받은 것부터 나온다. 판의 "결과 보기"를 누르면 그때의 바뀐 항목, 바뀐 값, 알림이 목록의 위에 나온다. 다음 파일을 올린 뒤에도, 다른 화면에 다녀와도 볼 수 있다.

## 제조 안전 Pack (`manufacturing-safety`)

시나리오: 물질안전자료가 두 번 바뀌고 인원이 옮기고 공정이 운영을 시작하고 물질의 이름을 고치고 물질 하나를 쓰지 않게 된다.

모두 27단계다. 적힌 차례대로 올린다.

| 단계 | 입력 자료 | 파일 | 적용일 | 받아들인 항목 | 추가 | 변경 | 삭제 | 조치 | 알림 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | regulation | `data/regulation-v1.csv` | 2026-01-01 | 6 | 0 | 0 | 0 | 0 | 0 |
| 2 | requirement | `data/requirement-v1.csv` | 2026-01-01 | 7 | 0 | 0 | 0 | 0 | 0 |
| 3 | safety-sheet | `data/safety-sheet-v1.csv` | 2026-01-01 | 12 | 0 | 0 | 0 | 0 | 0 |
| 4 | substance | `data/substance-v1.csv` | 2026-01-01 | 12 | 0 | 0 | 0 | 0 | 0 |
| 5 | process | `data/process-v1.csv` | 2026-01-01 | 13 | 0 | 0 | 0 | 0 | 0 |
| 6 | process-substance | `data/process-substance-v1.csv` | 2026-01-01 | 23 | 0 | 0 | 0 | 0 | 0 |
| 7 | equipment | `data/equipment-v1.csv` | 2026-01-01 | 32 | 0 | 0 | 0 | 0 | 0 |
| 8 | storage | `data/storage-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 9 | substance-storage | `data/substance-storage-v1.csv` | 2026-01-01 | 7 | 0 | 0 | 0 | 0 | 0 |
| 10 | emission-point | `data/emission-point-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 11 | process-emission | `data/process-emission-v1.csv` | 2026-01-01 | 6 | 0 | 0 | 0 | 0 | 0 |
| 12 | worker | `data/worker-v1.csv` | 2026-01-01 | 60 | 0 | 0 | 0 | 0 | 0 |
| 13 | incident | `data/incident-v1.csv` | 2026-01-01 | 5 | 0 | 0 | 0 | 0 | 0 |
| 14 | incident-substance | `data/incident-substance-v1.csv` | 2026-01-01 | 5 | 0 | 0 | 0 | 0 | 0 |
| 15 | permit | `data/permit-v1.csv` | 2026-01-01 | 4 | 0 | 0 | 0 | 0 | 0 |
| 16 | evidence | `data/evidence-v1.csv` | 2026-01-01 | 25 | 0 | 0 | 0 | 0 | 0 |
| 17 | requirement-substance | `data/requirement-substance-v1.csv` | 2026-01-01 | 18 | 0 | 0 | 0 | 0 | 0 |
| 18 | requirement-storage | `data/requirement-storage-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 19 | requirement-emission | `data/requirement-emission-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 20 | requirement-equipment | `data/requirement-equipment-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 21 | safety-sheet | `data/safety-sheet-v2.csv` | 2026-09-02 | 12 | 0 | 2 | 0 | 70 | 0 |
| 22 | worker | `data/worker-v2.csv` | 2026-10-01 | 60 | 1 | 4 | 1 | 2 | 0 |
| 23 | process | `data/process-v2.csv` | 2026-10-15 | 13 | 0 | 1 | 0 | 0 | 0 |
| 24 | equipment | `data/equipment-v2.csv` | 2026-10-15 | 32 | 0 | 4 | 0 | 0 | 0 |
| 25 | substance | `data/substance-v2.csv` | 2026-10-20 | 12 | 0 | 1 | 0 | 0 | 0 |
| 26 | process-substance | `data/process-substance-v2.csv` | 2026-11-20 | 22 | 0 | 0 | 1 | 0 | 0 |
| 27 | safety-sheet | `data/safety-sheet-v3.csv` | 2026-11-20 | 12 | 0 | 2 | 0 | 56 | 0 |

- 앞의 20단계는 처음 자료다. 바뀐 항목이 없다.
- 변동이 나오는 것은 21단계부터다. 조치가 가장 많이 나오는 단계는 21단계 (70건)다.
- 어느 단계에도 "투입의 알림"이 나오지 않는다. 가리키는 개체의 자료가 먼저 들어가게 차례를 잡았다 (물질안전자료가 화학물질보다 먼저다, D2 AD-382).
- 리허설에서 뒤의 화면으로 들어간 변동: 27단계(`data/safety-sheet-v3.csv`)의 변동 이벤트 `manufacturing-safety|safety-sheet|3|SDS-06`. 영향 대상 16개, 조치 12건. 조치 하나를 확정했다.

## 퇴직연금 Pack (`retirement-pension`)

시나리오: 위험자산 한도가 내려가고 보유가 바뀌고 금융기관의 등급이 내려가고 퇴직 통보가 들어오고 적립비율이 바뀌고 규약의 이름이 바뀐다.

모두 25단계다. 적힌 차례대로 올린다.

| 단계 | 입력 자료 | 파일 | 적용일 | 받아들인 항목 | 추가 | 변경 | 삭제 | 조치 | 알림 |
|---|---|---|---|---|---|---|---|---|---|
| 1 | regulation | `data/regulation-v1.csv` | 2026-01-01 | 14 | 0 | 0 | 0 | 0 | 0 |
| 2 | requirement | `data/requirement-v1.csv` | 2026-01-01 | 21 | 0 | 0 | 0 | 0 | 0 |
| 3 | institution | `data/institution-v1.csv` | 2026-01-01 | 10 | 0 | 0 | 0 | 0 | 0 |
| 4 | product | `data/product-v1.csv` | 2026-01-01 | 28 | 0 | 0 | 0 | 0 | 0 |
| 5 | default-option | `data/default-option-v1.csv` | 2026-01-01 | 5 | 0 | 0 | 0 | 0 | 0 |
| 6 | default-option-product | `data/default-option-product-v1.csv` | 2026-01-01 | 8 | 0 | 0 | 0 | 0 | 0 |
| 7 | group | `data/group-v1.csv` | 2026-01-01 | 3 | 0 | 0 | 0 | 0 | 0 |
| 8 | manager | `data/manager-v1.csv` | 2026-01-01 | 5 | 0 | 0 | 0 | 0 | 0 |
| 9 | employer | `data/employer-v1.csv` | 2026-01-01 | 16 | 0 | 0 | 0 | 0 | 0 |
| 10 | plan | `data/plan-v1.csv` | 2026-01-01 | 16 | 0 | 0 | 0 | 0 | 0 |
| 11 | plan-employer | `data/plan-employer-v1.csv` | 2026-01-01 | 15 | 0 | 0 | 0 | 0 | 0 |
| 12 | participant | `data/participant-v1.csv` | 2026-01-01 | 115 | 0 | 0 | 0 | 0 | 0 |
| 13 | account | `data/account-v1.csv` | 2026-01-01 | 129 | 0 | 0 | 0 | 0 | 0 |
| 14 | account-product | `data/account-product-v1.csv` | 2026-01-01 | 378 | 0 | 0 | 0 | 0 | 0 |
| 15 | requirement-product | `data/requirement-product-v1.csv` | 2026-01-01 | 50 | 0 | 0 | 0 | 0 | 0 |
| 16 | requirement-plan | `data/requirement-plan-v1.csv` | 2026-01-01 | 25 | 0 | 0 | 0 | 0 | 0 |
| 17 | requirement-institution | `data/requirement-institution-v1.csv` | 2026-01-01 | 15 | 0 | 0 | 0 | 0 | 0 |
| 18 | requirement-default-option | `data/requirement-default-option-v1.csv` | 2026-01-01 | 10 | 0 | 0 | 0 | 0 | 0 |
| 19 | requirement | `data/requirement-v2.csv` | 2026-04-01 | 21 | 0 | 2 | 0 | 38 | 0 |
| 20 | account-product | `data/account-product-v2.csv` | 2026-06-01 | 377 | 2 | 0 | 3 | 0 | 0 |
| 21 | institution | `data/institution-v2.csv` | 2026-08-18 | 10 | 0 | 5 | 0 | 94 | 0 |
| 22 | participant | `data/participant-v2.csv` | 2026-10-01 | 115 | 0 | 6 | 0 | 4 | 0 |
| 23 | plan | `data/plan-v2.csv` | 2026-10-06 | 16 | 0 | 5 | 0 | 6 | 0 |
| 24 | account-product | `data/account-product-v3.csv` | 2026-11-01 | 376 | 0 | 0 | 1 | 0 | 0 |
| 25 | regulation | `data/regulation-v2.csv` | 2026-11-01 | 14 | 0 | 1 | 0 | 0 | 0 |

- 앞의 18단계는 처음 자료다. 바뀐 항목이 없다.
- 변동이 나오는 것은 19단계부터다. 조치가 가장 많이 나오는 단계는 21단계 (94건)다.
- 리허설에서 뒤의 화면으로 들어간 변동: 23단계(`data/plan-v2.csv`)의 변동 이벤트 `retirement-pension|plan|2|L-04`. 영향 대상 2개, 조치 2건. 조치 하나를 확정했다.
