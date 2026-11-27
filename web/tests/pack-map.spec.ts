import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// A-20 수용 기준: 가운데 개체 없이 Pack 의 지도를 조회 시점으로 연다. 이어진 개체가 한도를 넘으면 유형별로 묶어
// 보이고, 유형마다의 수를 더한 것이 전체 수와 같다 (D7 4절 "Pack 의 전체 지도", D2 AD-419).
//
// 화면은 서버(getPackRelationMap)가 준 것을 그대로 그린다. 다시 세거나 정렬하지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): e2e-compare 는 한도 안의 Pack, e2e-wide 는 이어진 개체가 한도를
// 넘는 Pack 이다. 이 시험은 읽기만 한다.

type Answer = {
  asOf: string;
  maxNodes: number;
  connectedEntities: number;
  isolatedEntities: number;
  relations: number;
  grouped: boolean;
  typeGroups: { entityType: string; entityTypeLabel: string; entityCount: number }[];
  relationGroups: { sourceEntityType: string; relationType: string; targetEntityType: string; relationCount: number }[];
  nodes: { entityId: string }[];
  edges: { id: string }[];
};

function overview(page: Page) {
  return page.getByRole("region", { name: "Pack 전체 보기" });
}

async function open(page: Page, packId: string, asOf: string) {
  await page.goto("/relations");
  await page.getByLabel("조회 시점").fill(asOf);
  await overview(page).getByLabel("전체를 볼 Pack").selectOption(packId);
  await overview(page).getByRole("button", { name: "전체 지도 보기" }).click();
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-pack-id", packId);
}

test("한도 안의 Pack 은 개체와 관계가 서버가 준 차례대로 보이고 개체를 가운데로 지도를 열 수 있다", async ({ page, request }) => {
  const answer = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-compare", asOf: "2026-03-01" } })
  ).json()) as Answer;
  expect(answer.grouped).toBe(false);
  expect(answer.nodes.length).toBeGreaterThan(1);

  await open(page, "e2e-compare", "2026-03-01");

  const map = overview(page).getByTestId("pack-map");
  await expect(map).toHaveAttribute("data-grouped", "false");
  await expect(map.getByTestId("pack-map-as-of")).toHaveText("2026-03-01");
  await expect(map.getByTestId("pack-map-summary")).toContainText(`이어진 개체 ${answer.connectedEntities}개`);
  await expect(map.getByTestId("pack-map-summary")).toContainText(`관계 ${answer.relations}건`);
  await expect(map.getByTestId("pack-map-summary")).toContainText(`관계가 없는 개체 ${answer.isolatedEntities}개`);
  const shownNodes = await map.locator("[data-pack-map-node]").evaluateAll((items) => items.map((item) => item.getAttribute("data-pack-map-node")));
  expect(shownNodes).toEqual(answer.nodes.map((node) => node.entityId));
  const shownEdges = await map.locator("[data-pack-map-edge]").evaluateAll((items) => items.map((item) => item.getAttribute("data-pack-map-edge")));
  expect(shownEdges).toEqual(answer.edges.map((edge) => edge.id));
  await expect(map.getByTestId("pack-map-grouped")).toHaveCount(0);

  // 개체를 가운데로: 위의 관계 지도가 그 개체로 열린다.
  const first = answer.nodes[0]!.entityId;
  await map.locator(`[data-pack-map-node]`).first().getByRole("button").click();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-center", first);
});

