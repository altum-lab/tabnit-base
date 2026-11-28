import { expect as baseExpect, test as baseTest } from "@playwright/test";
import { apiAs, E2E_OTHER_USER, E2E_USER, expect, login, test } from "./fixtures";

// E-22 수용 기준: 관리자가 감사 기록을 누가, 언제, 무엇으로 걸러 본다. 담당자는 볼 수 없다
// (D7 9절 "감사 기록 보기", D2 AD-414).
//
// 화면은 서버(listAuditRecords)가 준 줄과 순서, 전체 수를 그대로 보여 준다. 다시 세거나 정렬하지 않는다.
// 사용자는 서버의 시험용 실행이 만들어 둔다 (E2eSeedData): e2e-admin 은 관리자, e2e-officer 는 담당자다.
// 다른 시험들이 같은 서버에 요청을 보내므로 수는 고정된 값과 견주지 않고 서버의 답과 견준다.

type Records = { total: number; records: { id: number; action: string; actor: string | null; outcome: string }[] };

// 관리자로 로그인해야 하므로 함께 쓰는 준비(담당자로 로그인) 대신 여기서 로그인한다.
baseTest("관리자에게는 감사 기록의 메뉴가 보이고 가장 최근의 줄부터 서버가 준 차례대로 나온다", async ({ page, context, baseURL }) => {
  await login(context, E2E_OTHER_USER);
  await page.goto("/");

  await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "감사 기록" }).click();

  const table = page.getByRole("table", { name: "감사 기록" });
  await baseExpect(table).toBeVisible();
  const shown = await table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => Number(row.getAttribute("data-record-id"))));
  baseExpect(shown.length).toBeGreaterThan(1);
  // 일련번호의 내림차순 그대로다.
  baseExpect(shown).toEqual([...shown].sort((left, right) => right - left));
  await baseExpect(page.getByTestId("pager-position")).toContainText(/전체 \d+건/);
  // 한 줄에 누가, 무엇을, 결과가 보인다.
  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const answer = (await (await admin.get("/api/v1/audit-records", { params: { limit: "200" } })).json()) as Records;
  await admin.dispose();
  const row = table.locator(`tbody tr[data-record-id="${shown[shown.length - 1]}"]`);
  const expected = answer.records.find((record) => record.id === shown[shown.length - 1]);
  baseExpect(expected, "화면의 줄이 서버의 답에 있어야 한다").toBeTruthy();
  await baseExpect(row).toContainText(expected!.action);
});

baseTest("누가와 무엇을과 결과로 거르면 서버가 거른 줄과 수가 그대로 나온다", async ({ page, context, baseURL }) => {
  await login(context, E2E_OTHER_USER);
  // 담당자가 Pack 의 목록을 한 번 본다: 그 사람의 줄이 생긴다.
  const officer = await apiAs(baseURL, E2E_USER);
  await officer.get("/api/v1/packs");
  await officer.dispose();
  await page.goto("/audit");
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toBeVisible();

  await page.getByLabel("누가").fill(E2E_USER.loginId);
  await page.getByLabel("무엇을").fill("listPacks");
  await page.getByLabel("결과").selectOption("SUCCESS");
  await page.getByRole("button", { name: "찾기" }).click();

  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const answer = (await (
    await admin.get("/api/v1/audit-records", { params: { actor: E2E_USER.loginId, action: "listPacks", outcome: "SUCCESS" } })
  ).json()) as Records;
  await admin.dispose();
  baseExpect(answer.total).toBeGreaterThan(0);
  await baseExpect(page.getByTestId("pager-position")).toContainText(`전체 ${answer.total}건`);
  const rows = page.getByRole("table", { name: "감사 기록" }).locator("tbody tr");
  baseExpect(await rows.evaluateAll((trs) => trs.map((tr) => Number(tr.getAttribute("data-record-id"))))).toEqual(
    answer.records.map((record) => record.id),
  );
  for (const row of await rows.all()) {
    await baseExpect(row).toContainText(E2E_USER.loginId);
    await baseExpect(row).toContainText("listPacks");
    await baseExpect(row).toContainText("성공");
  }
});

baseTest("날짜로 거르고 맞는 줄이 없으면 없다고 말한다", async ({ page, context }) => {
  await login(context, E2E_OTHER_USER);
  await page.goto("/audit");
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toBeVisible();

  // 아무 요청도 없었던 먼 옛날의 하루.
  await page.getByLabel("시작 날짜").fill("2001-01-01");
  await page.getByLabel("끝 날짜").fill("2001-01-01");
  await page.getByRole("button", { name: "찾기" }).click();

  await baseExpect(page.getByRole("status")).toContainText("맞는 감사 기록이 없습니다");
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toHaveCount(0);

  // 끝 날짜가 시작 날짜보다 앞서면 서버가 알린 까닭을 보인다.
  await page.getByLabel("끝 날짜").fill("2000-12-31");
  await page.getByRole("button", { name: "찾기" }).click();
  await baseExpect(page.getByRole("alert")).toContainText("끝 날짜가 시작 날짜보다 앞섭니다");
});

