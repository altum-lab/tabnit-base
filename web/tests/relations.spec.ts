import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// SK-07 수용 기준: 화면 한 곳이 시점 조회 API 의 값(조회 시점과 관계 목록)을 보여 준다.
//
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData).
// 화면은 서버가 준 값과 순서를 그대로 보여 줘야 한다. 다시 세거나 정렬하지 않는다.

async function search(page: Page, entityId: string, asOf?: string) {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill(entityId);
  if (asOf) {
    await page.getByLabel("조회 시점").fill(asOf);
  }
  await page.getByRole("button", { name: "조회" }).click();
}

async function relationIds(page: Page) {
  const table = page.getByRole("table", { name: "관계 목록" });
  // 목록이 그려질 때까지 기다린 뒤에 읽는다. 기다리지 않으면 응답이 오기 전의 빈 화면을 읽는다.
  await expect(table).toBeVisible();
  return table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-relation-id")));
}

test("조회 시점에 유효한 관계와 답한 날짜를 보여 준다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByTestId("answered-as-of")).toHaveText("2026-03-01");
  // 2026-03-01 에는 R1 (03-01 ~ 06-01) 만 유효하다. 종료일 당일인 R3 와 아직 시작하지 않은 R2 는 없다.
  expect(await relationIds(page)).toEqual(["e2e|T|ga|T|na|R|src|1|2026-03-01"]);
  const row = page.getByRole("table", { name: "관계 목록" }).locator("tbody tr").first();
  await expect(row).toContainText("R");
  await expect(row).toContainText("e2e|T|ga");
  await expect(row).toContainText("e2e|T|na");
  await expect(row).toContainText("2026-03-01");
  await expect(row).toContainText("2026-06-01");
  await expect(row).toContainText("src");
});

test("조회 시점을 바꾸면 그 시점으로 다시 받아 온다", async ({ page }) => {
  await search(page, "e2e|T|ga", "2026-06-01");

  await expect(page.getByTestId("answered-as-of")).toHaveText("2026-06-01");
  expect(await relationIds(page)).toEqual(["e2e|T|ga|T|da|R|src|1|2026-06-01"]);
  // 종료일이 없는 관계는 종료일이 없다고 보여 준다.
  await expect(page.getByRole("table", { name: "관계 목록" }).locator("tbody tr").first()).toContainText(
    "종료일 없음",
  );
});

test("조회 시점을 비우면 서버가 정한 날짜로 답하고 그 날짜를 보여 준다", async ({ page }) => {
  await search(page, "e2e|T|me");

  // 화면이 오늘을 정하지 않는다. 서버가 답한 날짜를 그대로 보여 준다.
  await expect(page.getByTestId("answered-as-of")).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
});

test("관계 목록은 서버가 준 순서 그대로다", async ({ page }) => {
  await search(page, "e2e|T|me", "2026-03-01");

  // 서버의 순서: 관계 유형, 상대 개체의 id, 관계의 id.
  expect(await relationIds(page)).toEqual([
    "e2e|T|me|T|x|a-type|src|1|2020-01-01",
    "e2e|T|x|T|me|a-type|src|1|2020-01-01",
    "e2e|T|me|T|y|a-type|src|1|2020-01-01",
    "e2e|T|me|T|y|b-type|src|1|2020-01-01",
  ]);
});

test("유효한 관계가 없으면 없다고 말한다", async ({ page }) => {
  await search(page, "e2e|T|alone", "2026-03-01");

  await expect(page.getByTestId("answered-as-of")).toHaveText("2026-03-01");
  await expect(page.getByRole("status")).toContainText("유효한 관계가 없습니다");
  await expect(page.getByRole("table", { name: "관계 목록" })).toHaveCount(0);
});

test("없는 개체는 서버의 오류 문구를 보여 준다", async ({ page }) => {
  await search(page, "e2e|T|missing", "2026-03-01");

  await expect(page.getByRole("alert")).toContainText("개체를 찾을 수 없습니다");
  await expect(page.getByRole("alert")).toContainText("e2e|T|missing");
  await expect(page.getByRole("table", { name: "관계 목록" })).toHaveCount(0);
});

test("받아 오는 동안 대기 상태를 보여 준다", async ({ page }) => {
  await page.route("**/api/v1/relations**", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 800));
    await route.continue();
  });

  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("status")).toContainText("불러오는 중");
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
});

test("서버에 닿지 못하면 화면이 멈추지 않고 이유를 보여 준다", async ({ page }) => {
  await page.route("**/api/v1/relations**", (route) => route.abort("connectionrefused"));

  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  // 다시 조회할 수 있다.
  await expect(page.getByRole("button", { name: "조회" })).toBeEnabled();
});

test("앞단이 서버에 닿지 못했다고 답해도 연결 문제라고 알린다", async ({ page }) => {
  // 서버가 꺼져 있으면 앞단(개발 서버의 넘겨주기, 운영의 역방향 프록시)이 502 등으로 답한다.
  // 이 응답은 공통 오류 형식이 아니다.
  await page.route("**/api/v1/relations**", (route) =>
    route.fulfill({ status: 502, contentType: "text/html", body: "<html>Bad Gateway</html>" }),
  );

  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  await expect(page.getByRole("button", { name: "조회" })).toBeEnabled();
});

test("공통 오류 형식이 아닌 오류에는 내용을 그대로 보이지 않고 정해진 문구를 보여 준다", async ({ page }) => {
  await page.route("**/api/v1/relations**", (route) =>
    route.fulfill({ status: 500, contentType: "application/json", body: '{"trace":"java.lang.Exception at ..."}' }),
  );

  await search(page, "e2e|T|ga", "2026-03-01");

  await expect(page.getByRole("alert")).toContainText("요청을 처리하지 못했습니다");
  await expect(page.getByRole("alert")).not.toContainText("Exception");
});
