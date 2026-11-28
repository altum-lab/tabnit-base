import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-20 수용 기준: 관계 지도에서 유형과 관계 유형을 켜고 끄면 그 노드와 간선만 그려진다. 화면은 서버가 준 것을
// 가리기만 한다. 모두 끄면 보일 것이 없다고 말한다.
//
// 정답은 이 시험이 서버의 답(getRelationMap)에서 같은 유형만 골라 만든다: 화면이 낸 것을 옮겨 적지 않는다.
// 그림은 캔버스라 읽지 못한다. 그림과 같은 자료로 만든 노드 목록과, 지도에 적어 둔 간선의 id 로 본다.
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData).

type Answer = {
  nodes: { entityId: string; entity: { entityType: string; entityTypeLabel: string } }[];
  edges: { id: string; relationType: string; sourceEntityId: string; targetEntityId: string }[];
};

// 시험용 Pack 의 사본. 사업 하나에 지침, 기관, 다른 사업이 여러 관계 유형으로 이어져 있다.
const MIXED = { entityId: "e2e-actions|의료사업|P-01", asOf: "2026-02-01" };

async function open(page: Page, entityId: string, asOf: string, depth = 1) {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill(entityId);
  await page.getByLabel("조회 시점").fill(asOf);
  await page.getByLabel("깊이").selectOption(String(depth));
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByTestId("relation-map")).toBeVisible();
}

async function answer(request: import("@playwright/test").APIRequestContext, entityId: string, asOf: string, depth = 1) {
  const response = await request.get("/api/v1/relation-map", { params: { entityId, asOf, depth } });
  expect(response.status()).toBe(200);
  return (await response.json()) as Answer;
}

async function shownNodes(page: Page) {
  return page
    .getByRole("list", { name: "지도의 노드" })
    .locator("li")
    .evaluateAll((items) => items.map((item) => item.getAttribute("data-node-id")));
}

async function shownEdges(page: Page) {
  return JSON.parse((await page.getByTestId("relation-map").getAttribute("data-shown-edges")) ?? "null") as string[];
}

// 서버의 답에서 켜 둔 유형만 고른다. 간선은 관계 유형이 켜져 있고 두 끝이 모두 남은 것이다.
function expected(map: Answer, offTypes: string[], offRelationTypes: string[]) {
  const nodes = map.nodes.filter((node) => !offTypes.includes(node.entity.entityType)).map((node) => node.entityId);
  const edges = map.edges
    .filter(
      (edge) =>
        !offRelationTypes.includes(edge.relationType) &&
        nodes.includes(edge.sourceEntityId) &&
        nodes.includes(edge.targetEntityId),
    )
    .map((edge) => edge.id);
  return { nodes, edges };
}

test("처음에는 모두 켜져 있고 서버가 준 노드와 간선을 모두 그린다", async ({ page, request }) => {
  await open(page, MIXED.entityId, MIXED.asOf);
  const map = await answer(request, MIXED.entityId, MIXED.asOf);
  const types = [...new Set(map.nodes.map((node) => node.entity.entityType))];
  const relationTypes = [...new Set(map.edges.map((edge) => edge.relationType))];
  expect(types.length, "유형이 둘 이상이어야 이 시험에 뜻이 있다").toBeGreaterThan(1);
  expect(relationTypes.length, "관계 유형이 둘 이상이어야 이 시험에 뜻이 있다").toBeGreaterThan(1);

  const filter = page.getByRole("group", { name: "보일 것" });
  // 고를 것은 서버의 답에 나온 유형과 관계 유형이고, 답에 처음 나온 차례다.
  expect(await filter.locator("[data-entity-type]").evaluateAll((boxes) => boxes.map((b) => b.getAttribute("data-entity-type")))).toEqual(types);
  expect(
    await filter.locator("[data-relation-type]").evaluateAll((boxes) => boxes.map((b) => b.getAttribute("data-relation-type"))),
  ).toEqual(relationTypes);
  for (const box of await filter.getByRole("checkbox").all()) {
    await expect(box).toBeChecked();
  }
  expect(await shownNodes(page)).toEqual(map.nodes.map((node) => node.entityId));
  expect(await shownEdges(page)).toEqual(map.edges.map((edge) => edge.id));
  await expect(page.getByTestId("map-filtered")).toHaveCount(0);
});

