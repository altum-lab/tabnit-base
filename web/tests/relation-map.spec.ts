import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-08 수용 기준: 시점 막대를 옮기면 그 시점의 관계가 그려진다.
//
// 지도는 관계 지도 API (docs/api/openapi.yaml 의 getRelationMap) 의 값을 그대로 그린다 (D2 AD-352, AD-353).
// 그림은 캔버스에 그려 시험이 그 안을 읽지 못한다. 그래서 그림과 같은 자료로 만든 노드 목록과,
// 지도에 적어 둔 값(가운데 개체, 조회 시점, 깊이)으로 본다. 그림 자체는 캔버스가 있고 비어 있지 않은지만 본다.
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData).

async function search(page: Page, entityId: string, asOf?: string) {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill(entityId);
  if (asOf) {
    await page.getByLabel("조회 시점").fill(asOf);
  }
  await page.getByRole("button", { name: "조회" }).click();
}

function map(page: Page) {
  return page.getByTestId("relation-map");
}

async function nodeIds(page: Page) {
  await expect(map(page)).toBeVisible();
  return page
    .getByRole("list", { name: "지도의 노드" })
    .locator("li")
    .evaluateAll((items) => items.map((item) => item.getAttribute("data-node-id")));
}

async function relationIds(page: Page) {
  const table = page.getByRole("table", { name: "관계 목록" });
  await expect(table).toBeVisible();
  return table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-relation-id")));
}

test("개체를 조회하면 그 개체를 가운데 둔 지도를 그리고 서버가 준 노드를 그 순서대로 보여 준다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("heading", { name: "관계 지도", level: 1 })).toBeVisible();
  await expect(map(page)).toHaveAttribute("data-center", "e2e|T|ga");
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-03-01");
  await expect(map(page)).toHaveAttribute("data-depth", "1");
  // 2026-03-01 에는 가와 나의 관계만 유효하다.
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|na"]);
  const answer = await (
    await page.request.get("/api/v1/relation-map", { params: { entityId: "e2e|T|ga", asOf: "2026-03-01" } })
  ).json();
  expect(await nodeIds(page)).toEqual(answer.nodes.map((node: { entityId: string }) => node.entityId));
  // 가운데 개체와 이웃을 사람이 읽을 말로 적는다. 깊이는 서버가 준 값이다.
  const nodes = page.getByRole("list", { name: "지도의 노드" }).locator("li");
  await expect(nodes.nth(0)).toContainText("가운데");
  await expect(nodes.nth(0)).toContainText("ga");
  await expect(nodes.nth(1)).toContainText("깊이 1");
  await expect(nodes.nth(1)).toContainText("na");
  // 그림: 캔버스가 있고 무언가 그려져 있다.
  const canvas = map(page).locator("canvas");
  await expect(canvas).toBeVisible();
  await expect
    .poll(() =>
      canvas.evaluate((element) => {
        const target = element as HTMLCanvasElement;
        const pixels = target.getContext("2d")!.getImageData(0, 0, target.width, target.height).data;
        for (let i = 3; i < pixels.length; i += 4) {
          if (pixels[i] !== 0) {
            return true;
          }
        }
        return false;
      }),
    )
    .toBe(true);
  await page.screenshot({ path: "test-results/relation-map-ga.png", fullPage: true });
  // 아래의 관계 목록도 같은 시점이다.
  await expect(page.getByTestId("answered-as-of")).toHaveText("2026-03-01");
  expect(await relationIds(page)).toEqual(["e2e|T|ga|T|na|R|src|1|2026-03-01"]);
});

