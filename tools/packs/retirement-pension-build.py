# 퇴직연금 Pack 의 예시 입력 파일과 검증 질문의 정답을 만든다 (P-01).
#
# 자료의 이름은 모두 지어낸 것이다. 구조(유형 11개, 관계 12개)와 규모(개체 360개 안팎), 한도와 기한의 수치는
# 시연 자료의 설계도를 본떴다 (D2 AD-295, AD-296). 실제 기관, 사업장, 사람, 규정의 이름은 없다.
# 정답은 엔진을 부르지 않고, 문서(D3 6절·7절, D8 1절·2절·3절·4절, D7 의 순서)의 규칙을 이 파일에 따로 구현해 계산한다.
#
# 쓰는 법 (저장소의 맨 위에서): python3 tools/packs/retirement-pension-build.py packs/retirement-pension
# 다시 돌리면 data/ 와 questions/S-01.yaml 을 똑같이 다시 쓴다. 정의 파일(ttl)은 손대지 않는다.
#
# 이 계산이 하지 않는 것 (그래서 이 Pack 의 질문은 이런 경우를 묻지 않는다. 그 규칙은 엔진의 일반 시험과 시험용 Pack 이 본다):
# - 관계 유형과 상대 개체가 같은 관계가 둘일 때 관계 식별자로 차례를 정하는 것 (D3 5절). 시점 조회의 정답을 만들다 만나면 멈춘다.
#   관계의 식별자는 값을 그대로 잇는다: 키에 세로 막대나 역빗금이 없어야 한다 (이 자료에는 없다).
# - 규칙이 상위 유형을 가리킬 때 하위 유형을 포함하는 것 (D8 3절). 이 Pack 의 규칙은 상위 유형을 쓰지 않는다.
# - 값을 읽은 뒤의 표기로 다듬어 "바뀌었는가"를 가리는 것 (0.70 과 0.7, D2 AD-156). 이 자료는 다듬은 표기로만 적는다.
# - 속성 값의 유효기간만 달라진 것, 적었지만 넣지 못한 것 (D8 2절). 이 자료에는 유효기간 열이 없고 값이 부딪히지 않는다.
# 아래의 MAPPING, RULES, DIRECTION 은 정의 파일을 손으로 옮긴 것이다. 정의 파일을 고치면 여기도 고친다.
import csv, io, os, sys, datetime
from decimal import Decimal

PACK = "retirement-pension"
OUT = sys.argv[1]
D = datetime.date.fromisoformat

# ── 유형과 관계 (온톨로지와 같아야 한다) ────────────────────────────
T_REG, T_REQ, T_INS, T_PRD, T_OPT = "운영규약", "운영요건", "금융기관", "운용상품", "기본운용방법"
T_ACC, T_PTC, T_PLN, T_EMP, T_GRP, T_MGR = "연금계좌", "연금가입자", "연금제도", "가입사업장", "기업집단", "기업담당자"
# 영향 방향: 'ST' 출발→도착, 'TS' 도착→출발, None 퍼지지 않음
DIRECTION = {"규약부과": "ST", "요건적용": "ST", "상품제공": "TS", "방법구성": "TS", "상품보유": "TS", "방법지정": "TS",
             "계좌소유": "ST", "제도가입": "TS", "사업장재직": "ST", "제도설정": "ST", "사업장담당": "ST", "집단지배": None}
MAX_DEPTH = 5

def eid(t, k): return "|".join([PACK, t, k])
def num(v): return f"{v:.2f}".rstrip("0").rstrip(".")   # 다듬은 표기: 0.7, 0.95, 1

# ── 지어낸 자료 ────────────────────────────────────────────────
REGS = [(f"RG-{i:02d}", f"사내 연금 운영 규약 제{i}조", "2024-01-01" if i % 2 else "2025-01-01") for i in range(1, 15)]
# 요건: 번호, 이름, 구분, 위험자산한도, 최저등급점수, 최저적립비율, 근거 규약
KINDS = ["운용", "상품", "적립", "지급", "안내"]
REQS = [["RQ-01", "위험자산 편입 한도", "운용", "0.8", "", "", "RG-01"],
        ["RQ-02", "원리금보장 상품 제공 기관의 등급", "상품", "", "10", "", "RG-02"],
        ["RQ-03", "최소 적립 수준", "적립", "", "", "0.95", "RG-03"]]
for i in range(4, 22):
    REQS.append([f"RQ-{i:02d}", f"운영 요건 {i}", KINDS[i % 5], "", "", "", f"RG-{(i - 1) % 14 + 1:02d}"])
# 금융기관: 번호, 이름, 구분, 신용등급, 등급점수. 자산운용은 등급이 없다.
# 등급점수는 등급의 순서를 수로 바꾼 값이다. 클수록 높은 등급이고 BBB- 가 10 이다.
SCORE = {g: str(19 - i) for i, g in enumerate(["AAA", "AA+", "AA", "AA-", "A+", "A", "A-", "BBB+", "BBB", "BBB-", "BB+"])}
def inst(no, name, kind, grade): return [no, name, kind, grade, SCORE.get(grade, "")]
INSTS = [inst("I-01", "은행 가", "은행", "AA"), inst("I-02", "은행 나", "은행", "AA-"),
         inst("I-03", "저축은행 다", "저축은행", "BBB"), inst("I-04", "저축은행 라", "저축은행", "BBB+"),
         inst("I-05", "저축은행 마", "저축은행", "A-"), inst("I-06", "보험 바", "보험", "AA-"),
         inst("I-07", "자산운용 사", "자산운용", ""), inst("I-08", "자산운용 아", "자산운용", ""),
         inst("I-09", "자산운용 자", "자산운용", ""), inst("I-10", "여신전문 차", "여신전문", "A")]
# 상품: 번호, 이름, 구분, 위험등급, 제공 기관
PRODS = []
def prod(name, kind, grade, inst): PRODS.append([f"P-{len(PRODS) + 1:02d}", name, kind, str(grade), inst])
for inst, tag in [("I-01", "가"), ("I-02", "나"), ("I-03", "다"), ("I-04", "라"), ("I-05", "마")]:
    prod(f"정기예금 {tag}-1", "예금", 6, inst); prod(f"정기예금 {tag}-2", "예금", 6, inst)
prod("이율보증 바-1", "이율보증", 6, "I-06"); prod("이율보증 바-2", "이율보증", 6, "I-06")
for inst, tag, n in [("I-07", "사", 4), ("I-08", "아", 3), ("I-09", "자", 2)]:
    for j in range(n): prod(f"목표시점 펀드 {tag}-{2030 + 5 * j}", "목표시점펀드", 3, inst)
