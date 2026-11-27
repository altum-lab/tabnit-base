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

// ── /code-review 가 짚은 것 (A-20 의 후속 수정) ─────────────────────────────

function picker(page: Page) {
  return page.getByRole("region", { name: "개체 찾기" });
}

async function largestGroup(request: import("@playwright/test").APIRequestContext) {
  const answer = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-wide", asOf: "2026-03-01" } })
  ).json()) as Answer;
  return { answer, group: [...answer.typeGroups].sort((a, b) => b.entityCount - a.entityCount)[0]! };
}

test("유형 거르기를 풀면 거르지 않은 목록으로 다시 찾는다", async ({ page, request }) => {
  const { group } = await largestGroup(request);
  const all = (await (await request.get("/api/v1/entities", { params: { packId: "e2e-wide", asOf: "2026-03-01" } })).json()) as { total: number };
  const filtered = (await (
    await request.get("/api/v1/entities", { params: { packId: "e2e-wide", entityType: group.entityType, asOf: "2026-03-01" } })
  ).json()) as { total: number };
  expect(all.total).toBeGreaterThan(filtered.total);

  await open(page, "e2e-wide", "2026-03-01");
  await overview(page).locator(`[data-type-group="${group.entityType}"]`).getByRole("button").click();
  await expect(picker(page).getByTestId("pager-position")).toContainText(`전체 ${filtered.total}건`);

  await picker(page).getByRole("button", { name: "유형 거르기 풀기" }).click();

  // 안내만 사라지는 것이 아니다: 목록과 전체 수가 거르지 않은 것으로 바뀐다.
  await expect(picker(page).getByTestId("picker-type-filter")).toHaveCount(0);
  await expect(picker(page).getByTestId("pager-position")).toContainText(`전체 ${all.total}건`);
});

test("개체 찾기의 Pack 을 바꾸면 유형 거르기가 풀린다", async ({ page, request }) => {
  const { group } = await largestGroup(request);
  await open(page, "e2e-wide", "2026-03-01");
  await overview(page).locator(`[data-type-group="${group.entityType}"]`).getByRole("button").click();
  await expect(picker(page).getByTestId("picker-type-filter")).toBeVisible();

  await picker(page).getByLabel("Pack", { exact: true }).selectOption("e2e-compare");

  // 다른 Pack 을 보는데 "이 유형의 개체만 찾습니다"가 남아 있지 않다.
  await expect(picker(page).getByTestId("picker-type-filter")).toHaveCount(0);
});

test("유형을 누르면 개체 찾기의 안내로 초점이 가고 화면에 보인다", async ({ page, request }) => {
  const { group } = await largestGroup(request);
  await open(page, "e2e-wide", "2026-03-01");
  await overview(page).locator(`[data-type-group="${group.entityType}"]`).getByRole("button").click();

  // 전체 보기는 화면의 맨 아래에, 개체 찾기는 맨 위에 있다: 누른 결과가 화면 밖에 있지 않다.
  await expect(picker(page).getByTestId("picker-type-filter")).toBeFocused();
  await expect(picker(page).getByTestId("picker-type-filter")).toBeInViewport();
});

test("조회 시점을 바꾸면 열어 둔 전체 보기를 그 날짜로 다시 받는다", async ({ page, request }) => {
  const later = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-compare", asOf: "2026-03-01" } })
  ).json()) as Answer;
  await open(page, "e2e-compare", "2026-03-01");
  await expect(overview(page).getByTestId("pack-map-summary")).toContainText(`이어진 개체 ${later.connectedEntities}개`);

  // 단추를 다시 누르지 않는다. 자료가 들어오기 전의 날짜: 유효한 관계가 없다.
  await page.getByLabel("조회 시점").fill("2019-01-01");

  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2019-01-01");
  await expect(overview(page).getByTestId("pack-map-empty")).toBeVisible();
  // 그래서 전체 보기에서 누른 것은 전체 보기가 보이는 그 날짜로 열린다 (다른 날짜의 목록에서 고르는 일이 없다).
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-03-01");
  await overview(page).locator("[data-pack-map-node]").first().getByRole("button").click();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", "2026-03-01");
});

// 서버의 답을 두 유형 사이에 관계 묶음이 셋인 것으로 바꿔 준다 (방향이 다른 것과 관계 유형이 다른 것).
// 유형의 이름표는 이름과 다르게 준다: 시험용 Pack 의 유형은 이름과 이름표가 같아 가릴 수 없다.
async function mockGrouped(page: Page) {
  await page.route("**/api/v1/pack-relation-map**", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as Record<string, unknown>;
    await route.fulfill({
      response,
      json: {
        ...body,
        grouped: true,
        connectedEntities: 12,
        relations: 12,
        nodes: [],
        edges: [],
        typeGroups: [
          { entityType: "A", entityTypeLabel: "가 유형", entityCount: 5 },
          { entityType: "B", entityTypeLabel: "나 유형", entityCount: 7 },
        ],
        relationGroups: [
          { sourceEntityType: "A", relationType: "R", targetEntityType: "B", relationCount: 7 },
          { sourceEntityType: "A", relationType: "S", targetEntityType: "B", relationCount: 2 },
          { sourceEntityType: "B", relationType: "R", targetEntityType: "A", relationCount: 3 },
        ],
      },
    });
  });
}

test("유형 사이의 관계의 표도 유형의 이름표로 보이고, 찾은 수가 다를 수 있다고 알린다", async ({ page }) => {
  await mockGrouped(page);
  await open(page, "e2e-wide", "2026-03-01");

  const row = overview(page).locator('[data-relation-group="B|R|A"]');
  await expect(row.getByTestId("relation-group-source")).toContainText("나 유형");
  await expect(row.getByTestId("relation-group-target")).toContainText("가 유형");
  // 묶음의 수와 개체 찾기의 수가 다를 수 있는 까닭을 적는다.
  await expect(overview(page).getByTestId("type-group-note")).toContainText("하위 유형");
});

test("묶음 그림은 같은 두 유형 사이의 관계 묶음들을 선 하나로 그리고 그 모두를 적는다", async ({ page }) => {
  await mockGrouped(page);
  await open(page, "e2e-wide", "2026-03-01");

  const lines = overview(page).locator("svg [data-pack-map-pair]");
  await expect(lines).toHaveCount(1);
  await expect(lines.first()).toHaveAttribute("data-group-count", "3");
  const title = await lines.first().locator("title").textContent();
  expect(title).toContain("R");
  expect(title).toContain("S");
  expect(title).toContain("7건");
  expect(title).toContain("2건");
  expect(title).toContain("3건");
  // 선의 설명도 유형의 이름표로 적는다.
  expect(title).toContain("가 유형");
});