test("유형을 끄면 그 유형의 노드와 거기에 닿은 간선이 빠지고 나머지는 서버의 순서 그대로다", async ({ page, request }) => {
  await open(page, MIXED.entityId, MIXED.asOf);
  const map = await answer(request, MIXED.entityId, MIXED.asOf);
  // 가운데 개체의 유형이 아닌 것 하나를 끈다.
  const centerType = map.nodes[0]!.entity.entityType;
  const off = map.nodes.find((node) => node.entity.entityType !== centerType)!.entity.entityType;
  const want = expected(map, [off], []);
  expect(want.nodes.length).toBeLessThan(map.nodes.length);
  expect(want.edges.length).toBeLessThan(map.edges.length);

  await page.getByRole("group", { name: "보일 것" }).locator(`[data-entity-type="${off}"]`).uncheck();

  expect(await shownNodes(page)).toEqual(want.nodes);
  expect(await shownEdges(page)).toEqual(want.edges);
  await expect(page.getByTestId("map-filtered")).toBeVisible();
  // 그림은 그대로 있다.
  await expect(page.getByTestId("relation-map").locator("canvas")).toBeVisible();

  // 다시 켜면 돌아온다.
  await page.getByRole("group", { name: "보일 것" }).locator(`[data-entity-type="${off}"]`).check();
  expect(await shownNodes(page)).toEqual(map.nodes.map((node) => node.entityId));
  expect(await shownEdges(page)).toEqual(map.edges.map((edge) => edge.id));
});

test("관계 유형을 끄면 그 간선만 빠지고 노드는 남는다", async ({ page, request }) => {
  await open(page, "e2e|T|me", "2026-03-01");
  const map = await answer(request, "e2e|T|me", "2026-03-01");
  const want = expected(map, [], ["a-type"]);
  expect(want.edges.length).toBeGreaterThan(0);
  expect(want.edges.length).toBeLessThan(map.edges.length);

  await page.getByRole("group", { name: "보일 것" }).locator('[data-relation-type="a-type"]').uncheck();

  expect(await shownNodes(page)).toEqual(map.nodes.map((node) => node.entityId));
  expect(await shownEdges(page)).toEqual(want.edges);
});

test("유형과 관계 유형을 함께 꺼도 서버의 답에서 고른 것과 같다", async ({ page, request }) => {
  await open(page, MIXED.entityId, MIXED.asOf, 2);
  const map = await answer(request, MIXED.entityId, MIXED.asOf, 2);
  const centerType = map.nodes[0]!.entity.entityType;
  const offType = map.nodes.find((node) => node.entity.entityType !== centerType)!.entity.entityType;
  const offRelation = map.edges[0]!.relationType;
  const want = expected(map, [offType], [offRelation]);

  const filter = page.getByRole("group", { name: "보일 것" });
  await filter.locator(`[data-entity-type="${offType}"]`).uncheck();
  await filter.locator(`[data-relation-type="${offRelation}"]`).uncheck();

  expect(await shownNodes(page)).toEqual(want.nodes);
  expect(await shownEdges(page)).toEqual(want.edges);
});

test("유형을 모두 끄면 보일 것이 없다고 말한다", async ({ page }) => {
  await open(page, "e2e|T|me", "2026-03-01");

  await page.getByRole("group", { name: "보일 것" }).locator('[data-entity-type="T"]').uncheck();

  await expect(page.getByTestId("map-empty")).toContainText("보일 것이 없습니다");
  expect(await shownNodes(page)).toEqual([]);
  expect(await shownEdges(page)).toEqual([]);
  await expect(page.getByTestId("relation-map").locator("canvas")).toHaveCount(0);
});

test("가려도 서버가 준 전체 수의 표시는 그대로다", async ({ page }) => {
  await open(page, "e2e|T|crowd", "2026-03-01");
  const before = await page.getByTestId("map-truncated").textContent();
  expect(before).toContain("251개");

  await page.getByRole("group", { name: "보일 것" }).locator('[data-relation-type="R"]').uncheck();

  await expect(page.getByTestId("map-truncated")).toHaveText(before ?? "");
  expect(await shownEdges(page)).toEqual([]);
  // 관계 목록(시점 조회 API 의 값)과 그 전체 수도 그대로다: 지도의 그림만 가린다.
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
});

