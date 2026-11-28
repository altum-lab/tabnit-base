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
  // 그림은 캔버스다 (개체 하나의 둘레의 지도와 같은 그래프 그림). 그린 노드와 간선은 서버가 준 것과 차례까지 같다.
  const graph = map.getByTestId("pack-map-graph");
  await expect(graph).toHaveAttribute("data-mode", "nodes");
  expect(JSON.parse((await graph.getAttribute("data-node-ids")) ?? "[]")).toEqual(answer.nodes.map((node) => node.entityId));
  expect(JSON.parse((await graph.getAttribute("data-link-ids")) ?? "[]")).toEqual(answer.edges.map((edge) => edge.id));
  await expect.poll(() => marked(page), { timeout: 20_000, message: "그림이 그려진다" }).toBeGreaterThan(50);
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
  const graph = map.getByTestId("pack-map-graph");
  await expect(graph).toHaveAttribute("data-mode", "groups");
  expect(JSON.parse((await graph.getAttribute("data-node-ids")) ?? "[]")).toEqual(answer.typeGroups.map((group) => group.entityType));
  expect(JSON.parse((await graph.getAttribute("data-link-ids")) ?? "[]")).toEqual(
    answer.relationGroups.map((group) => `${group.sourceEntityType}|${group.relationType}|${group.targetEntityType}`),
  );
  await expect.poll(() => marked(page), { timeout: 20_000, message: "그림이 그려진다" }).toBeGreaterThan(50);
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
  await expect(overview(page).locator("canvas")).toHaveCount(0);

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

// 전체 보기의 그림(캔버스)에 바탕색이 아닌 점이 몇 개인가. 캔버스의 안은 바로 읽을 수 없어 찍은 그림으로 본다.
async function marked(page: Page) {
  const canvas = overview(page).getByTestId("pack-map-graph").locator("canvas").first();
  const png = (await canvas.screenshot()).toString("base64");
  return page.evaluate(async (data) => {
    const image = new Image();
    image.src = `data:image/png;base64,${data}`;
    await image.decode();
    const sheet = document.createElement("canvas");
    sheet.width = image.width;
    sheet.height = image.height;
    const context = sheet.getContext("2d")!;
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, sheet.width, sheet.height).data;
    let count = 0;
    for (let i = 0; i < pixels.length; i += 4) {
      if ((pixels[i] ?? 255) < 235 || (pixels[i + 1] ?? 255) < 235 || (pixels[i + 2] ?? 255) < 235) {
        count++;
      }
    }
    return count;
  }, png);
}

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

test("묶음 그림은 같은 두 유형 사이의 관계 묶음들을 겹치지 않게 따로 휘어 그린다", async ({ page }) => {
  await mockGrouped(page);
  await open(page, "e2e-wide", "2026-03-01");

  const graph = overview(page).getByTestId("pack-map-graph");
  await expect(graph).toHaveAttribute("data-mode", "groups");
  // 묶음마다 선 하나다. 차례는 서버가 준 묶음의 차례다.
  expect(JSON.parse((await graph.getAttribute("data-link-ids")) ?? "[]")).toEqual(["A|R|B", "A|S|B", "B|R|A"]);
  // 같은 두 유형 사이의 셋이 같은 자리에 겹치지 않는다: 휘는 정도가 서로 다르다.
  // (휘는 정도는 두 유형을 이름 차례로 놓고 본 쪽이다: 방향이 반대인 선도 같은 기준으로 견준다.)
  const bends = JSON.parse((await graph.getAttribute("data-link-bends")) ?? "[]") as number[];
  expect(new Set(bends).size).toBe(3);
  await expect.poll(() => marked(page), { timeout: 20_000 }).toBeGreaterThan(50);
});

// ── 그림의 3D, 시점 막대, 가리기, 점 누르기 (개체 하나의 둘레의 지도에 있는 것을 전체 보기에도 둔다) ──────────

type Detailed = {
  nodes: { entityId: string; entity: { entityType: string } }[];
  edges: { id: string; relationType: string; sourceEntityId: string; targetEntityId: string }[];
};

function graph(page: Page) {
  return overview(page).getByTestId("pack-map-graph");
}

async function ids(page: Page, attribute: "data-node-ids" | "data-link-ids") {
  return JSON.parse((await graph(page).getAttribute(attribute)) ?? "[]") as string[];
}

