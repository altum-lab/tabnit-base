import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { E2E_OTHER_USER, E2E_USER, apiAs, expect, test } from "./fixtures";

// D-06 수용 기준: 조치 목록과 근거가 실제 API 에서 온다.
// D-07 수용 기준: 화면에서 확정하면 상태와 이력이 바뀐다.
//
// 자료는 서버의 시험용 실행이 넣는다 (server/api/src/test 의 E2eSeedData).
// - e2e-actions: 시험용 Pack 의 시나리오를 올려 조치까지 만들어 둔 Pack. 이 시험은 읽기만 한다.
// - e2e-changes: 확정과 보류의 시험이 쓴다. 시험마다 새 판을 올려 대기인 조치를 새로 만든다.
//   그래서 띄워 둔 서버를 다시 써도 시험이 서로의 조치를 건드리지 않는다.
//
// 화면은 서버가 준 값과 순서를 그대로 보여 줘야 한다. 다시 세거나 정렬하지 않는다.

const READ_PACK = "e2e-actions";
const WRITE_PACK = "e2e-changes";

type Action = {
  id: string;
  changeEventId: string;
  ruleNo: string;
  ruleLabel: string;
  actionText: string;
  priority: number;
  dueDate: string;
  status: string;
  appliesTo: string;
  target: { entityId: string; entityType: string; entityTypeLabel: string; entityKey: string; displayName: string | null };
  basis: {
    entity: { entityId: string; entityTypeLabel: string; entityKey: string; displayName: string | null };
    inputSourceId: string;
    inputSourceLabel: string;
    editionNo: number;
    effectiveDate: string;
  };
};

async function listActions(request: APIRequestContext, params: Record<string, string>) {
  // 한 번에 받을 수 있는 가장 큰 크기로 받는다. 시험 자료의 조치는 그보다 적다.
  const response = await request.get("/api/v1/actions", { params: { limit: "200", ...params } });
  expect(response.ok()).toBe(true);
  return (await response.json()) as { total: number; actions: Action[] };
}

function nextDay(date: string) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

// 새 판을 올려 대기인 조치 하나를 새로 만든다. 지침 G-01 의 점검 주기를 고치면 그 지침 자신에 거는 조치가 나온다.
async function freshAction(request: APIRequestContext): Promise<Action> {
  const packs = (await (await request.get("/api/v1/packs")).json()) as {
    packs: { packId: string; inputSources: { inputSourceId: string; latestEdition: { effectiveDate: string } | null }[] }[];
  };
  const source = packs.packs.find((pack) => pack.packId === WRITE_PACK)?.inputSources.find((s) => s.inputSourceId === "guideline");
  const latest = source?.latestEdition?.effectiveDate;
  const first = latest ? nextDay(latest) : "2026-01-01";
  const run = Date.now();
  const upload = async (effectiveDate: string, cycle: number) => {
    const response = await request.post("/api/v1/editions", {
      multipart: {
        packId: WRITE_PACK,
        inputSourceId: "guideline",
        effectiveDate,
        file: {
          name: "지침.csv",
          mimeType: "text/csv",
          buffer: Buffer.from(`지침번호,제목,점검주기일\nG-01,조치 시험 ${run},${cycle}\n`, "utf-8"),
        },
      },
    });
    expect(response.ok()).toBe(true);
    return (await response.json()) as { changes: { changeEventId: string; itemKey: string }[] };
  };
  await upload(first, 30);
  const second = await upload(nextDay(first), 14);
  const change = second.changes.find((candidate) => candidate.itemKey === "G-01");
  expect(change).toBeDefined();
  const actions = await listActions(request, { packId: WRITE_PACK, changeEventId: change?.changeEventId ?? "" });
  // 조치가 하나도 없으면 이 시험은 뜻이 없다.
  const fresh = actions.actions[0];
  if (!fresh) {
    throw new Error("새 판을 올렸는데 조치가 만들어지지 않았습니다. 시험용 Pack 의 규칙을 확인하세요.");
  }
  expect(fresh.status).toBe("PENDING");
  return fresh;
}