prod("주식형 펀드 아-1", "주식형", 1, "I-08"); prod("주식형 펀드 자-1", "주식형", 1, "I-09")
prod("채권혼합 펀드 아-1", "채권혼합", 4, "I-08"); prod("채권혼합 펀드 아-2", "채권혼합", 4, "I-08")
prod("채권형 펀드 자-1", "채권형", 5, "I-09")
prod("회사채 차-1", "회사채", 4, "I-10"); prod("회사채 차-2", "회사채", 4, "I-10")
PIDS = [p[0] for p in PRODS]
OPTS = [("D-01", "안정형", "안정"), ("D-02", "안정추구형", "안정"), ("D-03", "중립형", "중립"),
        ("D-04", "적극투자형 가", "적극"), ("D-05", "적극투자형 나", "적극")]
OPT_PROD = [("D-01", "P-05"), ("D-01", "P-01"), ("D-02", "P-03"), ("D-02", "P-26"), ("D-03", "P-24"),
            ("D-04", "P-13"), ("D-04", "P-22"), ("D-05", "P-17")]
GROUPS = [("G-1", "집단 가"), ("G-2", "집단 나"), ("G-3", "집단 다")]
MGRS = [(f"M-{i}", f"담당자 {i}", f"기업연금 {1 + i % 2}팀") for i in range(1, 6)]
INDUSTRY = ["제조", "물류", "건설", "유통", "소프트웨어", "제약", "전자", "기계"]
# 사업장: 번호, 이름, 업종, 담당자, 소속 집단 (앞의 아홉 곳만 집단에 든다)
EMPS = [(f"E-{i:02d}", f"사업장 {i:02d}", INDUSTRY[i % 8], f"M-{i % 5 + 1}", f"G-{(i - 1) // 3 + 1}" if i <= 9 else "")
        for i in range(1, 17)]
# 제도: 확정급여 여섯(E-01 ~ E-06), 확정기여 아홉(E-07 ~ E-15), 개인형 하나. E-16 에는 제도가 없다.
FUNDING_1 = ["1.02", "0.97", "0.83", "0.86", "1.05", "0.99"]
FUNDING_2 = ["1.03", "0.91", "0.78", "0.88", "1.05", "0.95"]
def plans(funding):
    rows = [(f"L-{i:02d}", "확정급여", funding[i - 1], "2026-06-30") for i in range(1, 7)]
    rows += [(f"L-{i:02d}", "확정기여", "", "") for i in range(7, 16)]
    return rows + [("L-16", "개인형", "", "")]
PLAN_EMP = [(f"L-{i:02d}", f"E-{i:02d}") for i in range(1, 16)]
# 가입자: 확정기여 사업장 아홉 곳에 89명, 확정급여 사업장 여섯 곳에 둘씩 12명, 사업장이 없는 개인 14명.
HEADS = [12, 11, 10, 10, 10, 9, 9, 9, 9]
PTCS, n = [], 0
for j, count in enumerate(HEADS):
    for _ in range(count):
        n += 1; PTCS.append([f"N-{n:03d}", f"가입자 {n:03d}", str(1962 + (n * 7) % 35), "", f"E-{7 + j:02d}"])
for i in range(1, 7):
    for _ in range(2):
        n += 1; PTCS.append([f"N-{n:03d}", f"가입자 {n:03d}", str(1962 + (n * 7) % 35), "", f"E-{i:02d}"])
for _ in range(14):
    n += 1; PTCS.append([f"N-{n:03d}", f"가입자 {n:03d}", str(1962 + (n * 7) % 35), "", ""])
EMP_OF = {p[0]: p[4] for p in PTCS}
# 계좌: 확정기여 89개 (확정기여 사업장의 가입자마다 하나), 개인형 40개 (개인 14명, 확정급여 사업장의 12명, 확정기여 가입자 14명).
ACCS = []
def acc(kind, owner, plan):
    k = len(ACCS) + 1
    option = "" if k % 4 == 0 else f"D-{k % 5 + 1:02d}"
    ACCS.append([f"A-{k:03d}", kind, str(1_000_000 * (3 + (k * 13) % 60)), num(((k * 37) % 90) / 100), owner, plan, option])
for p in PTCS[:89]: acc("확정기여", p[0], "L-" + p[4][2:])
for p in PTCS[101:] + PTCS[89:101] + PTCS[0:84:6]: acc("개인형", p[0], "L-16")
# 보유: 계좌마다 상품 셋 (겹치면 그만큼 적다)
HOLD = []
for k in range(1, len(ACCS) + 1):
    for pid in sorted({PIDS[(k * 5) % 28], PIDS[(k * 11 + 3) % 28], PIDS[(k * 17 + 7) % 28]}):
        HOLD.append((f"A-{k:03d}", pid))
# 요건이 적용되는 대상. RQ-01 (위험자산 한도)은 자산운용 사의 목표시점 펀드 넷에 걸린다.
REQ_PRD = [("RQ-01", p) for p in PIDS[12:16]] + [("RQ-02", p) for p in PIDS[:10]]
REQ_PRD += [(f"RQ-{i:02d}", PIDS[j]) for i in range(4, 13) for j in range(28) if (i * 3 + j) % 7 == 0]
# RQ-15 는 RQ-01 과 같은 규약(RG-01)이 부과한 요건이다. 확정급여 제도 하나에만 걸려 있다.
REQ_PLN = [("RQ-03", f"L-{i:02d}") for i in range(1, 7)] + [(f"RQ-{r}", f"L-{i:02d}") for r in (13, 14) for i in range(7, 16)]
REQ_PLN += [("RQ-15", "L-01")]
REQ_INS = [("RQ-02", f"I-{i:02d}") for i in range(1, 6)] + [("RQ-16", f"I-{i:02d}") for i in range(1, 11)]
REQ_OPT = [(f"RQ-{r}", o[0]) for r in (17, 18) for o in OPTS]

# ── 입력 자료의 판 (열 이름, 행) ─────────────────────────────────
SOURCES = {}  # 입력 자료 → [(파일 이름, 적용일, 열, 행)]
def edition(source, version, date, cols, rows):
    SOURCES.setdefault(source, []).append((f"{source}-v{version}.csv", date, cols, [list(map(str, r)) for r in rows]))

