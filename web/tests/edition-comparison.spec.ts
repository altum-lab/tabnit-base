import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// C-16 수용 기준: 같은 입력 자료의 두 판을 골라 추가, 변경, 삭제된 항목과 값의 앞뒤를 본다 (D7 5절 "두 판의 비교",
// D2 AD-418).
//
// 화면은 서버(compareEditions)가 준 항목과 차례, 값을 그대로 보여 준다. 견주지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): e2e-compare 는 같은 입력 자료의 판 셋을 올려 둔 Pack 이다. 이 시험은 읽기만 한다.

const PACK_ID = "e2e-compare";

type Listed = { editions: { editionId: string; inputSourceId: string; inputSourceLabel: string; editionNo: number }[] };

type Comparison = {
  changes: { itemKey: string; kind: string; changedValues: { beforeDisplay: string | null; afterDisplay: string | null }[] | null }[];
};

const KIND_LABEL: Record<string, string> = { ADDED: "추가", CHANGED: "변경", REMOVED: "삭제" };

function comparer(page: Page) {
  return page.getByRole("region", { name: "두 판의 비교" });
}

// 판이 가장 많은 입력 자료의 가장 앞 판과 가장 뒤 판.
async function farthest(request: import("@playwright/test").APIRequestContext) {
  const listed = (await (await request.get("/api/v1/editions", { params: { packId: PACK_ID } })).json()) as Listed;
  const bySource = new Map<string, Listed["editions"]>();
  for (const edition of listed.editions) {
    bySource.set(edition.inputSourceId, [...(bySource.get(edition.inputSourceId) ?? []), edition]);
  }
  const groups = [...bySource.entries()].sort((a, b) => b[1].length - a[1].length || (a[0] < b[0] ? -1 : 1));
  const ordered = [...groups[0]![1]].sort((a, b) => a.editionNo - b.editionNo);
  expect(ordered.length).toBeGreaterThan(2);
  return { first: ordered[0]!, last: ordered[ordered.length - 1]!, listed };
}

async function open(page: Page) {
  await page.goto("/changes");
  await page.getByLabel("Pack", { exact: true }).selectOption(PACK_ID);
  await expect(comparer(page)).toBeVisible();
}

test("이웃하지 않은 두 판을 고르면 서버가 준 항목과 종류와 값의 앞뒤가 그 차례대로 보인다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  const answer = (await (
    await request.get("/api/v1/edition-comparison", { params: { fromEditionId: first.editionId, toEditionId: last.editionId } })
  ).json()) as Comparison;
  expect(answer.changes.length).toBeGreaterThan(0);

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();

  const result = comparer(page).getByTestId("comparison-result");
  await expect(result).toHaveAttribute("data-from-edition-id", first.editionId);
  await expect(result).toHaveAttribute("data-to-edition-id", last.editionId);
  await expect(result.getByRole("status")).toContainText(`바뀐 항목 ${answer.changes.length}건`);
  const rows = result.locator("tbody tr");
  await expect(rows).toHaveCount(answer.changes.length);
  for (const [index, change] of answer.changes.entries()) {
    const row = rows.nth(index);
    await expect(row).toHaveAttribute("data-compared-item-key", change.itemKey);
    await expect(row.getByTestId("compared-kind")).toHaveText(KIND_LABEL[change.kind] ?? change.kind);
    expect(change.changedValues).not.toBeNull();
    const befores = row.getByTestId("compared-before");
    await expect(befores).toHaveCount(change.changedValues!.length);
    for (const [at, value] of change.changedValues!.entries()) {
      await expect(befores.nth(at)).toHaveText(value.beforeDisplay ?? "(없음)");
      await expect(row.getByTestId("compared-after").nth(at)).toHaveText(value.afterDisplay ?? "(없음)");
    }
  }
});

test("뒤의 판은 앞의 판과 같은 입력 자료의 것만 고를 수 있고, 같은 판 둘은 바뀐 항목이 없다", async ({ page, request }) => {
  const { first, listed } = await farthest(request);
  const same = listed.editions.filter((edition) => edition.inputSourceId === first.inputSourceId).map((edition) => edition.editionId);
  expect(same.length).toBeLessThan(listed.editions.length);

  await open(page);
  const to = comparer(page).getByLabel("뒤의 판");
  await expect(to).toBeDisabled();
  await expect(comparer(page).getByRole("button", { name: "두 판 견주기" })).toBeDisabled();
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  // 고를 수 있는 것은 같은 입력 자료의 판이다. 차례는 지난 판의 목록의 차례다.
  const offered = await to.locator("option").evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value).filter((value) => value !== ""));
  expect(offered).toEqual(same);

  await to.selectOption(first.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();
  await expect(comparer(page).getByTestId("comparison-result").getByRole("status")).toContainText("바뀐 항목이 없습니다");
  await expect(comparer(page).getByTestId("comparison-result").locator("table")).toHaveCount(0);
});

test("견줘도 지난 판의 목록과 변동 이벤트의 수가 그대로다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  const before = await (await request.get("/api/v1/change-events", { params: { packId: PACK_ID } })).json();

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(last.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(first.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();
  // 뒤의 판을 앞에 골라도 적힌 방향 그대로 견준다.
  await expect(comparer(page).getByTestId("comparison-result")).toHaveAttribute("data-from-edition-id", last.editionId);

  expect(await (await request.get("/api/v1/change-events", { params: { packId: PACK_ID } })).json()).toEqual(before);
});

test("서버가 거부하면 그 문구를 보인다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  await page.route("**/api/v1/edition-comparison**", (route) =>
    route.fulfill({
      status: 404,
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "EDITION_NOT_FOUND", field: "toEditionId", detail: "뒤의 판이 없습니다. 판의 식별자를 확인하세요." }),
    }),
  );

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();

  await expect(comparer(page).getByRole("alert")).toContainText("뒤의 판이 없습니다");
});

