import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-03 수용 기준: 영향 범위 화면의 숫자와 목록이 실제 API 에서 온다.
//
// 화면은 변동 하나의 저장된 영향 범위(docs/api/openapi.yaml 의 getChangeImpactScope)를 그대로 보여 준다 (D2 AD-365).
// 수, 순서, 날짜, 근거 경로는 서버가 준 값이다. 화면이 다시 세거나 정렬하지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (server/api/src/test 의 E2eSeedData): e2e-actions 는 시험용 Pack 의 시나리오를
// 올려 조치까지 만들어 둔 Pack 이다. 이 시험은 읽기만 한다.

const READ_PACK = "e2e-actions";

type Scope = {
  changeEventId: string;
  packId: string;
  asOfPrevious: string;
  asOfEffective: string;
  maxDepth: number;
  truncated: boolean;
  total: number;
  countsByType: { entityType: string; count: number }[];
  countsByDepth: { depth: number; count: number }[];
  targets: {
    entityId: string;
    depth: number;
    entity: { entityKey: string; displayName: string | null; entityTypeLabel: string };
    path: { asOf: string; steps: { relationId: string }[] };
  }[];
};

// 영향 대상이 있는 변동 하나를 고른다: 대상이 영향 대상인 조치의 변동이다.
async function changeWithTargets(request: APIRequestContext) {
  // 상태를 주지 않으면 서버가 가리지 않고 모두 준다 (화면 주소의 status=ALL 은 화면의 것이고 서버에는 보내지 않는다).
  const response = await request.get("/api/v1/actions", { params: { packId: READ_PACK, limit: "200" } });
  expect(response.ok()).toBe(true);
  const actions = (await response.json()).actions as { changeEventId: string; appliesTo: string }[];
  const found = actions.find((action) => action.appliesTo === "IMPACT_TARGET");
  expect(found, "영향 대상에 건 조치가 시험 자료에 있어야 한다").toBeTruthy();
  return found!.changeEventId;
}

async function scopeOf(request: APIRequestContext, changeEventId: string) {
  const response = await request.get("/api/v1/impact-scopes", { params: { changeEventId } });
  expect(response.ok()).toBe(true);
  return (await response.json()) as Scope;
}

function address(changeEventId: string) {
  return `/impact?${new URLSearchParams({ changeEventId })}`;
}

async function targetIds(page: Page) {
  const table = page.getByRole("table", { name: "영향 대상" });
  await expect(table).toBeVisible();
  return table.locator("tbody tr[data-target-id]").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-target-id")));
}

test("변동의 영향 범위를 서버가 준 수와 목록 그대로 보여 준다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);
  const scope = await scopeOf(request, changeEventId);
  expect(scope.total).toBeGreaterThan(0);

  await page.goto(address(changeEventId));

  await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
  // 수는 서버가 센 값이다.
  await expect(page.getByTestId("impact-total")).toHaveText(String(scope.total));
  // 어느 두 날짜로 보았는지.
  await expect(page.getByTestId("impact-as-of-previous")).toHaveText(scope.asOfPrevious);
  await expect(page.getByTestId("impact-as-of-effective")).toHaveText(scope.asOfEffective);
  await expect(page.getByTestId("impact-max-depth")).toHaveText(String(scope.maxDepth));
  // 유형별 수와 깊이별 수: 서버의 순서와 값 그대로.
  const byType = page.getByRole("table", { name: "유형별 수" }).locator("tbody tr");
  await expect(byType).toHaveCount(scope.countsByType.length);
  for (const [index, one] of scope.countsByType.entries()) {
    await expect(byType.nth(index)).toHaveAttribute("data-entity-type", one.entityType);
    await expect(byType.nth(index).locator("td").last()).toHaveText(String(one.count));
  }
  const byDepth = page.getByRole("table", { name: "깊이별 수" }).locator("tbody tr");
  await expect(byDepth).toHaveCount(scope.countsByDepth.length);
  for (const [index, one] of scope.countsByDepth.entries()) {
    await expect(byDepth.nth(index).locator("th")).toHaveText(String(one.depth));
    await expect(byDepth.nth(index).locator("td")).toHaveText(String(one.count));
  }
  // 영향 대상의 목록: 서버의 순서 그대로.
  expect(await targetIds(page)).toEqual(scope.targets.map((target) => target.entityId));
  const first = scope.targets[0]!;
  const row = page.locator(`tr[data-target-id="${first.entityId}"]`);
  // 대상 칸의 이름이다 (같은 행의 근거 경로에도 개체의 이름이 있다).
  await expect(row.locator("td").nth(1).getByTestId("entity-name")).toHaveText(first.entity.displayName ?? first.entity.entityKey);
  await expect(row.getByTestId("target-depth")).toHaveText(String(first.depth));
  // 한계에서 멈추지 않았으면 그 말이 없다.
  await expect(page.getByTestId("impact-truncated")).toHaveCount(scope.truncated ? 1 : 0);
});