test("담당자에게는 감사 기록의 메뉴가 없고 주소로 열어도 볼 수 없으며 서버는 403 으로 막는다", async ({ page, request }) => {
  await page.goto("/");
  await expect(page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "감사 기록" })).toHaveCount(0);

  await page.goto("/audit");
  await expect(page.getByRole("alert")).toContainText("관리자만 볼 수 있습니다");
  await expect(page.getByRole("table", { name: "감사 기록" })).toHaveCount(0);

  const response = await request.get("/api/v1/audit-records");
  expect(response.status()).toBe(403);
});

baseTest("받지 못하면 까닭을 알리고 다시 받을 수 있다", async ({ page, context }) => {
  await login(context, E2E_OTHER_USER);
  // 서버에 닿지 못하게 막아 둔다 (화면이 처음에 몇 번 받든 모두 막는다).
  await page.route("**/api/v1/audit-records*", (route) => route.abort());
  await page.goto("/audit");

  await baseExpect(page.getByRole("alert")).toBeVisible();
  await page.unroute("**/api/v1/audit-records*");
  await page.getByRole("button", { name: "찾기" }).click();
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toBeVisible();
});

// /code-review 123 이 짚은 것.
baseTest("거르는 값의 앞뒤 빈칸은 떼고 찾고, 권한이 없어 막힌 시도를 찾는 법을 알린다", async ({ page, context, baseURL }) => {
  await login(context, E2E_OTHER_USER);
  const officer = await apiAs(baseURL, E2E_USER);
  await officer.get("/api/v1/packs");
  await officer.dispose();
  await page.goto("/audit");
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toBeVisible();

  // 붙여 넣다 딸려 온 빈칸.
  await page.getByLabel("누가").fill(` ${E2E_USER.loginId} `);
  await page.getByLabel("무엇을").fill("listPacks ");
  await page.getByRole("button", { name: "찾기" }).click();

  const rows = page.getByRole("table", { name: "감사 기록" }).locator("tbody tr");
  await baseExpect(rows.first()).toBeVisible();
  await baseExpect(page.getByText("맞는 감사 기록이 없습니다")).toHaveCount(0);
  // 칸에도 뗀 값이 보인다: 무엇으로 찾았는지가 화면과 같다.
  await baseExpect(page.getByLabel("누가")).toHaveValue(E2E_USER.loginId);

  // 역할이 모자라 막힌 시도는 그 기능의 이름이 아니라 accessDenied 로 남는다.
  await baseExpect(page.getByTestId("audit-filter-hint")).toContainText("accessDenied");
});

// /code-review 123 이 짚은 것 (D2 AD-424): 쪽을 넘기는 일 자체가 감사 기록을 더한다.
baseTest("쪽을 넘겨도 앞 쪽의 줄이 다시 나오지 않고 전체 수가 그대로다", async ({ page, context, baseURL }) => {
  await login(context, E2E_OTHER_USER);
  // 한 쪽(50줄)을 넘도록 줄을 만든다.
  const officer = await apiAs(baseURL, E2E_USER);
  for (let i = 0; i < 30; i++) {
    await officer.get("/api/v1/packs");
  }
  await officer.dispose();
  await page.goto("/audit");
  const rows = page.getByRole("table", { name: "감사 기록" }).locator("tbody tr");
  await baseExpect(rows.first()).toBeVisible();
  const ids = () => rows.evaluateAll((trs) => trs.map((tr) => Number(tr.getAttribute("data-record-id"))));
  const firstPage = await ids();
  const position = (await page.getByTestId("pager-position").innerText()).match(/전체 (\d+)건/)![1];

  await page.getByRole("button", { name: "다음" }).click();
  await baseExpect(page.getByTestId("pager-position")).toContainText(`${firstPage.length + 1}`);
  const secondPage = await ids();
  // 앞 쪽의 바로 아래에서 이어진다.
  baseExpect(secondPage.filter((id) => firstPage.includes(id))).toEqual([]);
  baseExpect(secondPage[0]).toBeLessThan(firstPage[firstPage.length - 1]!);
  await baseExpect(page.getByTestId("pager-position")).toContainText(`전체 ${position}건`);
  // 붙든 것을 알린다.
  await baseExpect(page.getByTestId("audit-pinned")).toContainText("찾은 때까지의 기록");

  // 다시 찾으면 그 사이에 남은 것까지 본다.
  await page.getByRole("button", { name: "찾기" }).click();
  await baseExpect(rows.first()).toBeVisible();
  baseExpect((await ids())[0]).toBeGreaterThan(firstPage[0]!);
});