// 그림 안에서 그 점이 놓인 자리 (그림 칸의 왼쪽 위에서 잰 화면의 점). 배치가 끝나야 적힌다.
async function spotOf(page: Page, id: string) {
  await expect.poll(async () => (await graph(page).getAttribute("data-node-spots")) ?? "[]", { timeout: 20_000 }).toContain(JSON.stringify(id));
  const spots = JSON.parse((await graph(page).getAttribute("data-node-spots")) ?? "[]") as { id: string; x: number; y: number }[];
  return spots.find((spot) => spot.id === id)!;
}

// 그림의 점을 누른다. 그리는 라이브러리는 어느 점 위인지를 가리는 속 그림을 0.8초에 한 번만 고치고, 마우스가 올라온
// 점을 다음 화면 갱신에서 알아챈다. 그래서 자리가 잡힌 뒤 1초를 두고, 올려 두었다가 누른다 (사람이 하는 차례다).
async function press(page: Page, spot: { x: number; y: number }) {
  const canvas = graph(page).locator("canvas").first();
  await page.waitForTimeout(1000);
  await canvas.hover({ position: { x: spot.x, y: spot.y } });
  await page.waitForTimeout(300);
  await canvas.click({ position: { x: spot.x, y: spot.y } });
}

test("전체 보기를 3D 로 바꾸면 같은 개체와 관계를 3D 로 그린다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  await expect(graph(page)).toHaveAttribute("data-view", "2d");
  const nodes = await ids(page, "data-node-ids");
  const links = await ids(page, "data-link-ids");

  await overview(page).getByRole("radio", { name: "3D" }).check();
  await expect(graph(page)).toHaveAttribute("data-view", "3d");
  expect(await ids(page, "data-node-ids")).toEqual(nodes);
  expect(await ids(page, "data-link-ids")).toEqual(links);
  await expect.poll(() => marked(page), { timeout: 30_000, message: "3D 그림이 그려진다" }).toBeGreaterThan(50);

  await overview(page).getByRole("radio", { name: "2D" }).check();
  await expect(graph(page)).toHaveAttribute("data-view", "2d");
});

test("전체 보기의 시점 막대를 옮기면 화면의 조회 시점이 바뀌고 전체 보기를 그 날짜로 다시 받는다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  const bar = overview(page).getByLabel("전체 보기의 시점을 옮기는 막대");
  await expect(bar).toHaveValue("0");
  await expect(overview(page).getByTestId("pack-map-bar-from")).toHaveText("2025-03-01");
  await expect(overview(page).getByTestId("pack-map-bar-to")).toHaveText("2027-03-01");

  // 61일 뒤는 2026-05-01 이다.
  await bar.fill("61");
  await expect(overview(page).getByTestId("pack-map-bar-date")).toHaveText("2026-05-01");
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-05-01");
  // 화면의 조회 시점도 그 날짜다: 여기서 고른 개체가 같은 날짜로 열린다.
  await expect(page.getByLabel("조회 시점")).toHaveValue("2026-05-01");
  // 막대의 범위는 그대로다 (처음 연 날짜의 앞뒤 1년).
  await expect(overview(page).getByTestId("pack-map-bar-from")).toHaveText("2025-03-01");
  await expect(bar).toHaveValue("61");

  // 조회 시점을 손으로 바꾸면 막대의 범위가 그 날짜를 가운데로 다시 잡힌다.
  await page.getByLabel("조회 시점").fill("2026-01-01");
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-01-01");
  await expect(overview(page).getByTestId("pack-map-bar-from")).toHaveText("2025-01-01");
  await expect(bar).toHaveValue("0");
});

