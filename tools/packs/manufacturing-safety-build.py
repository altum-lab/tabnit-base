# 제조 안전 Pack 의 예시 입력 파일과 검증 질문의 정답을 만든다.
#
# 자료는 모두 지어낸 것이다. 구조(유형 11개, 관계 11개, 수)는 시연 자료의 설계도를 본떴다.
# 정답은 엔진을 부르지 않고, 문서(D3 6절·7절, D8 1절·3절·4절, D7 의 순서)의 규칙을 이 파일에 따로 구현해 계산한다.
#
# 쓰는 법 (저장소의 맨 위에서): python3 tools/packs/manufacturing-safety-build.py packs/manufacturing-safety
# 다시 돌리면 data/ 와 questions/S-01.yaml 을 똑같이 다시 쓴다. 정의 파일(ttl)은 손대지 않는다.
#
# 이 계산이 하지 않는 것 (그래서 이 Pack 의 질문은 이런 경우를 묻지 않는다. 그 규칙은 엔진의 일반 시험과 시험용 Pack 이 본다):
# - 길이가 같은 근거 경로가 둘일 때 관계 식별자로 가리는 것 (D3 7절). 그런 경우를 만나면 멈춘다.
# - 관계 유형과 상대 개체가 같은 관계가 둘일 때 관계 식별자로 차례를 정하는 것 (D3 5절). 만나면 멈춘다.
# - 규칙이 상위 유형을 가리킬 때 하위 유형을 포함하는 것 (D8 3절). 이 Pack 의 규칙은 상위 유형을 쓰지 않는다.
# - 값을 읽은 뒤의 표기로 다듬어 견주는 것 (25 와 25.0, D2 AD-156). 이 자료에는 그런 값이 없다.
# 아래의 MAPPING, RULES, DIRECTION 은 정의 파일을 손으로 옮긴 것이다. 정의 파일을 고치면 여기도 고친다.
import csv, io, os, sys, datetime

PACK = "manufacturing-safety"
OUT = sys.argv[1]
D = datetime.date.fromisoformat

# ── 유형과 관계 (온톨로지와 같아야 한다) ────────────────────────────
T_REG, T_REQ, T_SUB, T_SDS = "안전법규", "법정요건", "화학물질", "물질안전자료"
T_PRC, T_EQP, T_STO, T_EMI = "제조공정", "공정설비", "약품보관소", "대기배출구"
T_WRK, T_INC, T_PMT = "작업인원", "안전사고", "작업허가"
# 영향 방향: 'ST' 출발→도착, 'TS' 도착→출발, None 퍼지지 않음
DIRECTION = {"요건부과": "ST", "요건적용": "TS", "자료기술": "TS", "물질사용": "TS", "설비구성": "ST",
             "인원배치": "ST", "물질보관": "ST", "배기연결": None, "사고관련": None, "이행증빙": None, "허가대상": None}
MAX_DEPTH = 3

def eid(t, k): return "|".join([PACK, t, k])

# ── 지어낸 자료 ────────────────────────────────────────────────
# 법규의 이름은 지어낸 사내 규정의 이름이다. 실제 법령이 아니다.
REGS = [("REG-01", "사내 물질정보 게시 규정 제3조", "2024-01-01"), ("REG-02", "사내 측정 주기 관리 규정 제5조", "2024-01-01"),
        ("REG-03", "사내 위험 요인 검토 규정 제2조", "2024-01-01"), ("REG-04", "사내 약품 취급 기준 제7조", "2024-07-01"),
        ("REG-05", "사내 보호구 지급 기준 제4조", "2024-07-01"), ("REG-06", "사내 배출 관리 기준 제9조", "2025-01-01")]
# 물질: 번호, 이름, 특별관리 여부
SUBS = [("MAT-01", "불산", True), ("MAT-02", "암모니아수", False), ("MAT-03", "과산화수소", False), ("MAT-04", "황산", True),
        ("MAT-05", "삼불화질소", False), ("MAT-06", "실란", False), ("MAT-07", "포스핀", True), ("MAT-08", "아르신", True),
        ("MAT-09", "육불화텅스텐", False), ("MAT-10", "현상액", False), ("MAT-11", "감광 용제", True), ("MAT-12", "이소프로필알코올", False)]
SDS_OF = {s[0]: "SDS-" + s[0][4:] for s in SUBS}
HAZARD = ["급성독성 구분3", "피부부식성 구분1", "산화성 구분2", "피부부식성 구분1", "산화성 가스 구분1", "인화성 가스 구분1",
          "급성독성 구분2", "급성독성 구분1", "급성독성 구분2", "급성독성 구분3", "인화성 액체 구분3", "인화성 액체 구분2"]
LIMIT = ["0.5", "25", "1", "0.2", "10", "5", "0.3", "0.005", "0.5", "1", "50", "200"]
# 요건: 번호, 이름, 구분, 근거 법규, 적용 물질
REQS = [("REQ-01", "게시물 최신화", "게시", "REG-01", ["MAT-01", "MAT-04", "MAT-02", "MAT-07"]),
        ("REQ-02", "변경 교육", "교육", "REG-01", ["MAT-01", "MAT-07", "MAT-08"]),
        ("REQ-03", "측정 재실시", "측정", "REG-02", ["MAT-01", "MAT-04", "MAT-11"]),
        ("REQ-04", "위험성평가 갱신", "평가", "REG-03", ["MAT-01", "MAT-07"]),
        ("REQ-05", "보호구 사양 확인", "보호구", "REG-05", ["MAT-01", "MAT-04"]),
        ("REQ-06", "보관 표지 관리", "보관", "REG-04", ["MAT-01", "MAT-03", "MAT-02"]),
        ("REQ-07", "배출 초과 보고", "배출", "REG-06", ["MAT-01"])]
