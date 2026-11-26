import { readFile } from "node:fs/promises";
import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-21 수용 기준: 영향 대상, 조치, 관계의 목록을 CSV 와 JSON 파일로 받는다. 파일의 행 수가 화면의 전체 건수와 같다
// (D7 "목록 내보내기", D2 AD-412).
//
// 화면의 전체 건수는 화면에 적힌 글("전체 N건")에서 읽고, 파일의 행은 이 시험이 센다.
// 자료는 서버의 시험용 실행이 넣은 지어낸 값이다 (server/api/src/test 의 E2eSeedData). 이 시험은 읽기만 한다.

const READ_PACK = "e2e-actions";

// CSV 를 읽는다 (RFC 4180). 맨 앞의 BOM 이 있어야 한다.
function csv(text: string) {
  expect(text.charCodeAt(0), "맨 앞에 BOM 이 있어야 한다").toBe(0xfeff);
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = "";
  let quoted = false;
  for (let i = 1; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (c === '"') {
        quoted = false;
      } else {
        cell += c;
      }
    } else if (c === '"') {
      quoted = true;
    } else if (c === ",") {
      row.push(cell);
      cell = "";
    } else if (c === "\r" && text[i + 1] === "\n") {
      row.push(cell);
      cell = "";
      rows.push(row);
      row = [];
      i++;
    } else {
      cell += c;
    }
  }
  expect(cell).toBe("");
  expect(row).toEqual([]);
  return rows;
}

// 단추를 눌러 받은 파일의 이름과 글.
async function download(page: Page, group: string, button: string) {
  const [file] = await Promise.all([
    page.waitForEvent("download"),
    page.getByRole("group", { name: group }).getByRole("button", { name: button }).click(),
  ]);
  const path = await file.path();
  return { name: file.suggestedFilename(), text: await readFile(path, "utf8") };
}

// 화면에 적힌 전체 건수.
async function shownTotal(page: Page) {
  const text = (await page.getByTestId("pager-position").first().textContent()) ?? "";
  const found = /전체 (\d+)건/.exec(text);
  expect(found, `전체 건수가 화면에 있어야 한다: ${text}`).toBeTruthy();
  return Number(found![1]);
}

async function changeWithTargets(request: APIRequestContext) {
  const response = await request.get("/api/v1/actions", { params: { packId: READ_PACK, limit: "200" } });
  const actions = (await response.json()).actions as { changeEventId: string; appliesTo: string }[];
  const found = actions.find((action) => action.appliesTo === "IMPACT_TARGET");
  expect(found, "영향 대상에 건 조치가 시험 자료에 있어야 한다").toBeTruthy();
  return found!.changeEventId;
}

test("관계 목록을 CSV 로 받으면 행의 수가 화면의 전체 건수와 같다 (한 쪽을 넘는 목록)", async ({ page }) => {
  // 관계가 60개인 개체: 화면은 50개씩 나눠 보인다.
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill("e2e|T|many");
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
  const total = await shownTotal(page);
  expect(total).toBeGreaterThan(50);

  const file = await download(page, "관계 목록 내보내기", "CSV");

  expect(file.name).toBe("relations-2026-03-01.csv");
  const rows = csv(file.text);
  expect(rows.length - 1).toBe(total);
  // 첫 쪽의 차례가 화면의 목록과 같다.
  const header = rows[0]!;
  const shown = await page
    .getByRole("table", { name: "관계 목록" })
    .locator("tbody tr")
    .evaluateAll((trs) => trs.map((tr) => tr.getAttribute("data-relation-id")));
  expect(rows.slice(1, 1 + shown.length).map((row) => row[header.indexOf("관계 id")])).toEqual(shown);
  expect(new Set(rows.slice(1).map((row) => row[header.indexOf("조회 시점")]))).toEqual(new Set(["2026-03-01"]));
});

