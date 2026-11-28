import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-23 수용 기준: 영향 범위 화면에서 개체와 조회 시점을 주면 그 개체에서 출발한 영향 범위를 지금 계산해 보여 준다.
// 화면은 서버(docs/api/openapi.yaml 의 getImpactFromEntity, D7 6절 "출발 개체로 영향을 묻는다", D2 AD-429)가 준
// 수, 순서, 날짜, 근거 경로를 그대로 그린다. 다시 세거나 정렬하지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData 의 e2e-actions: 시험용 Pack 의 시나리오). 이 시험은 읽기만 한다.

const ENTITY = "e2e-actions|운영지침|G-01";

type Impact = {
  entityId: string;
  asOf: string;
  asOfPrevious: string | null;
  maxDepth: number;
  truncated: boolean;
  total: number;
  countsByType: { entityType: string; count: number }[];
  countsByDepth: { depth: number; count: number }[];
  targets: { entityId: string; depth: number; path: { asOf: string; steps: { relationId: string }[] } }[];
};

async function impactOf(request: APIRequestContext, params: Record<string, string>) {
  const response = await request.get("/api/v1/impact", { params });
  expect(response.ok(), await response.text()).toBe(true);
  return (await response.json()) as Impact;
}

function asker(page: Page) {
  return page.getByRole("region", { name: "개체로 영향 묻기" });
}

async function ask(page: Page, entityId: string, asOf: string) {
  await page.goto("/impact");
  await asker(page).getByLabel("출발 개체의 id").fill(entityId);
  await asker(page).getByLabel("조회 시점").fill(asOf);
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();
}

async function targetIds(page: Page) {
  const table = asker(page).getByRole("table", { name: "영향 대상" });
  await expect(table).toBeVisible();
  return table.locator("tbody tr[data-target-id]").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-target-id")));
}

test("개체와 조회 시점으로 물으면 서버가 준 수와 목록 그대로 보이고 저장된 것이 아니라고 알린다", async ({ page, request }) => {
  const answer = await impactOf(request, { entityId: ENTITY, asOf: "2026-03-01" });
  expect(answer.total, "시험 자료에 영향 대상이 있어야 한다").toBeGreaterThan(1);

  await ask(page, ENTITY, "2026-03-01");

  const result = asker(page).getByTestId("entity-impact");
  await expect(result).toHaveAttribute("data-entity-id", ENTITY);
  await expect(result.getByTestId("entity-impact-total")).toHaveText(String(answer.total));
  // 날짜와 최대 깊이는 서버가 답한 값이다.
  await expect(result.getByTestId("entity-impact-as-of")).toHaveText(answer.asOf);
  await expect(result.getByTestId("entity-impact-max-depth")).toHaveText(String(answer.maxDepth));
  // 저장된 영향 범위가 아니라 지금 계산한 것이다.
  await expect(result).toContainText("지금의 자료로 계산한 것입니다");
  await expect(result).toContainText("저장하지 않습니다");
  expect(await targetIds(page)).toEqual(answer.targets.map((target) => target.entityId));
  // 유형별 수와 깊이별 수도 서버의 값이다.
  for (const one of answer.countsByType) {
    await expect(result.locator(`tr[data-entity-type="${one.entityType}"] td`)).toHaveText(String(one.count));
  }
  for (const one of answer.countsByDepth) {
    await expect(result.locator(`tr[data-depth="${one.depth}"] td`)).toHaveText(String(one.count));
  }
});

test("대상마다 근거 경로를 펼쳐 볼 수 있고 어느 날짜의 관계인지 보이며 관계 지도로 이어 간다", async ({ page, request }) => {
  const answer = await impactOf(request, { entityId: ENTITY, asOf: "2026-03-01" });
  const deep = answer.targets.find((target) => target.depth > 1) ?? answer.targets[0]!;

  await ask(page, ENTITY, "2026-03-01");

  const row = asker(page).locator(`tr[data-target-id="${deep.entityId}"]`);
  await expect(row.getByTestId("target-depth")).toHaveText(String(deep.depth));
  await row.getByText("근거 경로 보기").click();
  // 단계의 수와 차례가 서버의 것이다.
  expect(await row.locator("ol li").evaluateAll((items) => items.map((item) => item.getAttribute("data-relation-id")))).toEqual(
    deep.path.steps.map((step) => step.relationId),
  );
  await expect(row.getByTestId("path-as-of")).toHaveText(deep.path.asOf);
  // 그 개체를 가운데로, 같은 조회 시점으로 관계 지도를 연다.
  await row.getByRole("link", { name: "관계 지도" }).click();
  await expect(page).toHaveURL(/\/relations\?/);
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-center", deep.entityId);
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", answer.asOf);
});