test("전체 보기에서 유형과 관계 유형을 끄면 그림에서만 빠지고 수와 표는 그대로다", async ({ page, request }) => {
  const answer = (await (
    await request.get("/api/v1/pack-relation-map", { params: { packId: "e2e-verified", asOf: "2026-03-01" } })
  ).json()) as Detailed & Answer;
  expect(answer.grouped).toBe(false);
  const types = [...new Set(answer.nodes.map((node) => node.entity.entityType))];
  expect(types.length, "시험용 Pack 에 유형이 둘 이상이어야 한다").toBeGreaterThan(1);
  const hiddenType = types[0]!;

  await open(page, "e2e-verified", "2026-03-01");
  const summary = await overview(page).getByTestId("pack-map-summary").innerText();
  await expect(overview(page).getByTestId("pack-map-filtered")).toHaveCount(0);

  await overview(page).locator(`input[data-entity-type="${hiddenType}"]`).uncheck();
  const keptNodes = answer.nodes.filter((node) => node.entity.entityType !== hiddenType).map((node) => node.entityId);
  const kept = new Set(keptNodes);
  const keptEdges = answer.edges.filter((edge) => kept.has(edge.sourceEntityId) && kept.has(edge.targetEntityId));
  expect(await ids(page, "data-node-ids")).toEqual(keptNodes);
  expect(await ids(page, "data-link-ids")).toEqual(keptEdges.map((edge) => edge.id));
  // 서버가 준 수와 아래의 목록은 가리기 전의 것 그대로다.
  await expect(overview(page).getByTestId("pack-map-filtered")).toBeVisible();
  await expect(overview(page).getByTestId("pack-map-summary")).toHaveText(summary);
  await expect(overview(page).locator("[data-pack-map-node]")).toHaveCount(answer.nodes.length);

  // 관계 유형도 끌 수 있다.
  const relationType = keptEdges[0]!.relationType;
  await overview(page).locator(`input[data-relation-type="${relationType}"]`).uncheck();
  expect(await ids(page, "data-link-ids")).toEqual(keptEdges.filter((edge) => edge.relationType !== relationType).map((edge) => edge.id));

  // 다시 켜면 서버가 준 것 그대로 돌아온다. 서버에 다시 묻지 않는다.
  await overview(page).locator(`input[data-entity-type="${hiddenType}"]`).check();
  await overview(page).locator(`input[data-relation-type="${relationType}"]`).check();
  expect(await ids(page, "data-node-ids")).toEqual(answer.nodes.map((node) => node.entityId));
  await expect(overview(page).getByTestId("pack-map-filtered")).toHaveCount(0);
});

test("묶음 그림에서도 유형과 관계 유형을 끌 수 있다", async ({ page }) => {
  await mockGrouped(page);
  await open(page, "e2e-wide", "2026-03-01");
  expect(await ids(page, "data-link-ids")).toEqual(["A|R|B", "A|S|B", "B|R|A"]);

  await overview(page).locator('input[data-relation-type="S"]').uncheck();
  expect(await ids(page, "data-link-ids")).toEqual(["A|R|B", "B|R|A"]);
  await overview(page).locator('input[data-entity-type="B"]').uncheck();
  expect(await ids(page, "data-node-ids")).toEqual(["A"]);
  expect(await ids(page, "data-link-ids")).toEqual([]);
  // 표는 그대로다.
  await expect(overview(page).locator("[data-relation-group]")).toHaveCount(3);
});