test("시점 막대를 옮기면 그 시점의 관계가 그려진다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|na"]);

  // 막대의 범위는 처음 답한 조회 시점의 앞뒤 1년이다. 하루 단위다.
  const bar = page.getByLabel("시점 막대");
  await expect(bar).toHaveAttribute("min", "-365");
  await expect(bar).toHaveAttribute("max", "365");
  await expect(bar).toHaveValue("0");
  await expect(page.getByTestId("time-bar-from")).toHaveText("2025-03-01");
  await expect(page.getByTestId("time-bar-to")).toHaveText("2027-03-01");

  // 92일 뒤는 2026-06-01 이다: 가와 나의 관계가 끝나고 가와 다의 관계가 시작한다.
  await bar.fill("92");
  await expect(page.getByTestId("time-bar-date")).toHaveText("2026-06-01");
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-06-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|da"]);
  await expect(page.getByTestId("answered-as-of")).toHaveText("2026-06-01");
  expect(await relationIds(page)).toEqual(["e2e|T|ga|T|da|R|src|1|2026-06-01"]);
  // 막대의 범위는 옮겨도 그대로다.
  await expect(page.getByTestId("time-bar-from")).toHaveText("2025-03-01");

  // 앞으로 옮긴다: 2026-01-01 에는 가와 라의 관계만 유효하다.
  await bar.fill("-59");
  await expect(page.getByTestId("time-bar-date")).toHaveText("2026-01-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|ra"]);

  // 어느 관계도 없는 날: 가운데 개체만 남고, 없다고 말한다.
  await bar.fill("-365");
  await expect(map(page)).toHaveAttribute("data-as-of", "2025-03-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga"]);
  await expect(page.getByRole("status")).toContainText("유효한 관계가 없습니다");
});

test("날짜를 적어 조회하면 막대의 범위를 그 날짜의 앞뒤로 다시 잡는다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  await expect(page.getByTestId("time-bar-to")).toHaveText("2027-03-01");

  await page.getByLabel("조회 시점").fill("2028-02-29");
  await page.getByRole("button", { name: "조회" }).click();

  await expect(map(page)).toHaveAttribute("data-as-of", "2028-02-29");
  await expect(page.getByLabel("시점 막대")).toHaveValue("0");
  // 2028년은 윤년이다: 365일 앞은 2027-03-01 이다.
  await expect(page.getByTestId("time-bar-from")).toHaveText("2027-03-01");
  await expect(page.getByTestId("time-bar-to")).toHaveText("2029-02-28");
});

test("조회 시점을 비우면 서버가 답한 날짜를 막대의 가운데로 삼는다", async ({ page }) => {
  await search(page, "e2e|T|me");

  // 화면이 오늘을 정하지 않는다.
  const answered = await map(page).getAttribute("data-as-of");
  expect(answered).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(page.getByTestId("time-bar-date")).toHaveText(answered!);
  await expect(page.getByLabel("시점 막대")).toHaveValue("0");
});

test("노드를 고르면 그 개체를 가운데로 지도와 관계 목록을 다시 받는다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|me", "e2e|T|x", "e2e|T|y"]);

  await page.locator('li[data-node-id="e2e|T|x"]').getByRole("button", { name: "가운데로" }).click();

  await expect(map(page)).toHaveAttribute("data-center", "e2e|T|x");
  // 조회 시점은 그대로다.
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-03-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|x", "e2e|T|me"]);
  await expect(page.getByLabel("개체 id")).toHaveValue("e2e|T|x");
  expect(await relationIds(page)).toEqual([
    "e2e|T|me|T|x|a-type|src|1|2020-01-01",
    "e2e|T|x|T|me|a-type|src|1|2020-01-01",
  ]);
});

test("깊이를 바꾸면 그 깊이까지의 이웃을 다시 받는다", async ({ page }) => {
  await search(page, "e2e|T|x", "2026-03-01");
  expect(await nodeIds(page)).toEqual(["e2e|T|x", "e2e|T|me"]);

  await page.getByLabel("깊이").selectOption("2");

  await expect(map(page)).toHaveAttribute("data-depth", "2");
  // 엑스에서 나를 거쳐 와이까지.
  expect(await nodeIds(page)).toEqual(["e2e|T|x", "e2e|T|me", "e2e|T|y"]);
  await expect(page.locator('li[data-node-id="e2e|T|y"]')).toContainText("깊이 2");
  await page.screenshot({ path: "test-results/relation-map-depth2.png", fullPage: true });
});