D0 = "2026-01-01"
REQ_COLS = ["요건번호", "요건명칭", "요건구분", "위험자산한도", "최저등급점수", "최저적립비율", "근거규약"]
INS_COLS = ["기관번호", "기관명칭", "기관구분", "신용등급", "등급점수"]
PLN_COLS = ["제도번호", "제도구분", "적립비율", "사업연도종료일"]
PTC_COLS = ["가입자번호", "가입자이름", "출생연도", "퇴직일", "재직사업장"]
edition("regulation", 1, D0, ["규약번호", "규약명칭", "규약시행일"], REGS)
edition("requirement", 1, D0, REQ_COLS, REQS)
edition("institution", 1, D0, INS_COLS, INSTS)
edition("product", 1, D0, ["상품번호", "상품명칭", "상품구분", "위험등급", "제공기관"], PRODS)
edition("default-option", 1, D0, ["방법번호", "방법명칭", "위험성향"], OPTS)
edition("default-option-product", 1, D0, ["방법번호", "상품번호"], OPT_PROD)
edition("group", 1, D0, ["집단번호", "집단명칭"], GROUPS)
edition("manager", 1, D0, ["담당자번호", "담당자이름", "담당팀"], MGRS)
edition("employer", 1, D0, ["사업장번호", "사업장명칭", "사업장업종", "담당자번호", "소속집단"], EMPS)
edition("plan", 1, D0, PLN_COLS, plans(FUNDING_1))
edition("plan-employer", 1, D0, ["제도번호", "사업장번호"], PLAN_EMP)
edition("participant", 1, D0, PTC_COLS, PTCS)
edition("account", 1, D0, ["계좌번호", "계좌구분", "적립금", "위험자산비중", "소유가입자", "가입제도", "지정방법"], ACCS)
edition("account-product", 1, D0, ["계좌번호", "상품번호"], HOLD)
edition("requirement-product", 1, D0, ["요건번호", "상품번호"], REQ_PRD)
edition("requirement-plan", 1, D0, ["요건번호", "제도번호"], REQ_PLN)
edition("requirement-institution", 1, D0, ["요건번호", "기관번호"], REQ_INS)
edition("requirement-default-option", 1, D0, ["요건번호", "방법번호"], REQ_OPT)

# 2026-04-01: 요건이 바뀐다. 위험자산 편입 한도가 0.8 에서 0.7 로 내려간다. RQ-05 는 이름만 바뀐다.
DA = "2026-04-01"
reqs2 = [list(r) for r in REQS]
reqs2[0][3] = "0.7"
reqs2[4][1] = "운영 요건 5 (문구 정비)"
edition("requirement", 2, DA, REQ_COLS, reqs2)
# 2026-06-01: 보유가 바뀐다. 계좌 셋이 상품 하나씩을 팔고, 계좌 둘이 상품 하나씩을 새로 산다.
DB = "2026-06-01"
SOLD = [("A-005", [h for h in HOLD if h[0] == "A-005"][0][1]), ("A-020", [h for h in HOLD if h[0] == "A-020"][0][1]),
        ("A-033", [h for h in HOLD if h[0] == "A-033"][-1][1])]
BOUGHT = [h for h in [("A-005", "P-06"), ("A-040", "P-13")] if h not in HOLD]
edition("account-product", 2, DB, ["계좌번호", "상품번호"], [h for h in HOLD if h not in SOLD] + BOUGHT)
# 2026-08-18: 금융기관이 바뀐다. 저축은행 다(I-03)의 등급이 BBB 에서 BB+ 로 내려간다 (점수 11 → 9, 기준 10 미만).
# 저축은행 라(I-04)는 BBB+ 에서 BBB- 로 내려간다 (12 → 10, 기준과 같아 미만이 아니다). 저축은행 마(I-05)는 오른다 (13 → 14).
# 여신전문 차(I-10)는 이름만 바뀐다.
DC = "2026-08-18"
insts2 = [list(r) for r in INSTS]
insts2[2][3:5] = ["BB+", SCORE["BB+"]]
insts2[3][3:5] = ["BBB-", SCORE["BBB-"]]
insts2[4][3:5] = ["A", SCORE["A"]]
insts2[9][1] = "여신전문 차 (상호 변경)"
edition("institution", 2, DC, INS_COLS, insts2)
# 2026-10-01: 가입자가 바뀐다. 퇴직 통보가 들어온다.
# - N-003, N-015, N-040: 퇴직일 2026-09-30. 재직이 끝난다 (적용일보다 앞선 퇴직).
# - N-052: 퇴직일 2026-10-01. 판의 적용일과 같은 날이다 (경계).
# - N-066: 퇴직일 2026-10-31. 예정이라 재직은 그대로다 (적용일보다 뒤).
# - N-077: 출생연도만 고친다.
DE = "2026-10-01"
ptcs2 = [list(p) for p in PTCS]
for p in ptcs2:
    if p[0] in ("N-003", "N-015", "N-040"): p[3], p[4] = "2026-09-30", ""
    if p[0] == "N-052": p[3], p[4] = "2026-10-01", ""
    if p[0] == "N-066": p[3] = "2026-10-31"
    if p[0] == "N-077": p[2] = str(int(p[2]) + 1)
edition("participant", 2, DE, PTC_COLS, ptcs2)
# 2026-10-06: 제도가 바뀐다. 확정급여 제도의 적립비율이 새 사업연도의 값이 된다. 0.95 미만은 L-02, L-03, L-04 다.
# L-06 은 꼭 0.95 다 (기준과 같아 미만이 아니다).
DF = "2026-10-06"
edition("plan", 2, DF, PLN_COLS, plans(FUNDING_2))
# 2026-11-01: 규약 RG-05 의 이름이 바뀐다. 이 규약이 부과한 요건 RQ-05 는 상품 넷(REG5_PRODS)에 걸려 있다.
# 같은 날 먼저: 그 상품 가운데 하나만 보유하던 계좌 하나(LEFT)가 그 상품을 판다. 이 보유는 2026-11-01 에 끝난다.
DG = "2026-11-01"
REG5_PRODS = [p for r, p in REQ_PRD if r == "RQ-05"]
hold2 = [h for h in HOLD if h not in SOLD] + BOUGHT
LEFT = next(h for h in hold2 if h[1] == REG5_PRODS[0] and not any(x[0] == h[0] and x[1] in REG5_PRODS[1:] for x in hold2))
edition("account-product", 3, DG, ["계좌번호", "상품번호"], [h for h in hold2 if h != LEFT])
regs2 = [list(r) for r in REGS]
regs2[4][1] = "사내 연금 운영 규약 제5조 (개정)"
edition("regulation", 2, DG, ["규약번호", "규약명칭", "규약시행일"], regs2)

# 시나리오: 투입하는 차례
ORDER = ["regulation", "requirement", "institution", "product", "default-option", "default-option-product", "group", "manager",
         "employer", "plan", "plan-employer", "participant", "account", "account-product", "requirement-product",
         "requirement-plan", "requirement-institution", "requirement-default-option"]