test("이어진 개체가 한도를 넘으면 유형별로 묶어 보이고 수를 더한 것이 전체와 같다", async ({ page, request }) => {
  const answer = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-wide", asOf: "2026-03-01" } })
  ).json()) as Answer;
  expect(answer.grouped).toBe(true);
  expect(answer.connectedEntities).toBeGreaterThan(answer.maxNodes);

  await open(page, "e2e-wide", "2026-03-01");

  const map = overview(page).getByTestId("pack-map");
  await expect(map).toHaveAttribute("data-grouped", "true");
  await expect(map.getByTestId("pack-map-grouped")).toContainText(`한도(${answer.maxNodes}개)`);
  await expect(map.locator("[data-pack-map-node]")).toHaveCount(0);
  // 유형의 묶음: 차례와 수가 서버가 준 그대로다. 그림에도 유형마다 하나다.
  const rows = map.locator("[data-type-group]");
  await expect(rows).toHaveCount(answer.typeGroups.length);
  let sum = 0;
  for (const [index, group] of answer.typeGroups.entries()) {
    await expect(rows.nth(index)).toHaveAttribute("data-type-group", group.entityType);
    await expect(rows.nth(index).getByTestId("type-group-count")).toHaveText(String(group.entityCount));
    sum += Number(await rows.nth(index).getByTestId("type-group-count").textContent());
  }
  // 화면에 보이는 유형마다의 수를 더한 것이 화면에 보이는 전체 수와 같다.
  expect(sum).toBe(answer.connectedEntities);
  await expect(map.getByTestId("pack-map-summary")).toContainText(`이어진 개체 ${sum}개`);
  await expect(map.locator("[data-pack-map-group]")).toHaveCount(answer.typeGroups.length);
  const relationRows = map.locator("[data-relation-group]");
  await expect(relationRows).toHaveCount(answer.relationGroups.length);
  for (const [index, group] of answer.relationGroups.entries()) {
    await expect(relationRows.nth(index)).toHaveAttribute(
      "data-relation-group",
      `${group.sourceEntityType}|${group.relationType}|${group.targetEntityType}`,
    );
    await expect(relationRows.nth(index).getByTestId("relation-group-count")).toHaveText(String(group.relationCount));
  }
});

test("유형의 묶음을 누르면 개체 찾기가 그 유형으로 걸러지고, 거르기를 풀 수 있다", async ({ page, request }) => {
  const answer = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-wide", asOf: "2026-03-01" } })
  ).json()) as Answer;
  // 이어진 개체가 가장 많은 유형.
  const group = [...answer.typeGroups].sort((a, b) => b.entityCount - a.entityCount)[0]!;
  const listed = (await (
    await request.get("/api/v1/entities", { params: { packId: "e2e-wide", entityType: group.entityType, asOf: "2026-03-01" } })
  ).json()) as { total: number };
  expect(listed.total).toBeGreaterThanOrEqual(group.entityCount);

  await open(page, "e2e-wide", "2026-03-01");
  await overview(page).locator(`[data-type-group="${group.entityType}"]`).getByRole("button").click();

  const picker = page.getByRole("region", { name: "개체 찾기" });
  await expect(picker.getByTestId("picker-type-filter")).toContainText(group.entityType);
  await expect(picker.getByTestId("pager-position")).toContainText(`전체 ${listed.total}건`);
  await expect(picker.locator("tbody tr").first()).toHaveAttribute("data-entity-id", new RegExp(`\\|${group.entityType}\\|`));

  await picker.getByRole("button", { name: "유형 거르기 풀기" }).click();
  await expect(picker.getByTestId("picker-type-filter")).toHaveCount(0);
});

test("조회 시점에 유효한 관계가 없으면 없다고 알리고, 서버가 거부하면 그 문구를 보인다", async ({ page }) => {
  await open(page, "e2e-compare", "2019-01-01");
  await expect(overview(page).getByTestId("pack-map-empty")).toBeVisible();
  await expect(overview(page).locator("svg")).toHaveCount(0);

  await page.route("**/api/v1/pack-relation-map**", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "PACK_NOT_FOUND", field: "packId", detail: "Pack 이 적재되어 있지 않습니다. Pack 의 식별자를 확인하세요." }),
    }),
  );
  await overview(page).getByRole("button", { name: "전체 지도 보기" }).click();
  await expect(overview(page).getByRole("alert")).toContainText("적재되어 있지 않습니다");
});