// 목록이 그려질 때까지 기다린 뒤에 읽는다. 기다리지 않으면 응답이 오기 전의 빈 화면을 읽는다.
async function shownActionIds(page: Page) {
  const table = page.getByRole("table", { name: "조치 목록" });
  await expect(table).toBeVisible();
  return table.locator("tbody tr[data-action-id]").evaluateAll((rows) => rows.map((r) => r.getAttribute("data-action-id")));
}

function row(page: Page, actionId: string): Locator {
  return page.locator(`tr[data-action-id="${cssEscape(actionId)}"]`);
}

function detail(page: Page, actionId: string): Locator {
  return page.locator(`[data-detail-of="${cssEscape(actionId)}"]`);
}

// 조치의 id 에는 세로 막대와 역슬래시가 들어 있다. 속성 값으로 찾을 때 역슬래시와 따옴표를 가린다.
function cssEscape(value: string) {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

async function open(page: Page, actionId: string) {
  await row(page, actionId).getByRole("button", { name: "근거와 이력 보기" }).click();
  await expect(detail(page, actionId)).toBeVisible();
  // 이력이 다 올 때까지 기다린다. 기다리지 않고 다음 일을 하면, 늦게 온 이력의 답이 그 뒤의 상태를 담아 와
  // 화면이 시험의 기대보다 먼저 바뀐다 (예: 다른 곳에서 확정한 뒤에 온 답이 확정 단추를 없앤다).
  await expect(detail(page, actionId).getByRole("region", { name: "이력" })).not.toContainText("불러오는 중");
}

test("Pack 을 고르면 대기인 조치를 서버가 준 순서로 근거 조항과 그 판과 함께 보여 준다", async ({ page, request }) => {
  const expected = await listActions(request, { packId: READ_PACK, status: "PENDING" });
  expect(expected.total).toBeGreaterThan(1);

  await page.goto("/actions");
  await page.getByLabel("Pack").selectOption(READ_PACK);

  await expect(page.getByRole("table", { name: "조치 목록" })).toBeVisible();
  // 고른 Pack 과 상태는 주소에 남는다.
  await expect(page).toHaveURL(new RegExp(`packId=${READ_PACK}`));
  await expect(page.getByLabel("상태")).toHaveValue("PENDING");
  // 수는 서버가 준 값이고 순서는 서버가 준 그대로다 (기한이 이른 것부터).
  await expect(page.getByTestId("action-total")).toHaveText(`${expected.total}건`);
  expect(await shownActionIds(page)).toEqual(expected.actions.map((action) => action.id));
  // 한 줄에 해야 할 일, 대상, 기한, 우선순위, 상태, 근거 조항과 그 판이 있다.
  const first = expected.actions[0];
  if (!first) {
    throw new Error("읽을 조치가 없습니다.");
  }
  const line = row(page, first.id);
  await expect(line).toContainText(first.actionText);
  await expect(line).toContainText(first.ruleNo);
  await expect(line).toContainText(first.target.entityId);
  await expect(line).toContainText(first.dueDate);
  await expect(line).toContainText("대기");
  await expect(line).toContainText(first.basis.entity.entityId);
  // 근거 조항의 판의 입력 자료는 이름표로 보인다 (B-15). 시험용 Pack 의 매핑에는 식별자와 다른 이름표가 적혀 있다.
  expect(first.basis.inputSourceLabel).not.toBe(first.basis.inputSourceId);
  await expect(line).toContainText(`${first.basis.inputSourceLabel} ${first.basis.editionNo}판`);
  await expect(line).toContainText(first.basis.effectiveDate);
});

test("조치를 펼치면 근거 경로가 서버가 준 단계 그대로 나오고 이력이 없으면 없다고 말한다", async ({ page, request }) => {
  const all = await listActions(request, { packId: READ_PACK });
  const reached = all.actions.find((action) => action.appliesTo === "IMPACT_TARGET");
  const own = all.actions.find((action) => action.appliesTo === "CHANGE_TARGET");
  expect(reached).toBeDefined();
  expect(own).toBeDefined();
  if (!reached || !own) {
    return;
  }
  const scope = (await (await request.get("/api/v1/impact-scopes", { params: { changeEventId: reached.changeEventId, limit: "200" } })).json()) as {
    targets: { entityId: string; path: { startEntityId: string; asOf: string; steps: { relationId: string; relationType: string }[] } }[];
  };
  const target = scope.targets.find((candidate) => candidate.entityId === reached.target.entityId);
  const firstStep = target?.path.steps[0];
  if (!target || !firstStep) {
    throw new Error("영향 대상에 거는 조치의 대상이 그 변동의 영향 범위에 없습니다.");
  }

  await page.goto(`/actions?packId=${READ_PACK}&status=ALL`);
  await open(page, reached.id);
  // 경로가 어느 날짜의 관계로 닿았는지를 말로 적는다: 판의 적용일이면 변동 후, 그 전날이면 변동 전.
  const dates = (await (
    await request.get("/api/v1/impact-scopes", { params: { changeEventId: reached.changeEventId, limit: "1" } })
  ).json()) as { asOfPrevious: string; asOfEffective: string };
  const basis = target.path.asOf === dates.asOfEffective ? "변동 후의 관계" : "변동 전의 관계";
  expect([dates.asOfEffective, dates.asOfPrevious]).toContain(target.path.asOf);
  await expect(detail(page, reached.id).getByTestId("path-basis")).toHaveText(basis);

  // 근거 경로: 시작한 변동 대상, 닿은 날짜, 따라간 관계가 서버가 준 차례대로.
  const path = detail(page, reached.id).getByRole("region", { name: "근거 경로" });
  await expect(path).toContainText(target.path.startEntityId);
  await expect(path).toContainText(target.path.asOf);
  const steps = await path.locator("ol > li").evaluateAll((items) => items.map((item) => item.getAttribute("data-relation-id")));
  expect(steps).toEqual(target.path.steps.map((step) => step.relationId));
  await expect(path.locator("ol > li").first()).toContainText(firstStep.relationType);
  // 펼쳐서 읽기만 한 것은 목록을 낡게 하지 않는다.
  await expect(page.getByTestId("list-outdated")).toHaveCount(0);
  // 아직 아무도 확정하거나 보류하지 않았다.
  await expect(detail(page, reached.id).getByRole("region", { name: "이력" })).toContainText("아직 확정하거나 보류한 적이 없습니다");

  // 대상이 바뀐 개체 자신인 조치는 근거 경로가 없다고 말한다.
  await open(page, own.id);
  await expect(detail(page, own.id).getByRole("region", { name: "근거 경로" })).toContainText("근거 경로가 없습니다");
});

test("보류하고 확정하면 상태와 이력이 바뀐다", async ({ page, request }) => {
  const action = await freshAction(request);
  // 변동 하나의 조치만 걸러서 연다.
  await page.goto(`/actions?packId=${WRITE_PACK}&status=ALL&changeEventId=${encodeURIComponent(action.changeEventId)}`);
  await open(page, action.id);
  const panel = detail(page, action.id);

  // 사유 없이 보류하면 서버가 이유를 알리고 아무것도 바뀌지 않는다.
  await panel.getByRole("button", { name: "보류" }).click();
  await expect(panel.getByRole("alert")).toContainText("사유");
  await expect(row(page, action.id)).toContainText("대기");

  await panel.getByLabel("사유").fill("담당 부서의 답을 기다린다");
  await panel.getByRole("button", { name: "보류" }).click();

  await expect(row(page, action.id).getByTestId("status")).toHaveText("보류");
  const history = panel.getByRole("table", { name: "이력" });
  await expect(history.locator("tbody tr")).toHaveCount(1);
  await expect(history.locator("tbody tr").first()).toContainText("대기 → 보류");
  await expect(history.locator("tbody tr").first()).toContainText("담당 부서의 답을 기다린다");
  // 누가 했는지는 서버가 채운 값이다 (로그인 전에는 임시 아이디).
  await expect(history.locator("tbody tr").first()).toContainText(E2E_USER.loginId);
  // 언제 했는지는 서버가 준 시각(협정 세계시)을 설치 설정의 시간대로 바꿔 보인다. 서버가 준 값은 그대로 남아 있다.
  const session = (await (await request.get("/api/v1/session")).json()) as { timeZone: string };
  expect(session.timeZone).toBe("Asia/Seoul");
  const decidedAt = history.locator("tbody tr").first().locator("time");
  const instant = (await decidedAt.getAttribute("datetime")) ?? "";
  expect(instant).toMatch(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/);
  // 한국 시간은 협정 세계시보다 아홉 시간 앞선다 (서머타임이 없다). 화면과 따로 셈한다.
  const expectedLocal = new Date(new Date(instant).getTime() + 9 * 60 * 60 * 1000).toISOString().slice(0, 19).replace("T", " ");
  await expect(decidedAt).toHaveText(expectedLocal);

  await panel.getByRole("button", { name: "확정" }).click();

  await expect(row(page, action.id).getByTestId("status")).toHaveText("확정");
  await expect(history.locator("tbody tr")).toHaveCount(2);
  await expect(history.locator("tbody tr").nth(1)).toContainText("보류 → 확정");
  // 확정된 조치는 더 바꿀 수 없다.
  await expect(panel.getByRole("button", { name: "확정" })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: "보류" })).toHaveCount(0);
  await expect(panel).toContainText("확정된 조치는 바꿀 수 없습니다");

  // 서버에 저장된 것이 같다.
  const stored = (await (await request.get("/api/v1/action", { params: { actionId: action.id } })).json()) as {
    action: { status: string };
    history: { toStatus: string }[];
  };
  expect(stored.action.status).toBe("CONFIRMED");
  expect(stored.history.map((entry) => entry.toStatus)).toEqual(["ON_HOLD", "CONFIRMED"]);
});

