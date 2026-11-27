import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// A-19 수용 기준: 화면에서 개체 하나의 값이 조회 시점에 따라 달라지는 것이 보인다. 두 날짜에서 같은 속성의 값이 다르다
// (D7 4절 "속성 값의 시점 조회", D2 AD-420).
//
// 화면은 서버(getAttributeValuesAsOf)가 준 값과 차례를 그대로 보여 준다. 어느 값이 유효한지 가리지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): e2e-compare 는 같은 입력 자료의 판 셋을 올려 둔 Pack 이다
// (적용일 2026-01-01, 2026-03-01, 2026-05-01). 이 시험은 읽기만 한다.

const PACK_ID = "e2e-compare";

type Answer = {
  asOf: string;
  values: { attributeName: string; valueDisplay: string; validFrom: string; validTo: string | null }[];
};

type Comparison = { changes: { kind: string; primaryEntityId: string }[] };

function values(page: Page) {
  return page.getByRole("region", { name: "속성 값" });
}

async function answerOf(request: import("@playwright/test").APIRequestContext, entityId: string, asOf: string) {
  return (await (await request.get("/api/v1/attribute-values", { params: { entityId, asOf } })).json()) as Answer;
}

// 첫 판과 둘째 판 사이에 값이 바뀐 개체 하나. 두 판의 비교(C-16)로 찾는다.
async function changedEntity(request: import("@playwright/test").APIRequestContext) {
  const compared = (await (
    await request.get("/api/v1/edition-comparison", {
      params: { fromEditionId: `${PACK_ID}|guideline|1`, toEditionId: `${PACK_ID}|guideline|2` },
    })
  ).json()) as Comparison;
  const changed = compared.changes.find((change) => change.kind === "CHANGED");
  expect(changed).toBeTruthy();
  return changed!.primaryEntityId;
}

async function open(page: Page, entityId: string, asOf: string) {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill(entityId);
  await page.getByLabel("조회 시점").fill(asOf);
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(values(page).getByTestId("entity-values")).toHaveAttribute("data-as-of", asOf);
}

async function shown(page: Page) {
  return values(page)
    .locator("tbody tr")
    .evaluateAll((rows) =>
      rows.map((row) => ({
        attributeName: row.getAttribute("data-value-attribute"),
        valueDisplay: row.querySelector('[data-testid="value-display"]')?.textContent,
        validFrom: row.querySelector('[data-testid="value-from"]')?.textContent,
      })),
    );
}

test("같은 개체의 같은 속성이 두 조회 시점에서 다른 값으로 보인다", async ({ page, request }) => {
  const entityId = await changedEntity(request);
  const before = await answerOf(request, entityId, "2026-02-28");
  const after = await answerOf(request, entityId, "2026-03-01");
  // 서버의 답에서 두 날짜의 값이 다른 속성이 있어야 이 시험에 뜻이 있다.
  const differing = after.values.find((value) => before.values.find((old) => old.attributeName === value.attributeName)?.valueDisplay !== value.valueDisplay);
  expect(differing).toBeTruthy();

  // 바뀌기 전날: 서버가 준 값과 차례 그대로다.
  await open(page, entityId, "2026-02-28");
  expect(await shown(page)).toEqual(before.values.map((value) => ({ attributeName: value.attributeName, valueDisplay: value.valueDisplay, validFrom: value.validFrom })));

  // 바뀐 날: 같은 속성의 값이 달라진다.
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(values(page).getByTestId("entity-values")).toHaveAttribute("data-as-of", "2026-03-01");
  expect(await shown(page)).toEqual(after.values.map((value) => ({ attributeName: value.attributeName, valueDisplay: value.valueDisplay, validFrom: value.validFrom })));
  const row = values(page).locator(`[data-value-attribute="${differing!.attributeName}"]`);
  await expect(row.getByTestId("value-display")).toHaveText(differing!.valueDisplay);
  await expect(row.getByTestId("value-from")).toHaveText("2026-03-01");
  await expect(values(page).getByTestId("entity-values")).toHaveAttribute("data-entity-id", entityId);
});

test("그 조회 시점에 값이 없으면 없다고 알린다", async ({ page, request }) => {
  const entityId = await changedEntity(request);
  const answer = await answerOf(request, entityId, "2019-01-01");
  expect(answer.values).toEqual([]);

  await open(page, entityId, "2019-01-01");

  await expect(values(page).getByTestId("entity-values-empty")).toBeVisible();
  await expect(values(page).locator("table")).toHaveCount(0);
});

test("서버가 답하지 못하면 그 문구를 보이고 다시 불러올 수 있다", async ({ page, request }) => {
  const entityId = await changedEntity(request);
  await page.route("**/api/v1/attribute-values**", (route) =>
    route.fulfill({
      status: 500,
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "INTERNAL_ERROR", detail: "요청을 처리하지 못했습니다. 잠시 뒤 다시 시도하세요." }),
    }),
  );
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill(entityId);
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await expect(values(page).getByRole("alert")).toContainText("처리하지 못했습니다");
  // 지도와 관계 목록은 그대로 보인다.
  await expect(page.getByTestId("relation-map")).toBeVisible();

  await page.unroute("**/api/v1/attribute-values**");
  await values(page).getByRole("button", { name: "속성 값 다시 불러오기" }).click();
  await expect(values(page).getByTestId("entity-values")).toHaveAttribute("data-as-of", "2026-03-01");
});
