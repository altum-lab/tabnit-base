import { expect as baseExpect, test as baseTest, type Page } from "@playwright/test";
import { apiAs, E2E_OTHER_USER, expect, login, test } from "./fixtures";

// E-23 수용 기준: 관리자가 적재된 Pack 의 검증 질문을 돌려 질문마다의 통과와 실패를 본다. 자료를 바꾸지 않는다.
// 담당자에게는 열리지 않는다 (D7 10절 "검증 질문을 화면에서 돌린다", D2 AD-422).
//
// 화면은 서버(getVerificationResults)가 준 통과와 실패, 수, 차례를 그대로 보여 준다. 견주지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): e2e-verified 는 서버의 Pack 폴더에 놓이고 제 시나리오의 판만
// 차례대로 올린 Pack 이다. e2e-compare 는 서버의 Pack 폴더에 없는 Pack, e2e-loadable 은 판을 올리지 않은 Pack 이다.
// 이 시험은 읽기만 한다.

type Answer = {
  available: boolean;
  scenarios: {
    scenarioId: string;
    matched: boolean;
    questionCount: number;
    passedCount: number;
    results: { id: string; passed: boolean; kindLabel: string; label: string }[];
  }[];
};

function runner(page: Page) {
  return page.getByRole("region", { name: "검증 질문 돌리기" });
}

async function openAsAdmin(page: Page, context: import("@playwright/test").BrowserContext, packId: string) {
  await login(context, E2E_OTHER_USER);
  await page.goto("/pack-definition");
  await page.getByLabel("Pack", { exact: true }).selectOption(packId);
  await baseExpect(runner(page)).toBeVisible();
}

baseTest("관리자가 검증 질문을 돌리면 질문마다의 통과와 실패가 서버가 준 그대로 보인다", async ({ page, context, baseURL }) => {
  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const answer = (await (await admin.get("/api/v1/verification-results", { params: { packId: "e2e-verified" } })).json()) as Answer;
  const before = await (await admin.get("/api/v1/editions", { params: { packId: "e2e-verified" } })).json();
  baseExpect(answer.available).toBe(true);
  const scenario = answer.scenarios[0]!;
  baseExpect(scenario.matched).toBe(true);
  baseExpect(scenario.results.length).toBeGreaterThan(10);
  // 시험용 Pack 의 질문은 명령줄 실행기에서 모두 통과한다. 판이 시나리오와 같은 이 Pack 에서도 같다.
  baseExpect(scenario.passedCount).toBe(scenario.questionCount);

  await openAsAdmin(page, context, "e2e-verified");
  await runner(page).getByRole("button", { name: "검증 질문 돌리기" }).click();

  const shown = runner(page).getByTestId("verification-scenario");
  await baseExpect(shown).toHaveAttribute("data-scenario-id", scenario.scenarioId);
  await baseExpect(shown).toHaveAttribute("data-matched", "true");
  await baseExpect(shown.getByTestId("verification-summary")).toContainText(`통과 ${scenario.passedCount} / ${scenario.questionCount}`);
  await baseExpect(shown.getByTestId("verification-summary")).toContainText("두 번 물은 결과가 같습니다");
  const rows = await shown
    .locator("tbody tr")
    .evaluateAll((items) => items.map((item) => ({ id: item.getAttribute("data-question-id"), passed: item.getAttribute("data-passed") })));
  baseExpect(rows).toEqual(scenario.results.map((result) => ({ id: result.id, passed: String(result.passed) })));

  // 돌려도 판이 그대로다.
  baseExpect(await (await admin.get("/api/v1/editions", { params: { packId: "e2e-verified" } })).json()).toEqual(before);
  await admin.dispose();
});

baseTest("실패한 질문은 기대한 값과 실제 값을 함께 보인다", async ({ page, context }) => {
  // 서버의 답에서 질문 하나를 실패로 바꿔 준다: 화면이 서버가 준 실패를 그대로 보이는지를 본다.
  await page.route("**/api/v1/verification-results**", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { scenarios: { passedCount: number; results: Record<string, unknown>[] }[] };
    const scenario = body.scenarios[0]!;
    scenario.results[0] = { ...scenario.results[0]!, passed: false, expected: "[지어낸 기대]", actual: "[지어낸 실제]", reason: null };
    scenario.passedCount -= 1;
    await route.fulfill({ response, json: body });
  });
  await openAsAdmin(page, context, "e2e-verified");
  await runner(page).getByRole("button", { name: "검증 질문 돌리기" }).click();

  const first = runner(page).locator("tbody tr").first();
  await baseExpect(first).toHaveAttribute("data-passed", "false");
  await baseExpect(first.getByTestId("question-expected")).toContainText("[지어낸 기대]");
  await baseExpect(first.getByTestId("question-actual")).toContainText("[지어낸 실제]");
});

baseTest("판이 시나리오와 다르거나 질문을 읽을 수 없으면 묻지 않고 까닭을 보인다", async ({ page, context, baseURL }) => {
  // 판을 올리지 않은 Pack: 적재만 해 둔다 (이미 적재돼 있으면 바뀌지 않는다). 시나리오의 첫 단계부터 없다.
  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  baseExpect((await admin.post("/api/v1/pack-loads", { data: { packId: "e2e-loadable" } })).status()).toBe(200);
  await admin.dispose();

  await openAsAdmin(page, context, "e2e-loadable");
  await runner(page).getByRole("button", { name: "검증 질문 돌리기" }).click();
  await baseExpect(runner(page).getByTestId("verification-mismatch")).toContainText("1단계");
  await baseExpect(runner(page).locator("table")).toHaveCount(0);

  // 서버의 Pack 폴더에 없는 Pack. Pack 을 바꾸면 앞의 결과가 비워진다.
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-compare");
  await baseExpect(runner(page).getByTestId("verification-mismatch")).toHaveCount(0);
  await runner(page).getByRole("button", { name: "검증 질문 돌리기" }).click();
  await baseExpect(runner(page).getByTestId("verification-unavailable")).toContainText("Pack 폴더");
  await baseExpect(runner(page).getByTestId("verification-scenario")).toHaveCount(0);
});

test("담당자에게는 이 칸이 보이지 않고 서버가 막는다", async ({ page, request }) => {
  await page.goto("/pack-definition");
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-verified");
  await expect(page.getByTestId("definition")).toHaveAttribute("data-pack-id", "e2e-verified");
  await expect(page.getByRole("region", { name: "검증 질문 돌리기" })).toHaveCount(0);

  const answer = await request.get("/api/v1/verification-results", { params: { packId: "e2e-verified" } });
  expect(answer.status()).toBe(403);
});