test("대기만 보는 목록에서 확정해도 방금 한 결과가 남고 목록을 다시 받으면 빠진다", async ({ page, request }) => {
  const action = await freshAction(request);
  // 처음 화면 그대로: 상태를 고르지 않으면 대기인 조치만 본다.
  await page.goto(`/actions?packId=${WRITE_PACK}&changeEventId=${encodeURIComponent(action.changeEventId)}`);
  await expect(page.getByLabel("상태")).toHaveValue("PENDING");
  await open(page, action.id);
  const panel = detail(page, action.id);

  await panel.getByRole("button", { name: "확정" }).click();

  // 줄과 펼친 곳이 그대로 남아 방금 한 결과가 보인다. 목록의 상태 칸과 펼친 곳이 같은 상태다.
  await expect(row(page, action.id).getByTestId("status")).toHaveText("확정");
  await expect(panel.getByRole("table", { name: "이력" }).locator("tbody tr")).toHaveCount(1);
  await expect(panel).toContainText("확정된 조치는 바꿀 수 없습니다");
  // 목록이 받았을 때의 것이라고 알린다.
  await expect(page.getByTestId("list-outdated")).toBeVisible();

  await page.getByRole("button", { name: "목록 다시 받기" }).click();

  // 다시 받으면 대기가 아닌 조치는 빠진다.
  await expect(page.getByTestId("list-outdated")).toHaveCount(0);
  await expect(row(page, action.id)).toHaveCount(0);
});

