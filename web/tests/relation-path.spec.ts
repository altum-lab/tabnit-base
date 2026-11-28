import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// A-18 수용 기준: 두 개체를 고르면 조회 시점에 유효한 관계로 이은 가장 짧은 경로가 나온다. 없으면 없다고 말한다
// (D7 4절 "두 개체 사이의 경로", D2 AD-413).
//
// 화면은 서버(getRelationPath)가 준 걸음을 그 차례 그대로 보여 준다. 화면이 경로를 찾거나 고르지 않는다.
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData): 시험용 Pack 의 사본 e2e-actions.

const PACK = "e2e-actions";
const FROM = `${PACK}|운영지침|G-01`;
const TO = `${PACK}|전담팀|T-01`;

type Answer = {
  found: boolean;
  length: number | null;
  steps: { relation: { id: string; relationType: string }; from: { entityId: string }; to: { entityId: string } }[];
};

function finder(page: Page) {
  return page.getByRole("region", { name: "경로 찾기" });
}

async function find(page: Page, from: string, to: string, asOf?: string) {
  await page.goto("/relations");
  if (asOf) {
    await page.getByLabel("조회 시점").fill(asOf);
  }
  await finder(page).getByLabel("경로의 출발").fill(from);
  await finder(page).getByLabel("경로의 도착").fill(to);
  await finder(page).getByRole("button", { name: "경로 찾기" }).click();
}

test("두 개체의 가장 짧은 경로를 서버가 준 걸음의 차례 그대로 보여 준다", async ({ page, request }) => {
  await find(page, FROM, TO, "2026-02-01");

  const answer = (await (
    await request.get("/api/v1/relation-path", { params: { fromEntityId: FROM, toEntityId: TO, asOf: "2026-02-01" } })
  ).json()) as Answer;
  expect(answer.found).toBe(true);
  expect(answer.length).toBeGreaterThan(1);
  await expect(finder(page).getByTestId("path-as-of")).toHaveText("2026-02-01");
  await expect(finder(page).getByTestId("path-length")).toHaveText(String(answer.length));
  const steps = finder(page).getByRole("list", { name: "경로의 걸음" }).locator("li");
  expect(await steps.evaluateAll((items) => items.map((item) => item.getAttribute("data-relation-id")))).toEqual(
    answer.steps.map((step) => step.relation.id),
  );
  // 걸음마다 두 개체가 이름으로 보이고 관계 유형이 적힌다.
  await expect(steps.first().getByTestId("entity-name").first()).not.toBeEmpty();
  await expect(steps.first()).toContainText(answer.steps[0]!.relation.relationType);
  expect(await steps.first().getByTestId("entity").first().getAttribute("data-entity-id")).toBe(FROM);
  expect(await steps.last().getByTestId("entity").last().getAttribute("data-entity-id")).toBe(TO);
});

test("개체 찾기에서 고른 개체를 경로의 출발과 도착에 넣어 찾는다", async ({ page }) => {
  await page.goto("/relations");
  await page.getByLabel("조회 시점").fill("2026-02-01");
  const picker = page.getByRole("region", { name: "개체 찾기" });
  await picker.getByLabel("Pack").selectOption(PACK);
  await picker.getByLabel("검색어").fill("방문");
  await picker.getByRole("button", { name: "찾기" }).click();
  const rows = picker.getByRole("table", { name: "찾은 개체" }).locator("tbody tr");
  await expect(rows.first()).toBeVisible();

  await rows.filter({ hasText: "G-01" }).getByRole("button", { name: "출발로" }).click();
  await rows.filter({ hasText: "T-01" }).getByRole("button", { name: "도착으로" }).click();

  await expect(finder(page).getByLabel("경로의 출발")).toHaveValue(FROM);
  await expect(finder(page).getByLabel("경로의 도착")).toHaveValue(TO);
  await finder(page).getByRole("button", { name: "경로 찾기" }).click();
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toBeVisible();
});

test("그 조회 시점에 이어져 있지 않으면 유효한 경로가 없다고 말한다", async ({ page }) => {
  // 자료가 들어오기 전의 날짜: 개체는 있지만 유효한 관계가 없다.
  await find(page, FROM, TO, "2025-12-31");

  await expect(finder(page).getByRole("status")).toContainText("유효한 경로가 없습니다");
  await expect(finder(page).getByTestId("path-as-of")).toHaveText("2025-12-31");
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toHaveCount(0);
});

test("두 개체가 같으면 같다고 말한다", async ({ page }) => {
  await find(page, FROM, FROM, "2026-02-01");

  await expect(finder(page).getByTestId("path-length")).toHaveText("0");
  await expect(finder(page).getByRole("status")).toContainText("같은 개체");
});

test("한도에서 멈췄으면 없다고 하지 않고 한도 안에서 찾지 못했다고 말한다", async ({ page }) => {
  await page.route("**/api/v1/relation-path?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        asOf: "2026-02-01",
        fromEntityId: FROM,
        toEntityId: TO,
        found: false,
        limitReached: true,
        maxLength: 6,
        length: null,
        entities: [],
        steps: [],
      }),
    }),
  );
  await find(page, FROM, TO, "2026-02-01");

  await expect(finder(page).getByRole("status")).toContainText("한도 안에서 찾지 못했습니다");
  await expect(finder(page).getByRole("status")).toContainText("6");
  await expect(finder(page).getByRole("status")).not.toContainText("유효한 경로가 없습니다");
  // 한도는 둘이다 (경로의 길이, 찾은 개체의 수). 개체의 수에서 멈췄으면 더 짧은 경로도 있을 수 있다:
  // "더 긴 경로가 있을 수 있다"고만 말하지 않는다.
  await expect(finder(page).getByRole("status")).not.toContainText("더 긴 경로");
  await expect(finder(page).getByRole("status")).toContainText("일찍 멈춥니다");
});

test("서버가 거부하면 까닭을 보이고 다시 찾을 수 있다", async ({ page }) => {
  // 다른 Pack 의 두 개체.
  await find(page, FROM, "e2e|T|ga", "2026-02-01");

  await expect(finder(page).getByRole("alert")).toContainText("서로 다른 Pack");
  await finder(page).getByLabel("경로의 도착").fill(TO);
  await finder(page).getByRole("button", { name: "경로 찾기" }).click();
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toBeVisible();
  await expect(finder(page).getByRole("alert")).toHaveCount(0);
});

// ── 코드 검토(/code-review 122)가 짚은 것 ─────────────────────────────────────

test("조회 시점을 바꾸면 찾아 둔 경로를 그 날짜로 다시 받는다", async ({ page }) => {
  await find(page, FROM, TO, "2026-02-01");
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toBeVisible();

  // 자료가 들어오기 전의 날짜로 옮긴다: 그 날짜에는 유효한 관계가 없다.
  await page.getByLabel("조회 시점").fill("2025-12-31");
  await expect(finder(page).getByTestId("path-as-of")).toHaveText("2025-12-31");
  await expect(finder(page).getByRole("status")).toContainText("유효한 경로가 없습니다");
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toHaveCount(0);
});

test("출발이나 도착을 고치면 앞의 결과를 비운다", async ({ page }) => {
  await find(page, FROM, TO, "2026-02-01");
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toBeVisible();

  await finder(page).getByLabel("경로의 도착").fill(FROM);
  // 적힌 두 개체의 것이 아닌 결과가 남아 있지 않다.
  await expect(finder(page).getByRole("list", { name: "경로의 걸음" })).toHaveCount(0);
  await expect(finder(page).getByTestId("path-as-of")).toHaveCount(0);
});