test("이웃이 한도를 넘으면 서버가 담아 준 것만 그리고 잘렸다고 알린다", async ({ page }) => {
  await search(page, "e2e|T|crowd", "2026-03-01");

  // 수는 서버가 준 값이다 (가운데 개체를 넣어 251개 가운데 200개).
  await expect(page.getByTestId("map-truncated")).toContainText("200개");
  await expect(page.getByTestId("map-truncated")).toContainText("251개");
  await expect(page.getByRole("list", { name: "지도의 노드" }).locator("li")).toHaveCount(200);
  await map(page).screenshot({ path: "test-results/relation-map-crowd.png" });
});

test("더 깊이 청해도 한도를 넘은 깊이에서 멈췄다고 서버가 준 값으로 알린다", async ({ page }) => {
  await search(page, "e2e|T|crowd", "2026-03-01");
  // 깊이 1 까지 본 것: 그 깊이 안의 전체다.
  await expect(page.getByTestId("map-truncated")).toContainText("이 깊이 안의 개체는 모두 251개");

  await page.getByLabel("깊이").selectOption("3");

  // 깊이 1 에서 이미 한도를 넘어 더 따라가지 않았다 (D2 AD-357). 그리는 것은 같다.
  await expect(map(page)).toHaveAttribute("data-depth", "3");
  await expect(page.getByTestId("map-truncated")).toContainText("깊이 1 에서 더 따라가지 않았습니다");
  await expect(page.getByTestId("map-truncated")).toContainText("거기까지 찾은 251개 가운데 가까운 200개");
  await expect(page.getByRole("list", { name: "지도의 노드" }).locator("li")).toHaveCount(200);
});

test("잘리지 않은 지도에는 잘렸다는 말이 없다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");

  await expect(map(page)).toBeVisible();
  await expect(page.getByTestId("map-truncated")).toHaveCount(0);
});

test("지도를 받지 못하면 화면이 멈추지 않고 이유를 보여 준다", async ({ page }) => {
  await page.route("**/api/v1/relation-map**", (route) => route.abort("connectionrefused"));

  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  await expect(map(page)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "조회" })).toBeEnabled();
});

test("막대를 옮긴 뒤 지도를 받지 못하면 이유를 보여 주고 막대로 다시 시도할 수 있다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  await expect(map(page)).toBeVisible();
  let fail = true;
  await page.route("**/api/v1/relation-map**", (route) => (fail ? route.abort("connectionrefused") : route.continue()));

  const bar = page.getByLabel("시점 막대");
  await bar.fill("92");
  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  // 막대는 남아 있다.
  await expect(bar).toBeVisible();

  fail = false;
  await bar.fill("91");
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-05-31");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|na"]);
});

// ── 실패한 뒤에 다시 청할 때 (검토에서 찾은 것) ─────────────────────────────

test("없는 개체를 조회해 실패한 뒤 깊이나 막대를 바꿔도 앞서 본 개체의 지도로 돌아가지 않는다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");
  await expect(map(page)).toHaveAttribute("data-center", "e2e|T|me");

  await page.getByLabel("개체 id").fill("e2e|T|missing");
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByRole("alert")).toContainText("e2e|T|missing");

  // 화면에 적힌 개체로 다시 청한다. 앞서 본 개체의 지도를 조용히 그리지 않는다.
  await page.getByLabel("깊이").selectOption("2");
  await expect(page.getByRole("alert")).toContainText("e2e|T|missing");
  await expect(map(page)).toHaveCount(0);
  await page.getByLabel("시점 막대").fill("10");
  await expect(page.getByRole("alert")).toContainText("e2e|T|missing");
  await expect(map(page)).toHaveCount(0);
});