# 공정: 번호, 동, 구역, 이름, 쓰는 물질, 설비 수, 인원 수
PRCS = [("가-세정-01", "가동", "세정", "게이트 전 세정", ["MAT-01", "MAT-02", "MAT-03"], 3, 7),
        ("가-세정-02", "가동", "세정", "산화막 식각 세정", ["MAT-01"], 3, 6),
        ("가-세정-03", "가동", "세정", "감광막 제거", ["MAT-04", "MAT-03"], 2, 5),
        ("가-식각-03", "가동", "식각", "챔버 세정", ["MAT-05"], 2, 5),
        ("가-확산-02", "가동", "확산", "다결정 증착", ["MAT-06", "MAT-07"], 2, 4),
        ("가-주입-01", "가동", "확산", "고전류 이온 주입", ["MAT-08", "MAT-07"], 2, 4),
        ("나-세정-02", "나동", "세정", "접촉부 세정", ["MAT-01", "MAT-03"], 3, 6),
        ("나-증착-01", "나동", "증착", "금속 플러그 증착", ["MAT-09", "MAT-06"], 2, 5),
        ("나-노광-01", "나동", "노광", "불화아르곤 현상", ["MAT-10", "MAT-11"], 2, 5),
        ("나-연마-01", "나동", "연마", "산화막 연마", ["MAT-12"], 2, 4),
        ("다-세정-01", "다동", "세정", "연마 후 세정", ["MAT-01", "MAT-02"], 3, 5),
        ("다-노광-02", "다동", "노광", "불화크립톤 현상", ["MAT-10", "MAT-11"], 2, 4),
        ("나-세정-03", "나동", "세정", "신규 세정 설비", ["MAT-01"], 4, 0)]
STOS = [("STO-가-A", "가동 약품 공급실 A", ["MAT-01", "MAT-02", "MAT-03"]), ("STO-나-B", "나동 약품 공급실 B", ["MAT-01", "MAT-03"]),
        ("STO-다-A", "다동 약품 창고 A", ["MAT-01", "MAT-02"])]
EMIS = [("EMI-가-03", "가동 배출구 3", ["가-세정-01", "가-세정-02", "가-세정-03"]), ("EMI-나-07", "나동 배출구 7", ["나-세정-02", "나-증착-01"]),
        ("EMI-다-02", "다동 배출구 2", ["다-세정-01"])]
NAMES = ["가람", "나래", "다온", "라온", "마루", "바다", "새론", "아라", "자람", "차오름", "한울", "누리", "미르", "보람", "슬기", "이든",
         "도담", "소담", "해솔", "여울"]

def equipment():
    rows = []
    for p in PRCS:
        for i in range(1, p[5] + 1):
            planned = p[0] == "나-세정-03"
            rows.append((f"{p[0]}-E{i}", "습식 세정기" if p[2] == "세정" else "공정 챔버", "계획" if planned else "운영", p[0]))
    return rows

def workers():
    rows, n = [], 0
    for p in PRCS:
        for i in range(p[6]):
            n += 1
            role = "파트장" if i == 0 else "설비기술자" if i < 2 else "운전원"
            trained = f"2025-{1 + (n * 7) % 12:02d}-{1 + (n * 11) % 27:02d}"
            rows.append((f"W-{1000 + n}", f"{NAMES[(n - 1) % 20]} {1 + (n - 1) // 20}", role, trained, p[0]))
    return rows

EQPS = equipment()
WRKS = workers()
INCS = [("INC-2024-031", "아차사고", "2024-06-11", "다-세정-01-E2", "MAT-01"), ("INC-2025-012", "아차사고", "2025-02-03", "나-세정-02-E3", "MAT-01"),
        ("INC-2023-077", "아차사고", "2023-11-20", "가-세정-01-E2", "MAT-01"), ("INC-2025-091", "아차사고", "2025-09-05", "가-세정-02-E3", "MAT-01"),
        ("INC-2025-117", "가스 경보", "2025-09-17", "가-확산-02-E1", "MAT-07")]
PMTS = [("PMT-0917-03", "화학물질 작업", "가-세정-02"), ("PMT-0917-05", "밀폐공간 작업", "가-확산-02"),
        ("PMT-0917-11", "화기 작업", "가-세정-02"), ("PMT-0917-08", "전기 작업", "가-세정-03")]
# 요건이 적용되는 다른 대상
REQ_STO = [("REQ-06", s[0]) for s in STOS]
REQ_EMI = [("REQ-07", e[0]) for e in EMIS]
# 설비에 걸린 요건. REQ-04 는 실란을 쓰는 공정의 설비 하나에만 걸려 있어, 실란 자료가 바뀌면 깊이 4 에서야 닿는다.
REQ_EQP = [("REQ-05", "가-세정-01-E1"), ("REQ-05", "가-세정-03-E1"), ("REQ-04", "가-확산-02-E1")]
# 증빙: 변경 교육을 이수한 인원 25명 (앞의 34명 가운데 넷마다 하나를 빼고 앞에서부터 25명. W-1033 까지 닿는다)
EVID = [("REQ-02", w[0]) for i, w in enumerate(WRKS[:34]) if i % 4 != 3][:25]

# ── 입력 자료의 판 (열 이름, 행) ─────────────────────────────────
def yn(b): return "예" if b else "아니오"

def sds_rows(version_of, hazard_of, limit_of, special_of):
    rows = []
    for i, s in enumerate(SUBS):
        k = SDS_OF[s[0]]
        rows.append([k, str(version_of.get(k, 6 if i == 0 else 3 + i % 4)), revised.get(k, f"2025-{1 + i % 8:02d}-15"),
                     hazard_of.get(k, HAZARD[i]), limit_of.get(k, LIMIT[i]), yn(special_of.get(k, False))])
    return rows

revised = {}
SOURCES = {}  # 입력 자료 → [(파일 이름, 적용일, 열, 행)]
def edition(source, name, date, cols, rows): SOURCES.setdefault(source, []).append((name, date, cols, [list(map(str, r)) for r in rows]))