test("다른 사람이 먼저 확정한 조치를 확정하려 하면 알리고 지금의 상태로 고친다", async ({ page, request, baseURL }) => {
  const action = await freshAction(request);
  await page.goto(`/actions?packId=${WRITE_PACK}&status=ALL&changeEventId=${encodeURIComponent(action.changeEventId)}`);
  await open(page, action.id);
  const panel = detail(page, action.id);
  // 화면을 연 뒤에 다른 사람이 먼저 확정한다. 화면과 같은 사람이 다시 보낸 것은 거부가 아니라 성공이다 (D2 AD-325).
  const other = await apiAs(baseURL, E2E_OTHER_USER);
  const elsewhere = await other.post("/api/v1/action-decisions", { data: { actionId: action.id, decision: "CONFIRM", note: "먼저 확인했다" } });
  expect(elsewhere.ok()).toBe(true);
  await other.dispose();

  await panel.getByRole("button", { name: "확정" }).click();

  // 서버의 문구와 마지막 이력(누가 했는지)을 보여 주고, 화면의 상태와 이력을 지금의 것으로 고친다.
  await expect(panel.getByRole("alert")).toContainText("바꿀 수 없습니다");
  await expect(row(page, action.id).getByTestId("status")).toHaveText("확정");
  await expect(panel.getByRole("table", { name: "이력" }).locator("tbody tr")).toHaveCount(1);
  await expect(panel.getByRole("table", { name: "이력" })).toContainText("먼저 확인했다");
  await expect(panel.getByRole("button", { name: "확정" })).toHaveCount(0);
});