test("막대를 옮긴 것이 실패한 뒤 깊이를 바꾸면 막대가 가리키는 날짜로 받는다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  await expect(map(page)).toBeVisible();
  let fail = true;
  await page.route("**/api/v1/relation-map**", (route) => (fail ? route.abort("connectionrefused") : route.continue()));

  await page.getByLabel("시점 막대").fill("92");
  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  await expect(page.getByTestId("time-bar-date")).toHaveText("2026-06-01");

  fail = false;
  await page.getByLabel("깊이").selectOption("2");
  // 막대와 날짜 입력이 보이는 날짜(2026-06-01)의 지도다. 앞서 받은 날짜(2026-03-01)가 아니다.
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-06-01");
  await expect(map(page)).toHaveAttribute("data-depth", "2");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|da"]);
});

test("받아 오는 동안에는 막대와 깊이를 바꾸지 못한다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  await expect(map(page)).toBeVisible();
  await page.route("**/api/v1/relation-map**", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 800));
    await route.continue();
  });

  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByLabel("시점 막대")).toBeDisabled();
  await expect(page.getByLabel("깊이")).toBeDisabled();
  await expect(map(page)).toBeVisible();
  await expect(page.getByLabel("시점 막대")).toBeEnabled();
});


// ── 3D 로 보기 (D-14, D2 AD-361) ───────────────────────────────────────────
//
// 3D 그림도 캔버스(WebGL)라 시험이 그 안을 읽지 못한다. 고르기가 되는지, 그림이 비어 있지 않은지,
// 같은 답의 노드 목록이 그대로인지, 2D 와 같은 일(가운데 옮기기, 시점 막대, 깊이)이 되는지를 본다.

// 가장자리에 걸친 점이 이보다 적으면 그림이 화면 안에 들어온 것으로 본다. 선 하나가 가장자리를 지나면 여덟 점 이상이
// 걸린다. 찍은 그림의 경계에 한두 점의 얼룩이 생길 수 있어 0 으로 두지 않는다.
const EDGE_NOISE = 8;

// 찍은 그림에 바탕색이 아닌 점이 있는지 본다. WebGL 캔버스는 안의 점을 바로 읽을 수 없어 찍은 그림으로 본다.
async function drawn(page: Page) {
  const canvas = map(page).locator("canvas").first();
  await expect(canvas).toBeVisible();
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
    let marked = 0;
    // 가장자리(바깥 여덟 점)에 걸친 점의 수. 그림이 화면 밖으로 나가면 여기에 걸린다.
    let onEdge = 0;
    const edge = 8;
    for (let i = 0; i < pixels.length; i += 4) {
      // 바탕은 흰색이다.
      if ((pixels[i] ?? 255) < 235 || (pixels[i + 1] ?? 255) < 235 || (pixels[i + 2] ?? 255) < 235) {
        marked++;
        const x = (i / 4) % sheet.width;
        const y = Math.floor(i / 4 / sheet.width);
        if (x < edge || y < edge || x >= sheet.width - edge || y >= sheet.height - edge) {
          onEdge++;
        }
      }
    }
    return { marked, onEdge };
  }, png);
}

test("처음에는 2D 로 그리고 3D 의 코드는 내려받지 않는다", async ({ page }) => {
  const scripts: string[] = [];
  page.on("request", (request) => {
    if (request.resourceType() === "script") {
      scripts.push(request.url());
    }
  });

  await search(page, "e2e|T|me", "2026-03-01");

  await expect(map(page)).toHaveAttribute("data-view", "2d");
  await expect(page.getByRole("radio", { name: "2D" })).toBeChecked();
  await expect(page.getByRole("radio", { name: "3D" })).not.toBeChecked();
  expect(scripts.filter((url) => /RelationMapGraph3D|three/i.test(url))).toEqual([]);
});

