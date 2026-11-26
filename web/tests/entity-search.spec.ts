import { expect, test } from "./fixtures";

// A-17 수용 기준: 관계 지도의 화면에서 식별자를 적지 않고 개체를 고른다. 검색어를 넣으면 후보가 나오고,
// 고르면 그 개체가 열린다 (D7 4절 "개체 찾기", D2 AD-411).
//
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData): 시험용 Pack 의 사본 e2e-actions.
// 이 시험은 읽기만 한다. 화면은 서버가 준 후보와 순서, 전체 수를 그대로 보여 줘야 한다.

const PACK = "e2e-actions";

async function find(page: import("@playwright/test").Page, text: string) {
  const picker = page.getByRole("region", { name: "개체 찾기" });
  await picker.getByLabel("Pack").selectOption(PACK);
  await picker.getByLabel("검색어").fill(text);
  await picker.getByRole("button", { name: "찾기" }).click();
  return picker;
}

test("검색어를 넣으면 후보가 서버가 준 순서와 수 그대로 나온다", async ({ page, request }) => {
  await page.goto("/relations");
  const picker = await find(page, "지원");

  const answer = await (
    await request.get("/api/v1/entities", { params: { packId: PACK, q: "지원" } })
  ).json();
  // 화면도 조회 시점을 비웠다: 둘 다 서버가 정한 날짜로 찾는다.
  const table = picker.getByRole("table", { name: "찾은 개체" });
  await expect(table).toBeVisible();
  const shown = await table
    .locator("tbody tr")
    .evaluateAll((rows) => rows.map((row) => row.getAttribute("data-entity-id")));
  expect(answer.total).toBeGreaterThan(1);
  expect(shown.length).toBe(answer.total);
  await expect(picker.getByTestId("pager-position")).toContainText(`전체 ${shown.length}건`);
  // id 의 글자 코드 순 그대로다. 화면이 다시 정렬하지 않는다.
  expect(shown).toEqual([...shown].sort());
  // 후보는 표시 이름, 유형의 이름표, 키로 보인다.
  await expect(table.locator("tbody tr").first().getByTestId("entity-name")).not.toBeEmpty();
  await expect(picker.getByTestId("searched-as-of")).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
});

test("후보를 고르면 식별자를 적지 않고 그 개체의 관계 지도와 목록이 열린다", async ({ page }) => {
  await page.goto("/relations");
  const picker = await find(page, "방문지원");

  const row = picker.getByRole("table", { name: "찾은 개체" }).locator("tbody tr");
  await expect(row).toHaveCount(1);
  const entityId = await row.getAttribute("data-entity-id");
  await row.getByRole("button", { name: "고르기" }).click();

  // 개체 id 의 칸이 고른 개체로 채워지고, 조회 단추를 누른 것처럼 열린다.
  await expect(page.getByLabel("개체 id")).toHaveValue(entityId ?? "");
  await expect(page.getByTestId("answered-as-of")).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
});

test("맞는 개체가 없으면 없다고 말한다", async ({ page }) => {
  await page.goto("/relations");
  const picker = await find(page, "어디에도 없는 글자");

  await expect(picker.getByText("맞는 개체가 없습니다")).toBeVisible();
  await expect(picker.getByRole("table", { name: "찾은 개체" })).toHaveCount(0);
});

test("조회 시점을 적으면 그 날짜의 값으로 찾는다", async ({ page }) => {
  await page.goto("/relations");
  // 자료가 들어오기 전의 날짜: 이름으로는 찾지 못한다.
  await page.getByLabel("조회 시점").fill("2025-12-31");
  const picker = await find(page, "방문지원");

  await expect(picker.getByTestId("searched-as-of")).toHaveText("2025-12-31");
  await expect(picker.getByText("맞는 개체가 없습니다")).toBeVisible();
});

test("찾지 못했을 때는 까닭을 알리고 다시 찾을 수 있다", async ({ page }) => {
  await page.goto("/relations");
  await page.route("**/api/v1/entities?*", (route) => route.abort(), { times: 1 });
  const picker = await find(page, "지원");

  await expect(picker.getByRole("alert")).toBeVisible();
  await picker.getByRole("button", { name: "찾기" }).click();
  await expect(picker.getByRole("table", { name: "찾은 개체" })).toBeVisible();
});