test("상태로 거르고 고른 것이 주소에 남아 새로 고쳐도 그대로다", async ({ page, request }) => {
  const confirmed = await listActions(request, { packId: READ_PACK, status: "CONFIRMED" });
  await page.goto(`/actions?packId=${READ_PACK}`);
  await expect(page.getByRole("table", { name: "조치 목록" })).toBeVisible();

  await page.getByLabel("상태").selectOption("CONFIRMED");

  await expect(page).toHaveURL(/status=CONFIRMED/);
  // 읽기만 하는 Pack 이라 확정된 조치가 없다. 없으면 없다고 말한다.
  expect(confirmed.total).toBe(0);
  await expect(page.getByText("해당하는 조치가 없습니다")).toBeVisible();
  await page.reload();
  await expect(page.getByLabel("Pack")).toHaveValue(READ_PACK);
  await expect(page.getByLabel("상태")).toHaveValue("CONFIRMED");
});

test("변동 감지 화면의 조치 수를 누르면 그 변동의 조치만 걸러서 연다", async ({ page, request }) => {
  const action = await freshAction(request);
  // 방금 올린 것과 같은 결과를 화면에서 다시 얻기 위해, 한 판을 더 화면으로 올린다.
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(WRITE_PACK);
  await page.getByLabel("입력 자료").selectOption("guideline");
  const latest = (await page.getByTestId("latest-effective-date").textContent()) ?? "";
  await page.getByLabel("적용일").fill(nextDay(latest));
  await page.getByLabel("파일").setInputFiles({
    name: "지침.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(`지침번호,제목,점검주기일\nG-01,조치로 가기 ${Date.now()},7\n`, "utf-8"),
  });
  await page.getByRole("button", { name: "올리기" }).click();
  const changed = page.getByRole("table", { name: "바뀐 항목" }).locator('tbody tr[data-item-key="G-01"]');
  await expect(changed).toContainText("처리됨");
  const changeEventId = (await changed.getAttribute("data-change-event-id")) ?? "";
  expect(changeEventId).not.toBe(action.changeEventId);

  await changed.getByRole("link", { name: /건$/ }).click();

  await expect(page).toHaveURL(/\/actions\?/);
  const expected = await listActions(request, { packId: WRITE_PACK, changeEventId });
  expect(await shownActionIds(page)).toEqual(expected.actions.map((candidate) => candidate.id));
  expect(expected.total).toBeGreaterThan(0);
  await expect(page.getByTestId("change-filter")).toContainText("변동 하나의 조치만");
});

// ── 나눠 주기 (D-13, D7 2절) ──────────────────────────────────────────