test("3D 를 고르면 같은 노드와 간선을 3D 로 그린다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");
  const before = await nodeIds(page);

  await page.getByRole("radio", { name: "3D" }).check();

  await expect(map(page)).toHaveAttribute("data-view", "3d");
  await expect(page.getByTestId("map-3d")).toBeVisible();
  // 그림이 그려지고, 그림 전체가 화면 안에 들어온다 (가장자리에 걸치지 않는다).
  await expect.poll(async () => (await drawn(page)).marked, { timeout: 20_000 }).toBeGreaterThan(50);
  await expect.poll(async () => (await drawn(page)).onEdge, { timeout: 20_000 }).toBeLessThan(EDGE_NOISE);
  // 노드 목록은 같은 답 그대로다.
  expect(await nodeIds(page)).toEqual(before);
  await map(page).screenshot({ path: "test-results/relation-map-3d-me.png" });

  // 2D 로 돌아갈 수 있다.
  await page.getByRole("radio", { name: "2D" }).check();
  await expect(map(page)).toHaveAttribute("data-view", "2d");
  await expect(page.getByTestId("map-3d")).toHaveCount(0);
});

test("3D 에서도 가운데를 옮기고 시점 막대와 깊이를 바꾸면 다시 그리며 3D 가 유지된다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");
  await page.getByRole("radio", { name: "3D" }).check();
  await expect(page.getByTestId("map-3d")).toBeVisible();
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|na"]);

  // 시점 막대.
  await page.getByLabel("시점 막대").fill("92");
  await expect(map(page)).toHaveAttribute("data-as-of", "2026-06-01");
  await expect(map(page)).toHaveAttribute("data-view", "3d");
  expect(await nodeIds(page)).toEqual(["e2e|T|ga", "e2e|T|da"]);
  await expect.poll(async () => (await drawn(page)).marked, { timeout: 20_000 }).toBeGreaterThan(50);
  await expect.poll(async () => (await drawn(page)).onEdge, { timeout: 20_000 }).toBeLessThan(EDGE_NOISE);

  // 가운데 옮기기.
  await page.locator('li[data-node-id="e2e|T|da"]').getByRole("button", { name: "가운데로" }).click();
  await expect(map(page)).toHaveAttribute("data-center", "e2e|T|da");
  await expect(map(page)).toHaveAttribute("data-view", "3d");

  // 깊이.
  await page.getByLabel("깊이").selectOption("2");
  await expect(map(page)).toHaveAttribute("data-depth", "2");
  await expect(map(page)).toHaveAttribute("data-view", "3d");
  await expect(page.getByTestId("map-3d")).toBeVisible();
});

test("노드가 많은 지도도 3D 로 그린다", async ({ page }) => {
  await search(page, "e2e|T|crowd", "2026-03-01");

  await page.getByRole("radio", { name: "3D" }).check();

  await expect(page.getByTestId("map-3d")).toBeVisible();
  await expect.poll(async () => (await drawn(page)).marked, { timeout: 30_000 }).toBeGreaterThan(500);
  // 노드 200개도 화면 안에 들어온다.
  await expect.poll(async () => (await drawn(page)).onEdge, { timeout: 30_000 }).toBeLessThan(EDGE_NOISE);
  await expect(page.getByTestId("map-truncated")).toContainText("251개");
  await map(page).screenshot({ path: "test-results/relation-map-3d-crowd.png" });
});

test("3D 의 코드를 받지 못하면 이유를 알리고 2D 로 돌아갈 수 있다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");
  await page.route(/RelationMapGraph3D/, (route) => route.abort("connectionrefused"));

  await page.getByRole("radio", { name: "3D" }).check();

  await expect(page.getByTestId("map-3d-failed")).toContainText("3D 그림을 불러오지 못했습니다");
  await page.getByRole("radio", { name: "2D" }).check();
  await expect(map(page)).toHaveAttribute("data-view", "2d");
  await expect(map(page).locator("canvas")).toBeVisible();
});