test("영향 대상마다 근거 경로를 펼쳐 볼 수 있고 변동 전후 어느 관계로 닿았는지 보인다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);
  const scope = await scopeOf(request, changeEventId);
  const first = scope.targets[0]!;

  await page.goto(address(changeEventId));
  const row = page.locator(`tr[data-target-id="${first.entityId}"]`);
  await row.getByText("근거 경로 보기").click();

  // 단계는 서버가 준 순서와 수 그대로다 (단계의 수는 깊이와 같다).
  const steps = row.locator("ol > li");
  await expect(steps).toHaveCount(first.path.steps.length);
  const ids = await steps.evaluateAll((items) => items.map((item) => item.getAttribute("data-relation-id")));
  expect(ids).toEqual(first.path.steps.map((step) => step.relationId));
  const basis = first.path.asOf === scope.asOfEffective ? "변동 후의 관계" : "변동 전의 관계";
  await expect(row.getByTestId("path-basis")).toHaveText(basis);
});

test("조치 화면에서 그 변동의 영향 범위로 이어 온다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);

  await page.goto(`/actions?${new URLSearchParams({ packId: READ_PACK, status: "ALL", changeEventId })}`);
  await page.getByRole("link", { name: "이 변동의 영향 범위" }).click();

  await expect(page).toHaveURL(new RegExp(`/impact\\?changeEventId=${encodeURIComponent(changeEventId).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`));
  await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
  await expect(page.getByTestId("impact-total")).toBeVisible();
  // 돌아가는 길: 그 변동의 조치.
  await page.getByRole("link", { name: "이 변동의 조치 보기" }).click();
  await expect(page).toHaveURL(/\/actions\?/);
  await expect(page.getByRole("heading", { name: "조치", level: 1 })).toBeVisible();
});

test("영향 대상에서 관계 지도로 가면 그 개체와 판의 적용일로 지도가 열린다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);
  const scope = await scopeOf(request, changeEventId);
  const first = scope.targets[0]!;

  await page.goto(address(changeEventId));
  await page.locator(`tr[data-target-id="${first.entityId}"]`).getByRole("link", { name: "관계 지도" }).click();

  await expect(page.getByRole("heading", { name: "관계 지도", level: 1 })).toBeVisible();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-center", first.entityId);
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", scope.asOfEffective);
  await expect(page.getByLabel("개체 id")).toHaveValue(first.entityId);
});

test("메뉴에서 바로 들어오면 변동을 고르는 법을 알리고 서버에 묻지 않는다", async ({ page }) => {
  let asked = 0;
  await page.route("**/api/v1/impact-scopes**", (route) => {
    asked++;
    return route.continue();
  });

  await page.goto("/changes");
  await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "영향 범위" }).click();

  await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
  await expect(page.getByTestId("impact-guide")).toContainText("변동 감지");
  await expect(page.getByTestId("impact-guide")).toContainText("조치");
  expect(asked).toBe(0);
});

test("저장된 영향 범위가 없는 변동은 서버의 문구를 보여 준다", async ({ page }) => {
  await page.goto(address("e2e-actions|없는|변동"));

  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByRole("table", { name: "영향 대상" })).toHaveCount(0);
  // 서버의 문구 그대로다 (공통 오류 형식).
  const answer = await page.request.get("/api/v1/impact-scopes", { params: { changeEventId: "e2e-actions|없는|변동" } });
  expect(answer.status()).toBe(404);
  await expect(page.getByRole("alert")).toContainText((await answer.json()).detail);
});

test("영향 대상이 많으면 나눠 보여 주고 다음 쪽은 서버에 그 자리부터 묻는다", async ({ page, request }) => {
  // 시험 자료의 영향 범위는 한 쪽에 다 들어온다. 나눠 주는 답은 지어내어 돌려준다: 화면이 서버의 전체 수를 쓰고
  // 다음 쪽을 그 자리부터 묻는지만 본다 (나눠 주는 일 자체는 서버의 시험이 본다).
  const changeEventId = await changeWithTargets(request);
  const real = await scopeOf(request, changeEventId);
  const offsets: string[] = [];
  await page.route("**/api/v1/impact-scopes**", async (route) => {
    const offset = new URL(route.request().url()).searchParams.get("offset") ?? "0";
    offsets.push(offset);
    await route.fulfill({ json: { ...real, total: 120, offset: Number(offset), limit: 50 } });
  });

  await page.goto(address(changeEventId));
  await expect(page.getByTestId("impact-total")).toHaveText("120");
  await expect(page.getByTestId("pager-position")).toContainText("전체 120건");
  await page.getByRole("button", { name: "다음" }).click();

  await expect.poll(() => offsets).toContain("50");
  await expect(page).toHaveURL(/offset=50/);
});

test("서버에 닿지 못하면 화면이 멈추지 않고 이유를 보여 주며 다시 불러올 수 있다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);
  let fail = true;
  await page.route("**/api/v1/impact-scopes**", (route) => (fail ? route.abort("connectionrefused") : route.continue()));

  await page.goto(address(changeEventId));
  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");

  fail = false;
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByTestId("impact-total")).toBeVisible();
});