test("전날의 관계를 함께 보게 하고 최대 깊이를 줄여 물을 수 있다", async ({ page, request }) => {
  const shallow = await impactOf(request, { entityId: ENTITY, asOf: "2026-03-01", includePreviousDay: "true", maxDepth: "1" });

  await page.goto("/impact");
  await asker(page).getByLabel("출발 개체의 id").fill(ENTITY);
  await asker(page).getByLabel("조회 시점").fill("2026-03-01");
  await asker(page).getByLabel("전날의 관계도 함께 따라간다").check();
  await asker(page).getByLabel("최대 깊이").fill("1");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();

  const result = asker(page).getByTestId("entity-impact");
  await expect(result.getByTestId("entity-impact-total")).toHaveText(String(shallow.total));
  await expect(result.getByTestId("entity-impact-max-depth")).toHaveText("1");
  await expect(result.getByTestId("entity-impact-as-of-previous")).toHaveText(shallow.asOfPrevious!);
  expect(await targetIds(page)).toEqual(shallow.targets.map((target) => target.entityId));
  // 최대 깊이에서 멈췄고 그 너머에 대상이 있으면 그렇다고 알린다 (서버가 준 값).
  await expect(result.getByTestId("entity-impact-truncated")).toHaveCount(shallow.truncated ? 1 : 0);
});

test("조회 시점을 비우면 서버가 정한 날짜를 보이고, 영향 대상이 없으면 없다고 말한다", async ({ page, request }) => {
  // 자료가 들어오기 전의 날짜: 관계가 없다.
  await ask(page, ENTITY, "2025-12-31");
  await expect(asker(page).getByRole("status")).toContainText("영향을 받는 대상이 없습니다");
  await expect(asker(page).getByRole("table", { name: "영향 대상" })).toHaveCount(0);

  // 조회 시점을 비운다: 화면은 오늘을 정하지 않는다.
  const today = await impactOf(request, { entityId: ENTITY });
  await asker(page).getByLabel("조회 시점").fill("");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();
  await expect(asker(page).getByTestId("entity-impact-as-of")).toHaveText(today.asOf);
});

test("틀린 입력과 없는 개체는 서버의 문구를 보이고, 닿지 못하면 다시 물을 수 있다", async ({ page }) => {
  await ask(page, "e2e-actions|운영지침|없는-지침", "2026-03-01");
  await expect(asker(page).getByRole("alert")).toBeVisible();
  await expect(asker(page).getByTestId("entity-impact")).toHaveCount(0);

  // Pack 이 정한 한도보다 깊게는 묻지 못한다: 서버가 알린 문구를 그대로 보인다.
  await asker(page).getByLabel("출발 개체의 id").fill(ENTITY);
  await asker(page).getByLabel("최대 깊이").fill("99");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();
  await expect(asker(page).getByRole("alert")).toContainText("최대 깊이");

  // 서버에 닿지 못한다.
  await page.route("**/api/v1/impact?*", (route) => route.abort());
  await asker(page).getByLabel("최대 깊이").fill("");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();
  await expect(asker(page).getByRole("alert")).toBeVisible();
  await page.unroute("**/api/v1/impact?*");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();
  await expect(asker(page).getByTestId("entity-impact")).toBeVisible();
});

test("변동의 저장된 영향 범위를 보는 중에도 개체로 물을 수 있고 둘이 섞이지 않는다", async ({ page, request }) => {
  const actions = (await (await request.get("/api/v1/actions", { params: { packId: "e2e-actions", limit: "200" } })).json())
    .actions as { changeEventId: string; appliesTo: string }[];
  const changeEventId = actions.find((action) => action.appliesTo === "IMPACT_TARGET")!.changeEventId;
  await page.goto(`/impact?${new URLSearchParams({ changeEventId })}`);
  await expect(page.getByTestId("impact-total")).toBeVisible();
  const storedTotal = await page.getByTestId("impact-total").innerText();

  await asker(page).getByLabel("출발 개체의 id").fill(ENTITY);
  await asker(page).getByLabel("조회 시점").fill("2026-03-01");
  await asker(page).getByRole("button", { name: "영향 묻기" }).click();

  await expect(asker(page).getByTestId("entity-impact")).toBeVisible();
  // 위의 저장된 영향 범위는 그대로다.
  await expect(page.getByTestId("impact-total")).toHaveText(storedTotal);
});