// ── /code-review 가 짚은 것 (C-16 의 후속 수정) ─────────────────────────────

test("관계만 바뀐 항목은 바뀐 관계 유형과 변동 대상을 보이고, 값이 바뀌지 않았다고만 하지 않는다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  // 서버의 답의 첫 항목을 관계만 바뀐 것으로 바꿔 준다: 화면이 서버가 준 것을 버리지 않는지를 본다.
  await page.route("**/api/v1/edition-comparison**", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { changes: Record<string, unknown>[] };
    body.changes[0] = {
      ...body.changes[0]!,
      kind: "CHANGED",
      changedValues: [],
      changedAttributes: [],
      changedRelationTypes: ["지어낸관계", "다른관계"],
    };
    await route.fulfill({ response, json: body });
  });

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();

  const row = comparer(page).getByTestId("comparison-result").locator("tbody tr").first();
  await expect(row.getByTestId("compared-kind")).toHaveText("변경");
  await expect(row.getByTestId("compared-relation-types")).toContainText("지어낸관계");
  await expect(row.getByTestId("compared-relation-types")).toContainText("다른관계");
  // 변동 대상도 보인다 (서버가 준 수만큼).
  await expect(row.getByTestId("compared-targets").locator("[data-entity-id]")).not.toHaveCount(0);
  await expect(row).not.toContainText("속성 값은 바뀌지 않았습니다");
});

test("앞뒤의 값이 같은 것은 유효기간이 다르다고만 알린다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  await page.route("**/api/v1/edition-comparison**", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { changes: { changedValues: Record<string, unknown>[] | null }[] };
    const change = body.changes.find((candidate) => candidate.changedValues !== null && candidate.changedValues.length > 0)!;
    const value = change.changedValues![0]!;
    change.changedValues![0] = { ...value, before: "같은 값", after: "같은 값", beforeDisplay: "같은 값", afterDisplay: "같은 값" };
    await route.fulfill({ response, json: body });
  });

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();

  const note = comparer(page).getByTestId("same-value-note").first();
  await expect(note).toContainText("값은 같고 유효기간이 다릅니다");
  // 사이의 판에서 바뀌었다고 하지 않는다: 이웃한 두 판에서도 유효기간만 달라질 수 있다.
  await expect(note).not.toContainText("되돌아왔습니다");
});

test("고른 판을 바꾸면 앞의 결과를 비운다", async ({ page, request }) => {
  const { first, last, listed } = await farthest(request);
  const middle = listed.editions.find(
    (edition) => edition.inputSourceId === first.inputSourceId && edition.editionId !== first.editionId && edition.editionId !== last.editionId,
  )!;

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();
  await expect(comparer(page).getByTestId("comparison-result")).toBeVisible();

  // 뒤의 판을 바꾼다: 앞의 표가 새로 고른 것의 결과처럼 남지 않는다.
  await comparer(page).getByLabel("뒤의 판").selectOption(middle.editionId);
  await expect(comparer(page).getByTestId("comparison-result")).toHaveCount(0);

  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();
  await expect(comparer(page).getByTestId("comparison-result")).toHaveAttribute("data-to-edition-id", middle.editionId);
  // 앞의 판을 바꿔도 비운다.
  await comparer(page).getByLabel("앞의 판").selectOption(middle.editionId);
  await expect(comparer(page).getByTestId("comparison-result")).toHaveCount(0);
});

test("목록이 바뀌어 고른 판이 보이지 않게 되면 고른 것과 결과를 비우고 단추를 잠근다", async ({ page, request }) => {
  const { first, last } = await farthest(request);
  // 지난 판의 목록을 두 쪽으로 만든다: 첫 쪽은 서버가 준 판, 둘째 쪽은 판이 없다 (전체 수만 한 쪽을 넘긴다).
  await page.route("**/api/v1/editions?**", async (route) => {
    const url = new URL(route.request().url());
    const offset = Number(url.searchParams.get("offset") ?? "0");
    try {
      const response = await route.fetch();
      const body = (await response.json()) as { total: number; offset: number; limit: number; editions: unknown[] };
      await route.fulfill({
        response,
        json: offset === 0 ? { ...body, total: body.limit + 1 } : { ...body, total: body.limit + 1, offset, editions: [] },
      });
    } catch {
      // 화면이 이미 버린 요청이다 (같은 목록을 잇달아 청한다). 답할 곳이 없다.
    }
  });

  await open(page);
  await comparer(page).getByLabel("앞의 판").selectOption(first.editionId);
  await comparer(page).getByLabel("뒤의 판").selectOption(last.editionId);
  await comparer(page).getByRole("button", { name: "두 판 견주기" }).click();
  await expect(comparer(page).getByTestId("comparison-result")).toBeVisible();

  // 다음 쪽으로 넘긴다: 고른 두 판이 목록에 없다.
  await page.getByRole("region", { name: "지난 판" }).getByTestId("pager").getByRole("button").last().click();

  // 비교의 칸은 판이 있는 목록 아래에만 있다: 다시 첫 쪽으로 돌아오면 고른 것이 비워져 있다.
  await page.getByRole("region", { name: "지난 판" }).getByTestId("pager").getByRole("button").first().click();
  await expect(comparer(page).getByLabel("앞의 판")).toHaveValue("");
  await expect(comparer(page).getByLabel("뒤의 판")).toBeDisabled();
  await expect(comparer(page).getByRole("button", { name: "두 판 견주기" })).toBeDisabled();
  await expect(comparer(page).getByTestId("comparison-result")).toHaveCount(0);
});