test("조치 목록에 전체 수와 지금 보는 자리가 나오고 주소의 건너뛸 수로 그 자리부터 열린다", async ({ page, request }) => {
  const expected = await listActions(request, { packId: READ_PACK, status: "PENDING" });
  expect(expected.total).toBeGreaterThan(2);

  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING`);
  await expect(page.getByTestId("pager-position")).toHaveText(`전체 ${expected.total}건 가운데 1~${expected.total}번째`);
  await expect(page.getByRole("button", { name: "이전" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "다음" })).toBeDisabled();

  // 둘째부터 연다. 수는 그대로이고 목록은 서버가 준 순서의 둘째부터다.
  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING&offset=1`);
  await expect(page.getByTestId("pager-position")).toHaveText(`전체 ${expected.total}건 가운데 2~${expected.total}번째`);
  await expect(page.getByTestId("action-total")).toHaveText(`${expected.total}건`);
  expect(await shownActionIds(page)).toEqual(expected.actions.slice(1).map((action) => action.id));

  // 이전으로 돌아가면 처음부터이고 주소에서 건너뛸 수가 빠진다.
  await page.getByRole("button", { name: "이전" }).click();
  await expect(page.getByTestId("pager-position")).toHaveText(`전체 ${expected.total}건 가운데 1~${expected.total}번째`);
  await expect(page).not.toHaveURL(/offset=/);
  expect(await shownActionIds(page)).toEqual(expected.actions.map((action) => action.id));
});

test("전체 수를 넘긴 자리와 틀린 건너뛸 수는 화면이 멈추지 않고 알린다", async ({ page, request }) => {
  const expected = await listActions(request, { packId: READ_PACK, status: "PENDING" });

  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING&offset=${expected.total + 10}`);
  await expect(page.getByText("이 자리에는 조치가 없습니다. 이전으로 돌아가세요.")).toBeVisible();
  await expect(page.getByTestId("action-total")).toHaveText(`${expected.total}건`);
  await expect(page.getByRole("button", { name: "이전" })).toBeEnabled();
  // 이전은 빈 쪽을 여러 번 지나지 않고 마지막 쪽으로 간다 (시험 자료는 한 쪽에 다 든다: 처음부터다).
  await page.getByRole("button", { name: "이전" }).click();
  await expect(page.getByTestId("pager-position")).toHaveText(`전체 ${expected.total}건 가운데 1~${expected.total}번째`);

  // 틀린 값은 서버가 알린다. 거르는 것을 바꾸면 처음부터 다시 본다.
  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING&offset=abc`);
  await expect(page.getByRole("alert")).toContainText("건너뛸 수(offset)의 값이 틀렸습니다");
  await page.getByLabel("상태").selectOption("ALL");
  await expect(page).not.toHaveURL(/offset=/);
  await expect(page.getByRole("table", { name: "조치 목록" })).toBeVisible();
});