test("관계 목록을 JSON 으로 받으면 전체가 담기고 조회 시점이 적힌다", async ({ page }) => {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill("e2e|T|many");
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
  const total = await shownTotal(page);

  const file = await download(page, "관계 목록 내보내기", "JSON");

  expect(file.name).toBe("relations-2026-03-01.json");
  const body = JSON.parse(file.text) as { asOf: string; total: number; relations: unknown[] };
  expect(body.asOf).toBe("2026-03-01");
  expect(body.total).toBe(total);
  expect(body.relations.length).toBe(total);
});

test("조치 목록을 받으면 행의 수가 화면의 전체 건수와 같고 거른 상태를 따른다", async ({ page }) => {
  await page.goto(`/actions?packId=${READ_PACK}&status=ALL`);
  await expect(page.getByTestId("pager-position").first()).toBeVisible();
  const total = await shownTotal(page);
  expect(total).toBeGreaterThan(0);

  const file = await download(page, "조치 목록 내보내기", "CSV");

  expect(file.name).toBe("actions.csv");
  const rows = csv(file.text);
  expect(rows.length - 1).toBe(total);
  expect(rows[0]).toContain("조치 id");
  expect(rows[0]).toContain("기한");

  // 대기인 조치만 거르면 파일도 그것만 담는다.
  await page.goto(`/actions?packId=${READ_PACK}&status=PENDING`);
  await expect(page.getByTestId("pager-position").first()).toBeVisible();
  const pending = await shownTotal(page);
  const filtered = csv((await download(page, "조치 목록 내보내기", "CSV")).text);
  expect(filtered.length - 1).toBe(pending);
  const status = filtered[0]!.indexOf("상태");
  expect(new Set(filtered.slice(1).map((row) => row[status]))).toEqual(new Set(["PENDING"]));
});

test("영향 대상을 받으면 행의 수가 화면의 영향 대상의 수와 같고 근거 경로가 적힌다", async ({ page, request }) => {
  const changeEventId = await changeWithTargets(request);
  await page.goto(`/impact?${new URLSearchParams({ changeEventId })}`);
  await expect(page.getByTestId("pager-position")).toBeVisible();
  const total = await shownTotal(page);
  expect(total).toBeGreaterThan(0);

  const file = await download(page, "영향 대상 내보내기", "CSV");

  expect(file.name).toMatch(/^impact-targets-\d{4}-\d{2}-\d{2}\.csv$/);
  const rows = csv(file.text);
  expect(rows.length - 1).toBe(total);
  const path = rows[0]!.indexOf("근거 경로");
  for (const row of rows.slice(1)) {
    expect(row[path]).not.toBe("");
  }
  const json = JSON.parse((await download(page, "영향 대상 내보내기", "JSON")).text) as { total: number; targets: unknown[] };
  expect(json.targets.length).toBe(total);
});

test("받지 못하면 서버가 알린 까닭을 보이고 다시 받을 수 있다", async ({ page }) => {
  await page.goto("/relations");
  await page.getByLabel("개체 id").fill("e2e|T|me");
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await page.getByRole("button", { name: "조회" }).click();
  await expect(page.getByRole("table", { name: "관계 목록" })).toBeVisible();
  await page.route(
    "**/api/v1/relation-exports?*",
    (route) =>
      route.fulfill({
        status: 422,
        contentType: "application/problem+json",
        body: JSON.stringify({
          type: "about:blank",
          title: "처리할 수 없는 요청",
          status: 422,
          code: "EXPORT_TOO_LARGE",
          detail: "내보낼 행이 한도를 넘습니다. 조건을 좁혀 다시 받으세요.",
        }),
      }),
    { times: 1 },
  );
  const group = page.getByRole("group", { name: "관계 목록 내보내기" });

  await group.getByRole("button", { name: "CSV" }).click();

  await expect(group.getByRole("alert")).toContainText("한도를 넘습니다");
  const file = await download(page, "관계 목록 내보내기", "CSV");
  expect(file.name).toBe("relations-2026-03-01.csv");
  await expect(group.getByRole("alert")).toHaveCount(0);
});