test("그림의 개체의 점을 누르면 그 개체를 가운데로 지도가 열린다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  const target = (await ids(page, "data-node-ids"))[1]!;
  // 속 그림이 고쳐지기 전에 누르면 라이브러리가 누름을 알아채지 못한다 (자리가 잡힌 뒤 1초 안). 될 때까지 다시 누른다.
  // 점의 자리는 누를 때마다 다시 읽는다.
  await expect(async () => {
    await press(page, await spotOf(page, target));
    await expect(page.getByTestId("relation-map")).toHaveAttribute("data-center", target, { timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", "2026-03-01");
});

test("묶음 그림의 유형의 점을 누르면 개체 찾기가 그 유형으로 걸러진다", async ({ page }) => {
  await open(page, "e2e-wide", "2026-03-01");
  const target = (await ids(page, "data-node-ids"))[0]!;
  const note = picker(page).getByTestId("picker-type-filter");
  await expect(async () => {
    await press(page, await spotOf(page, target));
    await expect(note).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 20_000 });
  // 이름표와 이름이 같으면 한 번만 적는다.
  await expect(note).toContainText(`유형 ${target}의 개체만 찾습니다.`);
});

test("유형 거르기의 안내는 이름표와 이름이 다르면 이름을 함께 적는다", async ({ page }) => {
  await mockGrouped(page);
  await open(page, "e2e-wide", "2026-03-01");
  await overview(page).locator('[data-type-group="A"]').getByRole("button").click();
  await expect(picker(page).getByTestId("picker-type-filter")).toContainText("유형 가 유형 (A)의 개체만 찾습니다.");
});

// ── 코드 검토가 짚은 것 ────────────────────────────────────────────────────

test("막대로 청한 것이 끝나기 전에 조회 시점을 바꾸면 막대도 그 날짜를 가운데로 다시 잡힌다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  // 막대가 청한 날짜(2026-05-01)의 답만 늦춘다.
  await page.route("**/api/v1/pack-relation-map**", async (route) => {
    if (route.request().url().includes("asOf=2026-05-01")) {
      await new Promise((resolve) => setTimeout(resolve, 1500));
    }
    try {
      await route.continue();
    } catch {
      // 화면이 그 청을 버렸다.
    }
  });
  const bar = overview(page).getByLabel("전체 보기의 시점을 옮기는 막대");
  await bar.fill("61");
  await page.getByLabel("조회 시점").fill("2026-01-01");

  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-01-01");
  await expect(overview(page).getByTestId("pack-map-bar-from")).toHaveText("2025-01-01");
  await expect(overview(page).getByTestId("pack-map-bar-date")).toHaveText("2026-01-01");
  await expect(bar).toHaveValue("0");
});

test("전체 보기의 시점 막대를 옮기면 위에 열어 둔 개체의 지도도 그 날짜로 다시 받는다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  await overview(page).locator("[data-pack-map-node]").first().getByRole("button").click();
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", "2026-03-01");

  await overview(page).getByLabel("전체 보기의 시점을 옮기는 막대").fill("61");
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-05-01");
  // 화면의 조회 시점이 바뀌었으니 위의 지도와 그 막대도 그 날짜다.
  await expect(page.getByTestId("relation-map")).toHaveAttribute("data-as-of", "2026-05-01");
  await expect(page.getByTestId("time-bar-date")).toHaveText("2026-05-01");
});

test("끝의 유형이 묶음에 없는 관계의 묶음은 그리지 않고 화면은 그대로 선다", async ({ page }) => {
  await page.route("**/api/v1/pack-relation-map**", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as Record<string, unknown>;
    await route.fulfill({
      response,
      json: {
        ...body,
        grouped: true,
        nodes: [],
        edges: [],
        typeGroups: [{ entityType: "A", entityTypeLabel: "가 유형", entityCount: 5 }],
        relationGroups: [
          { sourceEntityType: "A", relationType: "R", targetEntityType: "A", relationCount: 2 },
          { sourceEntityType: "A", relationType: "R", targetEntityType: "없는유형", relationCount: 7 },
        ],
      },
    });
  });
  await open(page, "e2e-wide", "2026-03-01");

  expect(await ids(page, "data-link-ids")).toEqual(["A|R|A"]);
  await expect.poll(() => marked(page), { timeout: 20_000 }).toBeGreaterThan(50);
  // 표에는 서버가 준 것이 모두 있다.
  await expect(overview(page).locator("[data-relation-group]")).toHaveCount(2);
  await expect(overview(page).getByTestId("pack-map-unmatched")).toBeVisible();
});

test("다른 Pack 의 전체 보기를 열면 가린 것이 풀린다", async ({ page }) => {
  await open(page, "e2e-verified", "2026-03-01");
  // 두 시험용 Pack 이 함께 쓰는 유형의 이름이다.
  const name = "의료사업";
  await overview(page).locator(`input[data-entity-type="${name}"]`).uncheck();
  await expect(overview(page).getByTestId("pack-map-filtered")).toBeVisible();

  // 같은 이름의 유형을 쓰는 다른 Pack 이다.
  await overview(page).getByLabel("전체를 볼 Pack").selectOption("e2e-compare");
  await overview(page).getByRole("button", { name: "전체 지도 보기" }).click();
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-pack-id", "e2e-compare");
  await expect(overview(page).getByTestId("pack-map-filtered")).toHaveCount(0);
  for (const box of await overview(page).locator("input[data-entity-type]").all()) {
    await expect(box).toBeChecked();
  }
});

test("막대로 옮긴 날짜를 받지 못했으면 그 날짜로 다시 청할 수 있다", async ({ page }) => {
  await open(page, "e2e-compare", "2026-03-01");
  let fail = true;
  await page.route("**/api/v1/pack-relation-map**", async (route) => {
    if (fail && route.request().url().includes("asOf=2026-05-01")) {
      await route.abort();
      return;
    }
    await route.continue();
  });
  const bar = overview(page).getByLabel("전체 보기의 시점을 옮기는 막대");
  await bar.fill("61");
  await expect(overview(page).getByRole("alert")).toBeVisible();

  fail = false;
  await overview(page).getByRole("button", { name: "다시 받기" }).click();
  await expect(overview(page).getByTestId("pack-map")).toHaveAttribute("data-as-of", "2026-05-01");
  await expect(bar).toHaveValue("61");
});