test("가린 것은 시점을 옮겨 다시 받아도, 3D 로 바꿔도 그대로다", async ({ page, request }) => {
  await open(page, "e2e|T|me", "2026-03-01");
  await page.getByRole("group", { name: "보일 것" }).locator('[data-relation-type="a-type"]').uncheck();

  await page.getByLabel("조회 시점").fill("2026-04-01");
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", "2026-04-01");
  const map = await answer(request, "e2e|T|me", "2026-04-01");
  await expect(page.getByRole("group", { name: "보일 것" }).locator('[data-relation-type="a-type"]')).not.toBeChecked();
  expect(await shownEdges(page)).toEqual(expected(map, [], ["a-type"]).edges);

  await page.getByRole("radio", { name: "3D" }).check();
  await expect(page.getByTestId("map-3d")).toBeVisible();
  expect(await shownEdges(page)).toEqual(expected(map, [], ["a-type"]).edges);
});

// ── 코드 검토(/code-review 120)가 짚은 것 ─────────────────────────────────────

test("3D 의 코드를 받는 중이거나 받지 못했어도, 모두 껐으면 보일 것이 없다고만 말한다", async ({ page }) => {
  // 3D 의 코드를 받지 못하게 한다.
  await page.route(/RelationMapGraph3D/, (route) => route.abort());
  await open(page, MIXED.entityId, MIXED.asOf);
  await page.getByRole("radio", { name: "3D" }).check();
  await expect(page.getByTestId("map-3d-failed")).toBeVisible();

  for (const box of await page.getByRole("group", { name: "보일 것" }).locator("[data-entity-type]").all()) {
    await box.uncheck();
  }
  await expect(page.getByTestId("map-empty")).toBeVisible();
  // 그릴 것이 없는데 그림을 받지 못했다고 알리지 않는다.
  await expect(page.getByTestId("map-3d-failed")).toHaveCount(0);
  await expect(page.getByTestId("map-3d-loading")).toHaveCount(0);
});

test("가린 유형이 지금의 지도에 없어도 칸이 남아 다시 켤 수 있다", async ({ page }) => {
  await open(page, "e2e|T|me", "2026-03-01");
  const filter = page.getByRole("group", { name: "보일 것" });
  await filter.locator('[data-relation-type="a-type"]').uncheck();

  // 그 관계 유형이 없는 다른 개체의 지도.
  await page.getByLabel("개체 id").fill(MIXED.entityId);
  await page.getByLabel("조회 시점").fill(MIXED.asOf);
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-center", MIXED.entityId);

  const box = filter.locator('[data-relation-type="a-type"]');
  await expect(box).toHaveCount(1);
  await expect(box).not.toBeChecked();
  await expect(filter.getByTestId("hidden-absent")).toContainText("이 지도에 없는");
  // 이 지도에서 가려진 것은 없다.
  await expect(page.getByTestId("map-filtered")).toHaveCount(0);

  // 누르면 칸이 사라지므로 (아래) 상태를 확인하는 check 가 아니라 click 으로 누른다.
  await box.click();
  // 켜면 가린 것이 없으니, 이 지도에 없는 유형의 칸은 사라진다.
  await expect(filter.locator('[data-relation-type="a-type"]')).toHaveCount(0);
  await expect(filter.getByTestId("hidden-absent")).toHaveCount(0);
});

test("강제 색 모드에서도 그림의 테두리가 보인다", async ({ page }) => {
  await page.emulateMedia({ forcedColors: "active" });
  await open(page, MIXED.entityId, MIXED.asOf);
  const picture = page.locator('[role="img"][aria-label^="관계 지도 그림"]');
  // 강제 색 모드에서는 브라우저가 그림자를 그리지 않는다: 테두리는 외곽선으로 그린다.
  expect(await picture.evaluate((element) => getComputedStyle(element).outlineStyle)).toBe("solid");
});

test("가린 것의 안내는 위에 수가 있을 때만 수를 말한다", async ({ page }) => {
  // 잘리지 않은 지도: 위에 수가 없다.
  await open(page, "e2e|T|me", "2026-03-01");
  await expect(page.getByTestId("map-truncated")).toHaveCount(0);
  await page.getByRole("group", { name: "보일 것" }).locator('[data-relation-type="a-type"]').uncheck();
  await expect(page.getByTestId("map-filtered")).toContainText("관계 목록은 가리기 전의 것 그대로입니다");
  await expect(page.getByTestId("map-filtered")).not.toContainText("위의 수");
});