D0 = "2026-01-01"
edition("regulation", "regulation-v1.csv", D0, ["법규번호", "법규명칭", "시행일자"], REGS)
edition("requirement", "requirement-v1.csv", D0, ["요건번호", "요건명칭", "요건구분", "근거법규"], [r[:4] for r in REQS])
edition("requirement-substance", "requirement-substance-v1.csv", D0, ["요건번호", "물질번호"], [(r[0], m) for r in REQS for m in r[4]])
edition("requirement-storage", "requirement-storage-v1.csv", D0, ["요건번호", "보관소번호"], REQ_STO)
edition("requirement-emission", "requirement-emission-v1.csv", D0, ["요건번호", "배출구번호"], REQ_EMI)
edition("requirement-equipment", "requirement-equipment-v1.csv", D0, ["요건번호", "설비번호"], REQ_EQP)
edition("substance", "substance-v1.csv", D0, ["물질번호", "물질명칭", "특별관리여부", "자료번호"], [(s[0], s[1], yn(s[2]), SDS_OF[s[0]]) for s in SUBS])
SDS_COLS = ["자료번호", "자료판번호", "자료개정일", "유해성구분", "노출기준값", "자료특별관리"]
edition("safety-sheet", "safety-sheet-v1.csv", D0, SDS_COLS, sds_rows({}, {}, {}, {}))
edition("process", "process-v1.csv", D0, ["공정번호", "공정명칭", "소속동", "공정구역", "공정상태"],
        [(p[0], p[3], p[1], p[2], "계획" if p[0] == "나-세정-03" else "운영") for p in PRCS])
edition("process-substance", "process-substance-v1.csv", D0, ["공정번호", "물질번호"], [(p[0], m) for p in PRCS for m in p[4]])
edition("equipment", "equipment-v1.csv", D0, ["설비번호", "설비종류", "설비상태", "소속공정"], EQPS)
edition("storage", "storage-v1.csv", D0, ["보관소번호", "보관소명칭", "표지판번호"], [(s[0], s[1], 6) for s in STOS])
edition("substance-storage", "substance-storage-v1.csv", D0, ["물질번호", "보관소번호"], [(m, s[0]) for s in STOS for m in s[2]])
edition("emission-point", "emission-point-v1.csv", D0, ["배출구번호", "배출구명칭"], [e[:2] for e in EMIS])
edition("process-emission", "process-emission-v1.csv", D0, ["공정번호", "배출구번호"], [(p, e[0]) for e in EMIS for p in e[2]])
WRK_COLS = ["사번", "인원이름", "인원역할", "교육이수일", "배치공정"]
edition("worker", "worker-v1.csv", D0, WRK_COLS, WRKS)
edition("incident", "incident-v1.csv", D0, ["사고번호", "사고종류", "사고발생일", "관련설비"], [i[:4] for i in INCS])
edition("incident-substance", "incident-substance-v1.csv", D0, ["사고번호", "물질번호"], [(i[0], i[4]) for i in INCS])
edition("permit", "permit-v1.csv", D0, ["허가번호", "허가종류", "대상공정"], PMTS)
edition("evidence", "evidence-v1.csv", D0, ["요건번호", "사번"], EVID)

# 2026-09-02: 물질안전자료가 바뀐다. 불산 자료가 7판이 되며 유해성 구분이 오르고 특별관리 대상이 된다. 황산 자료는 6판에서 7판이 되며 노출기준이 바뀐다.
DA = "2026-09-02"
revised.update({"SDS-01": DA, "SDS-04": DA})
edition("safety-sheet", "safety-sheet-v2.csv", DA, SDS_COLS,
        sds_rows({"SDS-01": 7, "SDS-04": 7}, {"SDS-01": "급성독성 구분2"}, {"SDS-04": "0.1"}, {"SDS-01": True}))
# 2026-10-01: 인원이 바뀐다. W-1003 이 가-세정-01 에서 나-세정-02 로 옮기고, W-1061 이 다-세정-01 에 새로 오고, W-1052 가 빠진다.
DB = "2026-10-01"
moved = [list(w) for w in WRKS if w[0] != "W-1052"]
for w in moved:
    if w[0] == "W-1003": w[4] = "나-세정-02"
moved.append(["W-1061", "가람 4", "운전원", "2026-09-20", "다-세정-01"])
edition("worker", "worker-v2.csv", DB, WRK_COLS, moved)
# 2026-10-15: 계획이던 공정 나-세정-03 이 운영을 시작한다.
DC = "2026-10-15"
edition("process", "process-v2.csv", DC, ["공정번호", "공정명칭", "소속동", "공정구역", "공정상태"],
        [(p[0], p[3], p[1], p[2], "운영") for p in PRCS])
# 같은 날: 그 공정의 설비 넷도 운영이 된다.
edition("equipment", "equipment-v2.csv", DC, ["설비번호", "설비종류", "설비상태", "소속공정"], [(e[0], e[1], "운영", e[3]) for e in EQPS])
# 2026-11-20: 불산 자료가 한 번 더 바뀐다 (8판). 실란 자료도 바뀐다.
DD = "2026-11-20"
# 같은 날 먼저: 공정 다-세정-01 이 불산을 더 쓰지 않는다. 이 관계는 2026-11-20 에 끝난다.
edition("process-substance", "process-substance-v2.csv", DD, ["공정번호", "물질번호"],
        [(p[0], m) for p in PRCS for m in p[4] if (p[0], m) != ("다-세정-01", "MAT-01")])
revised.update({"SDS-01": DD, "SDS-06": DD})
edition("safety-sheet", "safety-sheet-v3.csv", DD, SDS_COLS,
        sds_rows({"SDS-01": 8, "SDS-04": 7, "SDS-06": 6}, {"SDS-01": "급성독성 구분2", "SDS-06": "인화성 가스 구분1, 자연발화성"},
                 {"SDS-04": "0.1"}, {"SDS-01": True}))

# 시나리오: 투입하는 차례
ORDER = ["regulation", "requirement", "substance", "safety-sheet", "process", "process-substance", "equipment", "storage",
         "substance-storage", "emission-point", "process-emission", "worker", "incident", "incident-substance", "permit", "evidence",
         "requirement-substance", "requirement-storage", "requirement-emission", "requirement-equipment"]
STEPS = [(s, 0) for s in ORDER] + [("safety-sheet", 1), ("worker", 1), ("process", 1), ("equipment", 1), ("process-substance", 1), ("safety-sheet", 2)]