test("조치의 근거 경로는 그 조치의 대상 하나만 물어 받는다", async ({ page, request }) => {
  const expected = await listActions(request, { packId: READ_PACK, status: "PENDING" });
  const onImpactTarget = expected.actions.find((action) => action.appliesTo === "IMPACT_TARGET");
  if (!onImpactTarget) {
    throw new Error("시험 자료에 영향 대상에 거는 조치가 없습니다.");
  }
  const asked: string[] = [];
  page.on("request", (sent) => {
    if (sent.url().includes("/api/v1/impact-scopes")) {
      asked.push(sent.url());
    }
  });
  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING`);

  await open(page, onImpactTarget.id);

  const path = detail(page, onImpactTarget.id).locator("ol li");
  await expect(path.first()).toBeVisible();
  expect(asked.length).toBeGreaterThan(0);
  for (const url of asked) {
    expect(new URL(url).searchParams.get("entityId")).toBe(onImpactTarget.target.entityId);
  }
});

// ── 개체의 표시 (D-09, D7 2절 "개체") ───────────────────────────────────

test("조치의 대상과 근거 조항과 근거 경로의 개체를 표시 이름과 유형의 이름표와 키로 보여 준다", async ({ page, request }) => {
  const expected = await listActions(request, { packId: READ_PACK, status: "PENDING" });
  const action = expected.actions.find((candidate) => candidate.appliesTo === "IMPACT_TARGET" && candidate.target.displayName);
  if (!action) {
    throw new Error("시험 자료에 표시 이름이 있는 영향 대상에 거는 조치가 없습니다.");
  }
  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING`);

  // 목록: 대상과 근거 조항. 서버가 준 값 그대로다.
  const line = row(page, action.id);
  const target = line.locator(`[data-entity-id="${action.target.entityId}"]`);
  await expect(target.getByTestId("entity-name")).toHaveText(action.target.displayName ?? action.target.entityKey);
  await expect(target.getByTestId("entity-type")).toHaveText(`(${action.target.entityTypeLabel} ${action.target.entityKey})`);
  const basis = line.locator(`[data-entity-id="${action.basis.entity.entityId}"]`);
  await expect(basis.getByTestId("entity-name")).toHaveText(action.basis.entity.displayName ?? action.basis.entity.entityKey);

  // 근거 경로: 시작한 변동 대상과 단계의 개체.
  await open(page, action.id);
  const scope = (await (
    await request.get("/api/v1/impact-scopes", { params: { changeEventId: action.changeEventId, entityId: action.target.entityId } })
  ).json()) as {
    targets: {
      path: {
        start: { entityId: string; entityKey: string; displayName: string | null };
        steps: { source: { entityId: string; entityKey: string; displayName: string | null } }[];
      };
    }[];
  };
  const reached = scope.targets[0];
  if (!reached) {
    throw new Error("조치의 대상이 그 변동의 영향 범위에 없습니다.");
  }
  const path = detail(page, action.id).getByRole("region", { name: "근거 경로" });
  const start = path.locator("p").locator(`[data-entity-id="${reached.path.start.entityId}"]`);
  await expect(start.getByTestId("entity-name")).toHaveText(reached.path.start.displayName ?? reached.path.start.entityKey);
  const firstStep = reached.path.steps[0];
  if (!firstStep) {
    throw new Error("근거 경로에 단계가 없습니다.");
  }
  await expect(path.locator("ol > li").first().getByTestId("entity-name").first()).toHaveText(
    firstStep.source.displayName ?? firstStep.source.entityKey,
  );
});

// /code-review 121 이 짚은 것 (카드 D-21): 내보낸 파일의 행의 수는 화면의 전체 건수와 같아야 한다.
test("목록을 받은 뒤 상태를 바꿨으면 목록을 다시 받기 전에는 파일로 내보내지 못한다", async ({ page, request }) => {
  const action = await freshAction(request);
  // 대기인 것만 걸러서 연다: 확정하면 서버의 그 조건에서는 빠지지만 화면의 줄과 전체 건수는 받았을 때의 것이다.
  await page.goto(`/actions?packId=${WRITE_PACK}&status=PENDING&changeEventId=${encodeURIComponent(action.changeEventId)}`);
  const exports = page.getByRole("group", { name: "조치 목록 내보내기" });
  await expect(exports.getByRole("button", { name: "CSV" })).toBeEnabled();

  await open(page, action.id);
  await detail(page, action.id).getByRole("button", { name: "확정" }).click();
  await expect(row(page, action.id).getByTestId("status")).toHaveText("확정");

  // 지금 내보내면 파일의 행의 수가 화면의 전체 건수와 다르다: 막고 까닭을 알린다.
  await expect(page.getByTestId("list-outdated")).toContainText("파일로 받으려면");
  await expect(exports.getByRole("button", { name: "CSV" })).toBeDisabled();
  await expect(exports.getByRole("button", { name: "JSON" })).toBeDisabled();

  await page.getByRole("button", { name: "목록 다시 받기" }).click();
  await expect(page.getByTestId("list-outdated")).toHaveCount(0);
});