STEPS = [(s, 0) for s in ORDER] + [("requirement", 1), ("account-product", 1), ("institution", 1), ("participant", 1), ("plan", 1),
                                    ("account-product", 2), ("regulation", 1)]

# ── 매핑 (매핑 파일과 같아야 한다): 행 → 개체의 속성, 관계 ─────────────────
# 입력 자료 → (키 열, 주 개체 (유형, 열), 개체 [(유형, 열, {속성: 열})], 관계 [(관계 유형, (출발 유형, 열), (도착 유형, 열))])
def same(*names): return {n: n for n in names}
MAPPING = {
    "regulation": (["규약번호"], (T_REG, "규약번호"), [(T_REG, "규약번호", same("규약명칭", "규약시행일"))], []),
    "requirement": (["요건번호"], (T_REQ, "요건번호"),
                    [(T_REQ, "요건번호", same("요건명칭", "요건구분", "위험자산한도", "최저등급점수", "최저적립비율"))],
                    [("규약부과", (T_REG, "근거규약"), (T_REQ, "요건번호"))]),
    "institution": (["기관번호"], (T_INS, "기관번호"), [(T_INS, "기관번호", same("기관명칭", "기관구분", "신용등급", "등급점수"))], []),
    "product": (["상품번호"], (T_PRD, "상품번호"), [(T_PRD, "상품번호", same("상품명칭", "상품구분", "위험등급"))],
                [("상품제공", (T_PRD, "상품번호"), (T_INS, "제공기관"))]),
    "default-option": (["방법번호"], (T_OPT, "방법번호"), [(T_OPT, "방법번호", same("방법명칭", "위험성향"))], []),
    "default-option-product": (["방법번호", "상품번호"], (T_OPT, "방법번호"), [], [("방법구성", (T_OPT, "방법번호"), (T_PRD, "상품번호"))]),
    "group": (["집단번호"], (T_GRP, "집단번호"), [(T_GRP, "집단번호", same("집단명칭"))], []),
    "manager": (["담당자번호"], (T_MGR, "담당자번호"), [(T_MGR, "담당자번호", same("담당자이름", "담당팀"))], []),
    "employer": (["사업장번호"], (T_EMP, "사업장번호"), [(T_EMP, "사업장번호", same("사업장명칭", "사업장업종"))],
                 [("사업장담당", (T_EMP, "사업장번호"), (T_MGR, "담당자번호")), ("집단지배", (T_EMP, "사업장번호"), (T_GRP, "소속집단"))]),
    "plan": (["제도번호"], (T_PLN, "제도번호"), [(T_PLN, "제도번호", same("제도구분", "적립비율", "사업연도종료일"))], []),
    "plan-employer": (["제도번호", "사업장번호"], (T_PLN, "제도번호"), [], [("제도설정", (T_PLN, "제도번호"), (T_EMP, "사업장번호"))]),
    "participant": (["가입자번호"], (T_PTC, "가입자번호"), [(T_PTC, "가입자번호", same("가입자이름", "출생연도", "퇴직일"))],
                    [("사업장재직", (T_PTC, "가입자번호"), (T_EMP, "재직사업장"))]),
    "account": (["계좌번호"], (T_ACC, "계좌번호"), [(T_ACC, "계좌번호", same("계좌구분", "적립금", "위험자산비중"))],
                [("계좌소유", (T_ACC, "계좌번호"), (T_PTC, "소유가입자")), ("제도가입", (T_ACC, "계좌번호"), (T_PLN, "가입제도")),
                 ("방법지정", (T_ACC, "계좌번호"), (T_OPT, "지정방법"))]),
    "account-product": (["계좌번호", "상품번호"], (T_ACC, "계좌번호"), [], [("상품보유", (T_ACC, "계좌번호"), (T_PRD, "상품번호"))]),
    "requirement-product": (["요건번호", "상품번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_PRD, "상품번호"))]),
    "requirement-plan": (["요건번호", "제도번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_PLN, "제도번호"))]),
    "requirement-institution": (["요건번호", "기관번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_INS, "기관번호"))]),
    "requirement-default-option": (["요건번호", "방법번호"], (T_REQ, "요건번호"), [], [("요건적용", (T_REQ, "요건번호"), (T_OPT, "방법번호"))]),
}
# 규칙 (규칙 파일과 같아야 한다):
# 번호 → (우선순위, 변동의 종류, 걸리는 입력 자료, 바뀐 것(속성이나 관계 유형), 대상 쪽, 대상 유형, 조건들, 기한 일수)
# 조건: (보는 쪽, "type", 유형) / (보는 쪽, 속성, "=", 값) / (보는 쪽, 속성, "<", 수) /
#       (보는 쪽, 속성, ">attr", (다른 쪽, 다른 속성)) / (보는 쪽, 속성, "<=effective")
LOW_RATING = [("CHANGE", "type", T_INS), ("CHANGE", "등급점수", "<", "10")]
LOW_FUNDING = [("CHANGE", "type", T_PLN), ("CHANGE", "적립비율", "<", "0.95")]
OVER_LIMIT = [("CHANGE", "type", T_REQ), ("IMPACT", "위험자산비중", ">attr", ("CHANGE", "위험자산한도"))]
RULES = {
    "0001": (1, {"CHANGED"}, {"requirement"}, {"위험자산한도"}, "IMPACT", T_ACC, OVER_LIMIT, 0),
    "0002": (2, {"CHANGED"}, {"requirement"}, {"위험자산한도"}, "IMPACT", T_ACC, OVER_LIMIT, 14),
    "0003": (1, {"CHANGED"}, {"institution"}, {"등급점수"}, "IMPACT", T_PRD, LOW_RATING + [("IMPACT", "상품구분", "=", "예금")], 0),
    "0004": (2, {"CHANGED"}, {"institution"}, {"등급점수"}, "IMPACT", T_PTC, LOW_RATING, 14),
    "0005": (3, {"CHANGED"}, {"institution"}, {"등급점수"}, "IMPACT", T_EMP, LOW_RATING, 7),
    "0006": (1, {"ADDED", "CHANGED"}, {"participant"}, {"퇴직일"}, "CHANGE", T_PTC, [("CHANGE", "퇴직일", "<=effective")], 14),
    "0007": (1, {"CHANGED"}, {"plan"}, {"적립비율"}, "IMPACT", T_EMP, LOW_FUNDING, 7),
    "0008": (2, {"CHANGED"}, {"plan"}, {"적립비율"}, "IMPACT", T_MGR, LOW_FUNDING, 30),
}

# ── 문서의 규칙을 따로 구현한 계산 ────────────────────────────────
def key_of(source, row, cols):
    return "|".join(row[cols.index(c)] for c in MAPPING[source][0])

def row_facts(source, row, cols):
    """행이 만드는 것: 개체(참조 포함), 속성 값, 관계. 빈 칸은 값이 없는 것이고, 끝이 빈 칸인 관계는 만들지 않는다."""
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

REL, ATT = [], []   # 관계 [rt, s, t, from, to, source, 판 번호], 속성 [entity, prop, value, from, to, source]
IMPACTS, ACTIONS = {}, {}
EDITION_NO, EVENTS = {}, {}  # 단계 → 변동 [(항목의 키, 종류, 주 개체, 변동 대상, 적용일, 입력 자료, 판 번호, 바뀐 것)]
PREV = {}

def changed_names(old, new):
    """그 행에서 바뀐 속성과 관계 유형의 이름 (D2 AD-291). 추가는 넣은 것 전부 (old 가 없다), 삭제는 넣었던 것 전부 (new 가 없다)."""
    old_att, old_rel = (old[2], set(old[3])) if old else ({}, set())
    new_att, new_rel = (new[2], set(new[3])) if new else ({}, set())
    names = {k[1] for k in set(old_att) | set(new_att) if old_att.get(k) != new_att.get(k)}
    return names | {rel[0] for rel in old_rel ^ new_rel}

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
            events.append((k, kind, prim, targets, date, source, EDITION_NO[source], changed_names(old.get(k), new.get(k))))
    # 관계: 이 입력 자료가 넣은 것 가운데 새 판에 없는 것은 적용일로 끝내고, 새로 생긴 것은 적용일부터 시작한다.
    now_rels = {rel for f in new.values() for rel in f[3]}
    for r in REL:
        if r[5] == source and r[4] is None and (r[0], r[1], r[2]) not in now_rels: r[4] = date
    live = {(r[0], r[1], r[2]) for r in REL if r[5] == source and r[4] is None}
    for rel in sorted(now_rels - live): REL.append([rel[0], rel[1], rel[2], date, None, source, EDITION_NO[source]])
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
    """조건이 보는 값: 적용일의 값이고, 없으면 전날의 값이다 (D2 AD-101)."""
    for d in (day, prev):
        for a in ATT:
            if a[0] == entity and a[1] == prop and valid(a[3], a[4], d): return a[2]
    return None

def type_of(entity): return entity.split("|")[1]

def rel_id(r):
    """관계의 식별자: 출발, 도착, 유형, 출처(입력 자료와 판 번호), 시작일을 이은 글자 (D5 1절)."""
    return "|".join([r[1], r[2].split("|", 1)[1], r[0], r[5], str(r[6]), r[3]])

def bfs(starts, day):
    """그 날짜에 유효한 관계로 영향 방향대로 따라간다. 대상 → (깊이, 경로, 시작한 변동 대상). 한계 너머까지 본다.
    길이가 같은 경로가 여럿이면 관계 식별자를 변동 대상 쪽부터 견주어 앞선 것을 남긴다 (D3 7절, D2 AD-36)."""
    by_node = {}
    for r in REL:
        if not valid(r[3], r[4], day) or DIRECTION[r[0]] is None: continue
        frm, to = (r[1], r[2]) if DIRECTION[r[0]] == "ST" else (r[2], r[1])
        by_node.setdefault(frm, []).append((to, r))
    found, frontier, depth = {}, [(s, [], s) for s in sorted(starts)], 0
    seen = set(starts)
    while frontier:
        depth += 1
        nxt = {}
        for node, path, start in frontier:
            for other, r in by_node.get(node, []):
                if other in seen: continue
                cand = (path + [r], start)
                order = [rel_id(x) for x in cand[0]]
                if other not in nxt or order < nxt[other][0]: nxt[other] = (order, cand)
        for other, (_, (path, start)) in nxt.items():
            found[other] = (depth, path, start)
            seen.add(other)
        frontier = [(o, c[0], c[1]) for o, (_, c) in nxt.items()]
    return found

def impact(event):
    _, kind, prim, targets, date, source, edition_no, _ = event
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
    key, kind, prim, targets, date, source, edition_no, changed = event
    ordered, truncated, day, prev = impact(event)
    out = []
    for no, (priority, kinds, sources, changed_any, side, target_type, conds, due) in RULES.items():
        if kind not in kinds: continue
        # 걸리는 입력 자료에서 나온 변동이고, 적은 것 가운데 하나가 그 행에서 바뀌었어야 한다 (D2 AD-290, AD-291).
        if source not in sources: continue
        if changed_any is not None and not (changed_any & changed): continue
        candidates = [(o, v[2]) for o, v in ordered] if side == "IMPACT" else [(t, t) for t in sorted(targets, key=lambda t: (type_of(t), t))]
        for target, start in candidates:
            if type_of(target) != target_type: continue
            ok = True
            for cond in conds:
                who = start if cond[0] == "CHANGE" else target
                if cond[1] == "type":
                    ok &= type_of(who) == cond[2]
                    continue
                mine = attr(who, cond[1], day, prev)
                if cond[2] == "=": ok &= mine == cond[3]
                elif cond[2] == "<": ok &= mine is not None and Decimal(mine) < Decimal(cond[3])
                elif cond[2] == ">attr":
                    # 다른 대상의 속성과 견준다. 어느 한쪽에 값이 없으면 만족하지 않는다 (D2 AD-292).
                    other = attr(start if cond[3][0] == "CHANGE" else target, cond[3][1], day, prev)
                    ok &= mine is not None and other is not None and Decimal(mine) > Decimal(other)
                elif cond[2] == "<=effective":
                    # 판의 적용일과 견준다 (D2 AD-292). 날짜는 2026-09-30 의 꼴이라 글자로 견줘도 날짜 순이다.
                    ok &= mine is not None and mine <= date
                else: raise SystemExit(f"모르는 조건: {cond}")
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

def item_key(key): return ", ".join(key.split("|"))

STEP_REQ, STEP_HOLD, STEP_INS, STEP_PTC, STEP_PLN, STEP_HOLD2, STEP_REG = (len(ORDER) + i for i in range(1, 8))
y = []
y.append("""# 퇴직연금 Pack 의 검증 질문. 형식은 D4 3절이다.
#
# 상태: 정답 초안. 대표가 확정하기 전이다 (D4 4절: 정답은 대표가 정한다).
# 모든 이름은 지어낸 것이다. 실제 기관, 사업장, 사람, 규정과 관계가 없다.
#
# 정답을 만든 방법: 자료가 커서 (개체 362개) 손으로 적지 않았다. 예시 입력 파일에서 문서의 규칙
# (D3 6절·7절의 변동 대상과 영향 범위, D8 의 두 날짜와 규칙의 조건, D7 의 순서)을 엔진과 따로 구현한 계산으로 만들었다
# (tools/packs/retirement-pension-build.py). 제품이 낸 답을 옮겨 적은 것이 아니다. 질문마다 무엇 때문에 그 답인지를 주석으로 적었다.
#
# 개체는 유형의 이름과 입력의 키로 가리킨다. 목록의 순서는 제품이 내놓는 순서다 (D4 4절, D2 AD-282).
# 영향 범위와 조치는 판을 투입한 직후에 그때의 자료로 한 번 계산해 저장한 것이다 (D2 AD-119).
# 영향이 퍼지는 길: 운영규약 → 운영요건 → 운용상품·연금제도·금융기관·기본운용방법, 금융기관 → 운용상품 → 기본운용방법·연금계좌,
# 기본운용방법·연금제도 → 연금계좌 → 연금가입자 → 가입사업장 → 기업담당자, 연금제도 → 가입사업장 (최대 깊이 5).
# 한 대상에 길이가 같은 길이 여럿이면 관계 식별자가 앞선 길이 남는다 (D3 7절). 이 자료에는 그런 대상이 많다.
#
# 이 질문들이 묻지 않는 것: 관계 유형과 상대 개체가 같은 관계가 둘인 경우 (정답을 만든 계산이 구현하지 않았다).
""")
y.append("scenario:\n  id: S-01\n  title: 위험자산 한도가 내려가고 보유가 바뀌고 금융기관의 등급이 내려가고 퇴직 통보가 들어오고 적립비율이 바뀌고 규약의 이름이 바뀐다\n  steps:")
y.append("    # 2026-01-01: 처음 자료가 들어온다. 모두 첫 판이라 변동은 없다.")
for n, (source, idx) in enumerate(STEPS, start=1):
    name, date, _, _ = SOURCES[source][idx]
    note = {STEP_REQ: "    # 2026-04-01: 위험자산 편입 한도(RQ-01)가 0.8 에서 0.7 로 내려간다. RQ-05 는 이름만 바뀐다.",
            STEP_HOLD: "    # 2026-06-01: 계좌 셋이 상품 하나씩을 팔고, 계좌 둘이 상품 하나씩을 새로 산다.",
            STEP_INS: "    # 2026-08-18: 저축은행 다(I-03)의 등급이 BBB 에서 BB+ 로 내려간다 (점수 11 → 9). 저축은행 라(I-04)는 BBB- 가 된다 (점수 10). 저축은행 마(I-05)는 오른다. 여신전문 차(I-10)는 이름만 바뀐다.",
            STEP_PTC: "    # 2026-10-01: 퇴직 통보. N-003, N-015, N-040 은 퇴직일 2026-09-30, N-052 는 2026-10-01, N-066 은 예정(2026-10-31). N-077 은 출생연도만 고친다.",
            STEP_PLN: "    # 2026-10-06: 확정급여 제도의 적립비율이 바뀐다. 0.95 미만은 L-02 (0.91), L-03 (0.78), L-04 (0.88). L-06 은 꼭 0.95 다.",
            STEP_HOLD2: "    # 2026-11-01: 계좌 %s 가 상품 %s 를 판다. 그 뒤에 같은 날의 규약이 들어온다 (차례가 정답에 닿는다)." % LEFT,
            STEP_REG: "    # 2026-11-01: 규약 RG-05 의 이름이 바뀐다."}.get(n)
    if note: y.append(note)
    y.append("    - { step: %d, inputSource: %s, file: data/%s, effectiveDate: %s }" % (n, source, name, date))
y.append("\n# 시점: 과거 2026-05-31 / 2026-06-01 (보유가 바뀌는 경계), 2026-09-30 / 2026-10-01 (재직이 끝나는 경계), 현재 2026-12-01 (마지막 판 뒤).\n\nquestions:\n")

def q_relations(qid, title, t, k, day, why):
    y.append("  - id: %s\n    kind: relations-as-of\n    title: %s\n    asOf: %s\n    entity: { type: %s, key: %s }" % (qid, title, day, t, k))
    y.append("    # " + why)
    rows = relations_as_of(eid(t, k), day)
    if not rows:
        y.append("    expect:\n      relations: []\n")
        return rows
    y.append("    expect:\n      relations:")
    for rt, other, s, tgt, f, to in rows:
        y.append("        - { type: %s, source: %s, target: %s, validFrom: %s, validTo: %s }" % (rt, ref(s), ref(tgt), f, to or "null"))
    y.append("")
    return rows

def q_changes(qid, title, step, why):
    y.append("  - id: %s\n    kind: changes\n    title: %s\n    step: %d\n    # %s\n    expect:\n      changes:" % (qid, title, step, why))
    for e in EVENTS[step]:
        y.append("        - { itemKey: [%s], kind: %s }" % (item_key(e[0]), e[1].lower()))
    y.append("")

def q_impact(qid, title, step, key, why):
    find(step, key)
    ordered, truncated, day, prev = IMPACTS[(step, key)]
    y.append("  - id: %s\n    kind: impact\n    title: %s\n    change: { step: %d, itemKey: [%s] }\n    # %s\n    expect:\n      targets:" % (qid, title, step, item_key(key), why))
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
    find(step, key)
    acts = ACTIONS[(step, key)]
    y.append("  - id: %s\n    kind: actions\n    title: %s\n    change: { step: %d, itemKey: [%s] }\n    # %s\n    expect:\n      actions:%s" % (qid, title, step, item_key(key), why, "" if acts else " []"))
    for due, priority, no, target, prim, source, edition_no in acts:
        y.append('        - { rule: "%s", priority: %d, target: %s, dueDate: %s, basis: { entity: %s, inputSource: %s, edition: %d } }'
                 % (no, priority, ref(target), due, ref(prim), source, edition_no))
    y.append("")
    return acts

SOLD_KEY = "|".join(SOLD[0])
y.append("  # ── 시점 조회 ──────────────────────────────────────────────\n")
q_relations("Q-01", "가입자 N-003 이 퇴직하기 전날의 관계 (종료일 전날)", T_PTC, "N-003", "2026-09-30",
            "재직은 2026-10-01 에 끝난다. 전날에는 사업장 E-07 의 재직이 아직 유효하다. 이 가입자의 계좌도 든다. 순서는 관계 유형, 상대 개체.")
q_relations("Q-02", "가입자 N-003 의 재직이 끝난 날의 관계 (종료일 당일)", T_PTC, "N-003", "2026-10-01",
            "재직은 이날 끝나 들지 않는다. 계좌를 가진 관계는 그대로다.")
q_relations("Q-03", "계좌 A-005 가 상품을 팔기 전날의 관계", T_ACC, "A-005", "2026-05-31",
            "보유는 2026-06-01 에 바뀐다. 전날에는 처음의 보유 그대로다. 가입자, 제도, 지정한 기본운용방법도 든다.")
q_relations("Q-04", "계좌 A-005 가 상품을 판 날의 관계 (종료일 당일, 새 시작일 당일)", T_ACC, "A-005", "2026-06-01",
            "판 상품의 보유는 이날 끝나 들지 않고, 새로 산 상품의 보유가 이날 시작해 든다.")
q_relations("Q-05", "현재, 저축은행 다(I-03)의 관계", T_INS, "I-03", "2026-12-01",
            "이 기관이 제공하는 상품 둘과 이 기관에 걸린 요건 둘이다. 등급이 내려간 것은 속성 값이 바뀐 것이라 관계는 그대로다.")
y.append("  # ── 변동 ───────────────────────────────────────────────────\n")
q_changes("Q-06", "요건의 둘째 판에서 나온 변동", STEP_REQ, "RQ-01 은 한도가, RQ-05 는 이름이 바뀌었다. 나머지 열아홉 행은 그대로다. 순서는 항목의 키.")
q_changes("Q-07", "보유의 둘째 판에서 나온 변동 (추가와 삭제)", STEP_HOLD, "항목의 키는 계좌와 상품 두 열이다. 판 것은 삭제, 새로 산 것은 추가다.")
q_changes("Q-08", "금융기관의 둘째 판에서 나온 변동", STEP_INS, "I-03, I-04, I-05 는 등급과 점수가, I-10 은 이름이 바뀌었다.")
q_changes("Q-09", "가입자의 둘째 판에서 나온 변동", STEP_PTC, "퇴직일이 들어온 다섯 명과 출생연도를 고친 한 명이다. 모두 변경이다 (행이 없어진 것이 아니다).")
q_changes("Q-10", "제도의 둘째 판에서 나온 변동", STEP_PLN, "확정급여 제도 여섯 가운데 적립비율이 달라진 다섯이다. L-05 는 값이 같아 변동이 아니다.")
y.append("  # ── 영향 범위 ──────────────────────────────────────────────\n")
i11, t11 = q_impact("Q-11", "위험자산 한도가 내려간 것의 영향 범위", STEP_REQ, "RQ-01",
                    "변동 대상은 요건 RQ-01 과 그 행이 가리키는 규약 RG-01 이다. RQ-01 이 걸린 목표시점 펀드 넷이 깊이 1, 그 펀드를 보유한 계좌와 펀드로 구성된 기본운용방법이 깊이 2, 그 방법을 지정한 계좌와 가입자가 그 뒤다. 깊이 4 와 5 에는 가입자가 재직하는 사업장과 그 담당자가 있다. 규약 RG-01 에서는 같은 규약의 다른 요건 RQ-15 와 그것이 걸린 제도, 그 사업장과 담당자 쪽으로 퍼진다 (네 대상의 근거 경로는 RG-01 에서 시작한다). 최대 깊이 5 안에서 모두 닿는다.")
i12, t12 = q_impact("Q-12", "저축은행 다의 등급이 내려간 것의 영향 범위", STEP_INS, "I-03",
                    "변동 대상은 기관 I-03 하나다. 깊이 1 은 이 기관의 예금 둘, 깊이 2 는 그 예금을 보유한 계좌와 예금으로 구성된 기본운용방법(안정형), 깊이 3 은 그 방법을 지정한 계좌와 가입자, 깊이 4 와 5 는 가입자가 재직하는 사업장과 그 담당자다. 2026-06-01 에 이 기관의 예금을 새로 산 계좌 A-005 도 든다. 한 계좌에 길이가 같은 길이 여럿이면 관계 식별자가 앞선 길이 남는다.")
i13, t13 = q_impact("Q-13", "가입자 N-003 이 퇴직한 것의 영향 범위 (참조로만 나온 사업장도 변동 대상이다)", STEP_PTC, "N-003",
                    "변동 대상은 가입자 N-003 과, 앞 판의 행이 가리킨 사업장 E-07 이다. 사업장의 담당자가 깊이 1 이다. 가입자에서 계좌 쪽으로는 퍼지지 않는다 (계좌소유는 계좌에서 가입자로만).")
i14, t14 = q_impact("Q-14", "확정급여 제도 L-03 의 적립비율이 내려간 것의 영향 범위", STEP_PLN, "L-03",
                    "변동 대상은 제도 L-03 하나다 (제도와 사업장을 잇는 관계는 다른 입력 자료가 넣는다). 사업장 E-03 이 깊이 1, 그 담당자가 깊이 2 다. 확정급여 제도에는 계좌가 없다.")
i15, t15 = q_impact("Q-15", "계좌 A-005 가 상품을 판 것의 영향 범위 (삭제된 행)", STEP_HOLD, SOLD_KEY,
                    "변동 대상은 계좌 A-005 와 판 상품이다. 계좌의 가입자와 사업장과 담당자, 그리고 그 상품으로 구성된 기본운용방법과, 그 상품을 아직 보유하거나 그 방법을 지정한 다른 계좌들과 그 가입자들, 가입자가 재직하는 사업장과 담당자가 든다. 끝난 보유는 적용일 전날에만 유효하지만 두 끝이 모두 변동 대상이라 길에 나오지 않는다.")
i27, t27 = q_impact("Q-27", "규약 RG-05 의 이름이 바뀐 것의 영향 범위 (한계에서 멈춘다. 적용일 전날의 관계로만 닿는 대상이 있다)", STEP_REG, "RG-05",
                    "변동 대상은 규약 RG-05 하나다. 이 규약이 부과한 요건이 깊이 1, 요건이 걸린 상품이 깊이 2, 그 상품을 보유한 계좌가 깊이 3, 가입자가 깊이 4, 사업장이 깊이 5 다. 담당자는 깊이 6 이라 들지 않고, 그래서 한계에서 멈췄다는 표시가 켜진다. 계좌 %s 가 상품 %s 를 보유한 관계는 같은 날(2026-11-01) 먼저 들어온 판에서 끝났다: 적용일에는 유효하지 않고 전날(2026-10-31)에는 유효하다. 그 계좌는 전날의 관계로만 닿고 근거 경로의 날짜가 2026-10-31 이다." % LEFT)
y.append("  # ── 조치 ───────────────────────────────────────────────────\n  # 순서는 제품이 내놓는 순서다: 기한이 이른 것부터, 같으면 우선순위, 규칙 번호, 대상 식별자 (D2 AD-243, AD-282).\n  # 근거는 변동이 생긴 행의 주 개체와, 그 변동이 나온 입력 자료의 판이다 (D2 AD-120).\n")
a16 = q_actions("Q-16", "위험자산 한도가 내려가 생긴 조치 (계좌의 비중을 요건의 한도와 견준다)", STEP_REQ, "RQ-01",
                "규칙 0001 (매수 제한, 당일)과 0002 (가입자 통지, 14일)는 요건의 입력 자료에서 한도가 바뀐 변동에 걸리고, 영향 대상인 계좌의 위험자산 비중이 변동 대상인 요건의 한도(새 값 0.7)보다 클 때만 건다. 비중이 꼭 0.7 인 계좌 A-070 은 넘지 않아 빠진다 (영향 범위에는 있다). 규약 RG-01 에서 시작한 길로 닿은 대상은 변동 대상이 요건이 아니라 빠진다.")
a17 = q_actions("Q-17", "요건 RQ-05 의 이름만 바뀌어 생긴 조치 (없다)", STEP_REQ, "RQ-05",
                "요건의 입력 자료에서 나온 변경이지만 그 행에서 바뀐 것은 이름뿐이다. 규칙 0001 과 0002 는 한도가 바뀐 변동에만 걸린다. 조치가 없다.")
a18 = q_actions("Q-18", "저축은행 다의 등급이 내려가 생긴 조치", STEP_INS, "I-03",
                "등급점수가 9 로 기준 10 미만이 되었다. 규칙 0003 (그 기관의 예금에 신규 편입 중단, 당일), 0005 (영향받은 사업장에 안내, 7일), 0004 (영향받은 가입자에 안내, 14일).")
a19 = q_actions("Q-19", "저축은행 마의 등급이 올라 생긴 조치 (없다)", STEP_INS, "I-05",
                "등급점수가 바뀌었으므로 규칙 0003 ~ 0005 를 평가하지만, 새 점수 14 는 기준 10 미만이 아니다. 조치가 없다.")
a20 = q_actions("Q-20", "가입자 N-003 의 퇴직 통보로 생긴 조치 (퇴직일이 판의 적용일보다 앞선다)", STEP_PTC, "N-003",
                "규칙 0006 (지급 일정 수립, 14일)은 가입자의 입력 자료에서 퇴직일이 바뀐 변동에 걸리고, 퇴직일이 판의 적용일(2026-10-01) 이하일 때 건다. 퇴직일 2026-09-30 은 앞선다. 변동 대상인 가입자 자신에게 건다.")
a21 = q_actions("Q-21", "가입자 N-052 의 퇴직 통보로 생긴 조치 (퇴직일이 판의 적용일과 같다)", STEP_PTC, "N-052",
                "퇴직일 2026-10-01 은 판의 적용일과 같은 날이다. 작거나 같다는 비교라 든다.")
a22 = q_actions("Q-22", "가입자 N-066 의 퇴직 예정으로 생긴 조치 (없다)", STEP_PTC, "N-066",
                "퇴직일 2026-10-31 은 판의 적용일보다 뒤다. 아직 퇴직하지 않았으므로 규칙 0006 은 걸리지 않는다.")
a23 = q_actions("Q-23", "가입자 N-077 의 출생연도만 고쳐 생긴 조치 (없다)", STEP_PTC, "N-077",
                "그 행에서 바뀐 것은 출생연도뿐이다. 규칙 0006 은 퇴직일이 바뀐 변동에만 걸린다.")
a24 = q_actions("Q-24", "제도 L-03 의 적립비율이 내려가 생긴 조치", STEP_PLN, "L-03",
                "적립비율 0.78 은 기준 0.95 미만이다. 규칙 0007 (사업장에 통보, 7일)과 0008 (담당자가 부족분 해소 안내를 준비, 30일).")
a28 = q_actions("Q-28", "제도 L-06 의 적립비율이 기준과 같아져 생긴 조치 (없다)", STEP_PLN, "L-06",
                "새 값 0.95 는 기준 0.95 와 같다. 미만이 아니라 조치가 없다 (경계).")
a29 = q_actions("Q-29", "저축은행 라의 등급이 기준까지 내려가 생긴 조치 (없다)", STEP_INS, "I-04",
                "등급점수가 12 에서 10 이 되었다. 기준 10 미만이 아니라 조치가 없다 (경계).")
a30 = q_actions("Q-30", "규약 RG-05 의 이름이 바뀌어 생긴 조치 (없다)", STEP_REG, "RG-05",
                "규약의 입력 자료에서 나온 변동에 걸리는 규칙이 없다. 영향 범위는 넓지만 조치가 없다.")
a25 = q_actions("Q-25", "제도 L-01 의 적립비율이 올라 생긴 조치 (없다)", STEP_PLN, "L-01",
                "적립비율이 바뀌었지만 새 값 1.03 은 기준 0.95 미만이 아니다. 조치가 없다.")
a26 = q_actions("Q-26", "제도 L-04 의 적립비율이 올랐지만 여전히 기준 미만이라 생긴 조치", STEP_PLN, "L-04",
                "0.86 에서 0.88 로 올랐지만 기준 0.95 미만이다. 규칙은 새 값만 본다 (앞 판의 값과 견주지 못한다). 조치가 생긴다.")
open(os.path.join(OUT, "questions", "S-01.yaml"), "w", encoding="utf-8").write("\n".join(y) + "\n")

n_entities = len({e for f in PREV.values() for x in f.values() for e in x[1]})
print("개체", n_entities, "관계", len(REL), "단계", len(STEPS), "입력 파일", sum(len(v) for v in SOURCES.values()))
print("Q-27", len(i27), t27, sum(1 for o, v in i27 if v[3] != DG), "LEFT", LEFT, "| Q-28", len(a28), "| Q-29", len(a29), "| Q-30", len(a30))
print("Q-11", len(i11), t11, "| Q-12", len(i12), t12, "| Q-13", len(i13), t13, "| Q-14", len(i14), t14, "| Q-15", len(i15), t15)
print("Q-16", len(a16), "| Q-17", len(a17), "| Q-18", len(a18), "| Q-19", len(a19), "| Q-20", len(a20), "| Q-21", len(a21),
      "| Q-22", len(a22), "| Q-23", len(a23), "| Q-24", len(a24), "| Q-25", len(a25), "| Q-26", len(a26))
for s in (STEP_REQ, STEP_HOLD, STEP_INS, STEP_PTC, STEP_PLN, STEP_HOLD2, STEP_REG): print(s, [(e[0], e[1], sorted(e[7])) for e in EVENTS[s]])