# ── 매핑 (매핑 파일과 같아야 한다): 행 → 개체의 속성, 관계 ─────────────────
# 입력 자료 → (키 열, 주 개체 (유형, 열), 개체 [(유형, 열, {속성: 열})], 관계 [(관계 유형, (출발 유형, 열), (도착 유형, 열))])
MAPPING = {
    "regulation": (["법규번호"], (T_REG, "법규번호"), [(T_REG, "법규번호", {"법규명칭": "법규명칭", "시행일자": "시행일자"})], []),
    "requirement": (["요건번호"], (T_REQ, "요건번호"), [(T_REQ, "요건번호", {"요건명칭": "요건명칭", "요건구분": "요건구분"})],
                    [("요건부과", (T_REG, "근거법규"), (T_REQ, "요건번호"))]),
    "requirement-substance": (["요건번호", "물질번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_SUB, "물질번호"))]),
    "requirement-storage": (["요건번호", "보관소번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_STO, "보관소번호"))]),
    "requirement-emission": (["요건번호", "배출구번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_EMI, "배출구번호"))]),
    "requirement-equipment": (["요건번호", "설비번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_EQP, "설비번호"))]),
    "substance": (["물질번호"], (T_SUB, "물질번호"), [(T_SUB, "물질번호", {"물질명칭": "물질명칭", "특별관리여부": "특별관리여부"})],
                  [("자료기술", (T_SUB, "물질번호"), (T_SDS, "자료번호"))]),
    "safety-sheet": (["자료번호"], (T_SDS, "자료번호"), [(T_SDS, "자료번호", {"자료판번호": "자료판번호", "자료개정일": "자료개정일",
                     "유해성구분": "유해성구분", "노출기준값": "노출기준값", "자료특별관리": "자료특별관리"})], []),
    "process": (["공정번호"], (T_PRC, "공정번호"), [(T_PRC, "공정번호", {"공정명칭": "공정명칭", "소속동": "소속동", "공정구역": "공정구역",
                "공정상태": "공정상태"})], []),
    "process-substance": (["공정번호", "물질번호"], (T_PRC, "공정번호"), [], [("물질사용", (T_PRC, "공정번호"), (T_SUB, "물질번호"))]),
    "equipment": (["설비번호"], (T_EQP, "설비번호"), [(T_EQP, "설비번호", {"설비종류": "설비종류", "설비상태": "설비상태"})],
                  [("설비구성", (T_PRC, "소속공정"), (T_EQP, "설비번호"))]),
    "storage": (["보관소번호"], (T_STO, "보관소번호"), [(T_STO, "보관소번호", {"보관소명칭": "보관소명칭", "표지판번호": "표지판번호"})], []),
    "substance-storage": (["물질번호", "보관소번호"], (T_SUB, "물질번호"), [], [("물질보관", (T_SUB, "물질번호"), (T_STO, "보관소번호"))]),
    "emission-point": (["배출구번호"], (T_EMI, "배출구번호"), [(T_EMI, "배출구번호", {"배출구명칭": "배출구명칭"})], []),
    "process-emission": (["공정번호", "배출구번호"], (T_PRC, "공정번호"), [], [("배기연결", (T_PRC, "공정번호"), (T_EMI, "배출구번호"))]),
    "worker": (["사번"], (T_WRK, "사번"), [(T_WRK, "사번", {"인원이름": "인원이름", "인원역할": "인원역할", "교육이수일": "교육이수일"})],
               [("인원배치", (T_PRC, "배치공정"), (T_WRK, "사번"))]),
    "incident": (["사고번호"], (T_INC, "사고번호"), [(T_INC, "사고번호", {"사고종류": "사고종류", "사고발생일": "사고발생일"})],
                 [("사고관련", (T_INC, "사고번호"), (T_EQP, "관련설비"))]),
    "incident-substance": (["사고번호", "물질번호"], (T_INC, "사고번호"), [], [("사고관련", (T_INC, "사고번호"), (T_SUB, "물질번호"))]),
    "permit": (["허가번호"], (T_PMT, "허가번호"), [(T_PMT, "허가번호", {"허가종류": "허가종류"})],
               [("허가대상", (T_PMT, "허가번호"), (T_PRC, "대상공정"))]),
    "evidence": (["요건번호", "사번"], (T_REQ, "요건번호"), [], [("이행증빙", (T_REQ, "요건번호"), (T_WRK, "사번"))]),
}
# 규칙 (규칙 파일과 같아야 한다): 번호 → (우선순위, 변동의 종류, 대상 쪽, 대상 유형, 조건들, 기한 일수)
RULES = {
    "0001": (1, {"CHANGED"}, "IMPACT", T_WRK, [("CHANGE", "type", T_SDS)], 14),
    "0002": (2, {"CHANGED"}, "IMPACT", T_EQP, [("CHANGE", "type", T_SDS), ("IMPACT", "설비상태", "운영")], 7),
    "0003": (2, {"CHANGED"}, "IMPACT", T_STO, [("CHANGE", "type", T_SDS)], 7),
    "0004": (1, {"CHANGED"}, "CHANGE", T_SDS, [], 3),
    "0005": (1, {"CHANGED"}, "IMPACT", T_WRK, [("CHANGE", "type", T_SDS), ("CHANGE", "자료특별관리", "예")], 30),
    "0006": (2, {"ADDED", "CHANGED"}, "CHANGE", T_WRK, [], 10),
}

# ── 문서의 규칙을 따로 구현한 계산 ────────────────────────────────
def key_of(source, row, cols):
    return "|".join(row[cols.index(c)] for c in MAPPING[source][0])

def row_facts(source, row, cols):
    """행이 만드는 것: 개체(참조 포함), 속성 값, 관계."""
    _, primary, entities, relations = MAPPING[source]
    v = lambda c: row[cols.index(c)]
    ents, attrs, rels = [], {}, []
    for t, c, props in entities:
        ents.append(eid(t, v(c)))
        for p, pc in props.items():
            if v(pc) != "": attrs[(eid(t, v(c)), p)] = v(pc)
    for rt, (st, sc), (tt, tc) in relations:
        if v(sc) == "" or v(tc) == "": continue
        rels.append((rt, eid(st, v(sc)), eid(tt, v(tc))))
        ents += [eid(st, v(sc)), eid(tt, v(tc))]
    prim = eid(primary[0], v(primary[1]))
    if prim not in ents: ents.append(prim)
    return prim, sorted(set(ents)), attrs, rels

REL, ATT = [], []   # 관계 [rt, s, t, from, to, source], 속성 [entity, prop, value, from, to, source]
IMPACTS, ACTIONS = {}, {}
EDITION_NO, EVENTS = {}, {}  # 단계 → 변동 [(항목의 키, 종류, 주 개체, 변동 대상, 적용일, 입력 자료, 판 번호)]
PREV = {}
def ingest(step_no, source, idx):
    name, date, cols, rows = SOURCES[source][idx]
    EDITION_NO[source] = EDITION_NO.get(source, 0) + 1
    new = {key_of(source, r, cols): row_facts(source, r, cols) for r in rows}
    old = PREV.get(source, {})
    events = []
    if source in PREV:
        for k in sorted(set(new) | set(old)):
            if k in new and k not in old: kind, targets, prim = "ADDED", new[k][1], new[k][0]
            elif k in old and k not in new: kind, targets, prim = "REMOVED", old[k][1], old[k][0]
            elif new[k][2] != old[k][2] or new[k][3] != old[k][3]:
                kind, targets, prim = "CHANGED", sorted(set(new[k][1]) | set(old[k][1])), new[k][0]
            else: continue
            events.append((k, kind, prim, targets, date, source, EDITION_NO[source]))
    # 관계: 이 입력 자료가 넣은 것 가운데 새 판에 없는 것은 적용일로 끝내고, 새로 생긴 것은 적용일부터 시작한다.
    now_rels = {rel for f in new.values() for rel in f[3]}
    for r in REL:
        if r[5] == source and r[4] is None and (r[0], r[1], r[2]) not in now_rels: r[4] = date
    live = {(r[0], r[1], r[2]) for r in REL if r[5] == source and r[4] is None}
    for rel in sorted(now_rels - live): REL.append([rel[0], rel[1], rel[2], date, None, source])
    now_att = {k: val for f in new.values() for k, val in f[2].items()}
    for a in ATT:
        if a[5] == source and a[4] is None and now_att.get((a[0], a[1])) != a[2]: a[4] = date
    live_att = {(a[0], a[1]) for a in ATT if a[5] == source and a[4] is None}
    for k, val in sorted(now_att.items()):
        if k not in live_att: ATT.append([k[0], k[1], val, date, None, source])
    PREV[source] = new
    EVENTS[step_no] = events
    # 영향 범위와 조치는 판을 투입한 직후의 자료로 계산해 둔다 (D2 AD-119). 뒤에 들어온 판은 닿지 않는다.
    for e in events:
        IMPACTS[(step_no, e[0])] = impact(e)
        ACTIONS[(step_no, e[0])] = actions(e)

def valid(item_from, item_to, day): return item_from <= day and (item_to is None or day < item_to)

def attr(entity, prop, day, prev):
    for d in (day, prev):
        for a in ATT:
            if a[0] == entity and a[1] == prop and valid(a[3], a[4], d): return a[2]
    return None

def type_of(entity): return entity.split("|")[1]

def bfs(starts, day):
    """그 날짜에 유효한 관계로 영향 방향대로 따라간다. 대상 → (깊이, 경로, 시작한 변동 대상). 한계 너머까지 본다."""
    found, frontier, depth = {}, [(s, [], s) for s in sorted(starts)], 0
    seen = set(starts)
    while frontier:
        depth += 1
        nxt = {}
        for node, path, start in frontier:
            for r in REL:
                if not valid(r[3], r[4], day) or DIRECTION[r[0]] is None: continue
                if DIRECTION[r[0]] == "ST" and r[1] == node: other = r[2]
                elif DIRECTION[r[0]] == "TS" and r[2] == node: other = r[1]
                else: continue
                if other in seen: continue
                cand = (path + [(r[0], r[1], r[2], r[3])], start)
                # 길이가 같은 경로가 둘이면 관계 식별자로 가려야 한다. 이 자료에는 그런 경우가 없어야 한다 (있으면 멈춘다).
                if other in nxt and nxt[other] != cand: raise SystemExit(f"경로가 둘이다: {other}")
                nxt[other] = cand
        for other, (path, start) in nxt.items():
            found[other] = (depth, path, start)
            seen.add(other)
        frontier = [(o, p, s) for o, (p, s) in nxt.items()]
    return found

def impact(event):
    _, kind, prim, targets, date, source, edition_no = event
    day = date
    prev = (D(date) - datetime.timedelta(days=1)).isoformat()
    on_prev, on_day = bfs(targets, prev), bfs(targets, day)
    merged = {}
    for o in set(on_prev) | set(on_day):
        a, b = on_prev.get(o), on_day.get(o)
        # 더 짧은 경로를, 길이가 같으면 더 늦은 날짜(적용일)의 경로를 남긴다.
        if b is not None and (a is None or b[0] <= a[0]): merged[o] = (b[0], b[1], b[2], day)
        else: merged[o] = (a[0], a[1], a[2], prev)
    within = {o: v for o, v in merged.items() if v[0] <= MAX_DEPTH}
    # 한계에서 멈췄다: 어느 날짜에서든 최대 깊이 너머에 합친 결과(깊이 안)에 없는 대상이 있다.
    truncated = any(o not in within for o in list(on_prev) + list(on_day))
    ordered = sorted(within.items(), key=lambda kv: (kv[1][0], type_of(kv[0]), kv[0]))
    return ordered, truncated, day, prev

def actions(event):
    key, kind, prim, targets, date, source, edition_no = event
    ordered, truncated, day, prev = impact(event)
    out = []
    for no, (priority, kinds, side, target_type, conds, due) in RULES.items():
        if kind not in kinds: continue
        candidates = [(o, v[2]) for o, v in ordered] if side == "IMPACT" else [(t, t) for t in sorted(targets, key=lambda t: (type_of(t), t))]
        for target, start in candidates:
            if type_of(target) != target_type: continue
            ok = True
            for subject, what, value in conds:
                who = start if subject == "CHANGE" else target
                if what == "type": ok &= type_of(who) == value
                else: ok &= attr(who, what, day, prev) == value
            if ok:
                due_date = (D(date) + datetime.timedelta(days=due)).isoformat()
                out.append((due_date, priority, no, target, prim, source, edition_no))
    # 제품이 내놓는 순서: 기한, 우선순위, 규칙 번호, 대상의 식별자 (D2 AD-243)
    return sorted(out)

def relations_as_of(entity, day):
    rows = []
    for r in REL:
        if valid(r[3], r[4], day) and entity in (r[1], r[2]):
            other = r[2] if r[1] == entity else r[1]
            rows.append((r[0], other, r[1], r[2], r[3], r[4]))
    keys = [(r[0], r[1]) for r in rows]
    if len(keys) != len(set(keys)): raise SystemExit(f"관계 유형과 상대가 같은 관계가 둘이다: {entity}")
    return sorted(rows)


for n, (source, idx) in enumerate(STEPS, start=1): ingest(n, source, idx)

# ── 파일 쓰기 ─────────────────────────────────────────────────
os.makedirs(os.path.join(OUT, "data"), exist_ok=True)
os.makedirs(os.path.join(OUT, "questions"), exist_ok=True)
for source, editions in SOURCES.items():
    for name, date, cols, rows in editions:
        buf = io.StringIO()
        w = csv.writer(buf, lineterminator="\n")
        w.writerow(cols)
        w.writerows(rows)
        open(os.path.join(OUT, "data", name), "w", encoding="utf-8").write(buf.getvalue())

def ref(entity):
    _, t, k = entity.split("|")
    return "{ type: %s, key: %s }" % (t, k)

def find(step, key):
    for e in EVENTS[step]:
        if e[0] == key: return e
    raise SystemExit(f"{step}단계에 {key} 의 변동이 없다")

STEP_A, STEP_B, STEP_C, STEP_E, STEP_F, STEP_D = (len(ORDER) + i for i in range(1, 7))
y = []
y.append("""# 제조 안전 Pack 의 검증 질문. 형식은 D4 3절이다.
#
# 상태: 정답 초안. 대표가 확정하기 전이다 (D4 4절: 정답은 대표가 정한다).
# 모든 이름과 값은 지어낸 것이다. 실제 회사, 현장, 법령, 문서와 관계가 없다.
#
# 정답을 만든 방법: 자료가 커서 (개체 157개) 손으로 적지 않았다. 예시 입력 파일에서 문서의 규칙
# (D3 6절·7절의 변동 대상과 영향 범위, D8 의 두 날짜와 규칙의 조건, D7 의 순서)을 엔진과 따로 구현한 계산으로 만들었다.
# 제품이 낸 답을 옮겨 적은 것이 아니다. 질문마다 무엇 때문에 그 답인지를 주석으로 적었다.
#
# 개체는 유형의 이름과 입력의 키로 가리킨다. 목록의 순서는 제품이 내놓는 순서다 (D4 4절, D2 AD-282).
# 영향 범위와 조치는 판을 투입한 직후에 그때의 자료로 한 번 계산해 저장한 것이다 (D2 AD-119).
# 영향이 퍼지는 길: 물질안전자료 → 화학물질 → 제조공정·약품보관소·법정요건 → 공정설비·작업인원 (최대 깊이 3).
#
# 이 질문들이 묻지 않는 것: 길이가 같은 근거 경로가 둘인 경우, 관계 유형과 상대 개체가 같은 관계가 둘인 경우.
# 정답을 만든 계산이 그 두 규칙(관계 식별자로 가리기)을 구현하지 않아 그런 자료를 넣지 않았다.
# 그 규칙은 엔진의 일반 시험과 시험용 Pack 의 질문이 본다.
""")
y.append("scenario:\n  id: S-01\n  title: 물질안전자료가 두 번 바뀌고 인원이 옮기고 공정이 운영을 시작하고 물질 하나를 쓰지 않게 된다\n  steps:")
y.append("    # 2026-01-01: 처음 자료가 들어온다. 모두 첫 판이라 변동은 없다.")
for n, (source, idx) in enumerate(STEPS, start=1):
    name, date, _, _ = SOURCES[source][idx]
    note = {STEP_A: "    # 2026-09-02: 불산 자료(SDS-01)가 7판이 되며 유해성 구분이 오르고 특별관리 대상이 된다. 황산 자료(SDS-04)는 7판이 되며 노출기준이 바뀐다.",
            STEP_B: "    # 2026-10-01: W-1003 이 가-세정-01 에서 나-세정-02 로 옮기고, W-1061 이 새로 오고, W-1052 가 빠진다.",
            STEP_C: "    # 2026-10-15: 계획이던 공정 나-세정-03 이 운영을 시작한다. 같은 날 그 공정의 설비 넷도 운영이 된다.",
            STEP_F: "    # 2026-11-20: 공정 다-세정-01 이 불산을 더 쓰지 않는다. 그 뒤에 같은 날의 물질안전자료가 들어온다 (차례가 정답에 닿는다).",
            STEP_D: "    # 2026-11-20: 불산 자료가 한 번 더 바뀐다 (8판). 실란 자료(SDS-06)도 바뀐다."}.get(n)
    if note: y.append(note)
    y.append("    - { step: %d, inputSource: %s, file: data/%s, effectiveDate: %s }" % (n, source, name, date))
y.append("\n# 시점: 과거 2026-01-01 과 2026-09-30 / 2026-10-01 (인원이 옮기는 경계), 현재 2026-12-01 (마지막 판 뒤).\n\nquestions:\n")

def q_relations(qid, title, t, k, day, why):
    y.append("  - id: %s\n    kind: relations-as-of\n    title: %s\n    asOf: %s\n    entity: { type: %s, key: %s }" % (qid, title, day, t, k))
    y.append("    # " + why)
    rows = relations_as_of(eid(t, k), day)
    if not rows:
        y.append("    expect:\n      relations: []\n")
        return
    y.append("    expect:\n      relations:")
    for rt, other, s, tgt, f, to in rows:
        y.append("        - { type: %s, source: %s, target: %s, validFrom: %s, validTo: %s }" % (rt, ref(s), ref(tgt), f, to or "null"))
    y.append("")

def q_changes(qid, title, step, why):
    y.append("  - id: %s\n    kind: changes\n    title: %s\n    step: %d\n    # %s\n    expect:\n      changes:" % (qid, title, step, why))
    for e in EVENTS[step]:
        y.append("        - { itemKey: [%s], kind: %s }" % (e[0], e[1].lower()))
    y.append("")

def q_impact(qid, title, step, key, why):
    e = find(step, key)
    ordered, truncated, day, prev = IMPACTS[(step, key)]
    y.append("  - id: %s\n    kind: impact\n    title: %s\n    change: { step: %d, itemKey: [%s] }\n    # %s\n    expect:\n      targets:" % (qid, title, step, key, why))
    by_type, by_depth = {}, {}
    for o, (depth, path, start, used) in ordered:
        rels = ", ".join("{ type: %s, source: %s, target: %s }" % (p[0], ref(p[1]), ref(p[2])) for p in path)
        y.append("        - entity: %s\n          depth: %d\n          path: { from: %s, asOf: %s, relations: [ %s ] }" % (ref(o), depth, ref(start), used, rels))
        by_type[type_of(o)] = by_type.get(type_of(o), 0) + 1
        by_depth[depth] = by_depth.get(depth, 0) + 1
    if not ordered: y[-1] = y[-1] + " []"
    y.append("      countsByType: { %s }" % ", ".join("%s: %d" % kv for kv in sorted(by_type.items())))
    y.append("      countsByDepth: { %s }" % ", ".join("%d: %d" % kv for kv in sorted(by_depth.items())))
    y.append("      truncated: %s\n" % ("true" if truncated else "false"))
    return ordered, truncated

def q_actions(qid, title, step, key, why):
    e = find(step, key)
    acts = ACTIONS[(step, key)]
    y.append("  - id: %s\n    kind: actions\n    title: %s\n    change: { step: %d, itemKey: [%s] }\n    # %s\n    expect:\n      actions:%s" % (qid, title, step, key, why, "" if acts else " []"))
    for due, priority, no, target, prim, source, edition_no in acts:
        y.append('        - { rule: "%s", priority: %d, target: %s, dueDate: %s, basis: { entity: %s, inputSource: %s, edition: %d } }'
                 % (no, priority, ref(target), due, ref(prim), source, edition_no))
    y.append("")
    return acts

y.append("  # ── 시점 조회 ──────────────────────────────────────────────\n")
q_relations("Q-01", "처음 자료가 들어온 날, 불산(MAT-01)의 관계", T_SUB, "MAT-01", "2026-01-01",
            "시작일 당일이라 들어온다. 불산을 쓰는 공정 다섯, 보관소 셋, 걸린 요건 일곱, 관련 사고 넷, 자료 하나다. 순서는 관계 유형, 상대 개체.")
q_relations("Q-02", "W-1003 이 옮기기 전날의 관계 (종료일 전날)", T_WRK, "W-1003", "2026-09-30",
            "배치는 2026-10-01 에 끝난다. 전날에는 가-세정-01 의 배치가 아직 유효하다. 변경 교육의 증빙도 이 인원을 가리킨다.")
q_relations("Q-03", "W-1003 이 옮긴 날의 관계 (종료일 당일, 새 시작일 당일)", T_WRK, "W-1003", "2026-10-01",
            "가-세정-01 의 배치는 이날 끝나 들지 않고, 나-세정-02 의 배치가 이날 시작해 든다.")
q_relations("Q-04", "W-1052 가 빠진 날의 관계", T_WRK, "W-1052", "2026-10-01",
            "인원의 행이 사라져 배치는 2026-10-01 에 끝났다. 이 인원을 가리키는 다른 관계가 없으면 빈 목록이다.")
q_relations("Q-05", "현재, 공정 나-세정-02 의 관계", T_PRC, "나-세정-02", "2026-12-01",
            "옮겨 온 W-1003 의 배치는 시작일이 2026-10-01 이다. 나머지는 처음 자료의 것이다. 배기와 작업허가도 든다.")
y.append("  # ── 변동 ───────────────────────────────────────────────────\n")
q_changes("Q-06", "물질안전자료의 둘째 판에서 나온 변동", STEP_A, "불산 자료와 황산 자료의 행이 바뀌었다. 나머지 열 행은 그대로다. 순서는 항목의 키.")
q_changes("Q-07", "인원의 둘째 판에서 나온 변동 (추가, 변경, 삭제가 모두 있다)", STEP_B, "W-1003 은 배치 공정이 바뀌어 변경, W-1052 는 행이 없어져 삭제, W-1061 은 새 행이라 추가.")
q_changes("Q-08", "물질안전자료의 셋째 판에서 나온 변동 (한 번 바뀐 것이 또 바뀐다)", STEP_D, "불산 자료가 8판이 되고, 실란 자료가 처음 바뀐다. 황산 자료는 둘째 판과 같아 변동이 아니다.")
q_changes("Q-17", "설비의 둘째 판에서 나온 변동", STEP_E, "나-세정-03 의 설비 넷의 상태가 계획에서 운영으로 바뀌었다. 나머지 스물여덟 대는 그대로다.")
y.append("  # ── 영향 범위 ──────────────────────────────────────────────\n")
i9, t9 = q_impact("Q-09", "불산 자료가 7판이 된 것의 영향 범위", STEP_A, "SDS-01",
                  "변동 대상은 물질안전자료 SDS-01 하나다 (이 자료의 행은 물질을 가리키지 않는다). 깊이 1 은 불산, 깊이 2 는 불산을 쓰는 공정 다섯과 보관소 셋과 걸린 요건 일곱, 깊이 3 은 그 공정의 설비와 인원이다. 설비에 걸린 요건(REQ-05)은 이미 깊이 2 에서 찾았으므로 한계 너머에 새 대상이 없다.")
i10, t10 = q_impact("Q-10", "황산 자료의 노출기준이 바뀐 것의 영향 범위", STEP_A, "SDS-04",
                    "황산을 쓰는 공정은 가-세정-03 하나다. 황산은 보관소에 없다. 걸린 요건은 셋이다.")
i11, t11 = q_impact("Q-11", "W-1003 이 옮긴 것의 영향 범위 (참조로만 나온 공정도 변동 대상이다)", STEP_B, "W-1003",
                    "변동 대상은 W-1003 과, 앞 판과 새 판의 행이 가리킨 두 공정(가-세정-01, 나-세정-02)이다. 두 공정의 설비와 인원이 깊이 1 이고, 설비 가-세정-01-E1 에 걸린 요건 REQ-05 가 깊이 2 다. 공정에서 물질 쪽으로는 퍼지지 않는다 (물질사용은 물질에서 공정으로만).")
i16, t16 = q_impact("Q-16", "실란 자료가 바뀐 것의 영향 범위 (한계에서 멈춘다)", STEP_D, "SDS-06",
                    "실란을 쓰는 공정은 가-확산-02 와 나-증착-01 이다. 실란은 보관소에 없고 걸린 요건도 없다. 깊이 3 의 설비 가-확산-02-E1 에 걸린 요건 REQ-04 는 깊이 4 라 들지 않고, 그래서 한계에서 멈췄다는 표시가 켜진다.")
i18, t18 = q_impact("Q-18", "공정 나-세정-03 이 운영을 시작한 것의 영향 범위", STEP_C, "나-세정-03",
                    "변동 대상은 공정 나-세정-03 하나다. 그 공정의 설비 넷이 깊이 1 이다. 배치된 인원은 없다. 공정에서 물질 쪽으로는 퍼지지 않는다.")
i21, t21 = q_impact("Q-21", "불산 자료가 8판이 된 것의 영향 범위 (적용일 전날의 관계로만 닿는 대상이 있다)", STEP_D, "SDS-01",
                    "공정 다-세정-01 이 불산을 쓰는 관계는 같은 날(2026-11-20) 먼저 들어온 판에서 끝났다. 적용일에는 유효하지 않고 전날(2026-11-19)에는 유효하다. 그래서 다-세정-01 과 그 설비 셋, 인원 다섯은 전날의 관계로만 닿고 근거 경로의 날짜가 2026-11-19 다. 나머지는 두 날짜 모두 닿아 적용일의 경로가 남는다. 옮겨 온 W-1003 은 나-세정-02 의, 새로 온 W-1061 은 다-세정-01 의 인원으로 든다.")
y.append("  # ── 조치 ───────────────────────────────────────────────────\n  # 순서는 제품이 내놓는 순서다: 기한이 이른 것부터, 같으면 우선순위, 규칙 번호, 대상 식별자 (D2 AD-243, AD-282).\n  # 근거는 변동이 생긴 행의 주 개체와, 그 변동이 나온 입력 자료의 판이다 (D2 AD-120).\n")
a12 = q_actions("Q-12", "불산 자료가 7판이 되어 생긴 조치", STEP_A, "SDS-01",
                "규칙 0004 (자료 자신의 게시물 교체, 3일), 0002 (운영 중인 설비의 표지, 7일: 계획인 나-세정-03 의 설비 넷은 빠진다), 0003 (보관소 표지, 7일), 0001 (영향받은 인원의 변경 교육, 14일), 0005 (자료가 특별관리라 특수검진 대상 갱신, 30일).")
a13 = q_actions("Q-13", "황산 자료의 노출기준이 바뀌어 생긴 조치", STEP_A, "SDS-04",
                "황산 자료는 특별관리가 아니라 규칙 0005 는 걸리지 않는다. 보관소가 없어 0003 도 없다.")
a14 = q_actions("Q-14", "W-1003 이 옮겨 생긴 조치", STEP_B, "W-1003",
                "규칙 0006 (배치가 바뀐 인원의 배치 전 검진, 10일) 하나다. 변동 대상인 인원 자신에게 건다. 변동 대상이 물질안전자료가 아니라 0001 ~ 0005 는 걸리지 않는다.")
a15 = q_actions("Q-15", "불산 자료가 8판이 되어 생긴 조치 (운영이 된 설비와 옮긴 인원이 반영된다)", STEP_D, "SDS-01",
                "나-세정-03 의 설비 넷은 2026-10-15 에 상태가 운영이 되어 이번에는 0002 에 든다 (Q-12 에서는 계획이라 빠졌다). 다-세정-01 은 적용일 전날의 관계로 닿았으므로 그 설비와 인원에게도 조치가 생긴다. W-1052 는 빠져 들지 않는다.")
a19 = q_actions("Q-19", "W-1061 이 새로 와서 생긴 조치 (추가)", STEP_B, "W-1061",
                "규칙 0006 은 추가와 변경에 걸린다. 새로 온 인원 자신에게 배치 전 검진 하나가 생긴다.")
a20 = q_actions("Q-20", "W-1052 가 빠져서 생긴 조치 (삭제에는 조치가 없다)", STEP_B, "W-1052",
                "규칙 0006 은 삭제에는 걸리지 않는다. 다른 규칙은 변동 대상이 물질안전자료가 아니라 걸리지 않는다. 조치가 없다.")
a22 = q_actions("Q-22", "설비 나-세정-03-E1 의 상태가 바뀌어 생긴 조치 (없다)", STEP_E, "나-세정-03-E1",
                "설비의 행이 바뀐 변동에 걸리는 규칙이 없다. 조치가 없다.")
open(os.path.join(OUT, "questions", "S-01.yaml"), "w", encoding="utf-8").write("\n".join(y) + "\n")

n_entities = len({e for f in PREV.values() for x in f.values() for e in x[1]})
print("개체", n_entities, "관계", len(REL), "단계", len(STEPS))
print("Q-09", len(i9), t9, "| Q-10", len(i10), t10, "| Q-11", len(i11), t11, "| Q-16", len(i16), t16)
print("Q-12", len(a12), "| Q-13", len(a13), "| Q-14", len(a14), "| Q-15", len(a15), "| Q-19", len(a19), "| Q-20", len(a20), "| Q-22", len(a22), "| Q-18", len(i18), t18, "| Q-21", len(i21), t21)
for s in (STEP_A, STEP_B, STEP_C, STEP_E, STEP_F, STEP_D): print(s, [(e[0], e[1]) for e in EVENTS[s]])
