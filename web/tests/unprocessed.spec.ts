import { expect as baseExpect, test as baseTest } from "@playwright/test";
import { E2E_OTHER_USER, expect, login, test } from "./fixtures";

// C-08 (둘째): 처리에 실패한 변동이 까닭과 함께 보이고, 관리자가 다시 처리한다 (D7 5절, D2 AD-321, AD-345).
//
// 서버의 시험용 실행이 규칙 하나를 일부러 틀리게 고친 Pack(e2e-failed)에 판을 올려, 실패한 변동을 남겨 둔다
// (server/api/src/test 의 E2eSeedData). 규칙을 고치지 않으므로 다시 처리해도 다시 실패한다:
// 띄워 둔 서버를 다시 써도 같은 결과가 나온다. 고친 뒤에 처리되는 것은 서버의 시험이 본다.

const FAILED_PACK = "e2e-failed";
const CLEAN_PACK = "e2e-actions";

test("담당자는 처리되지 않은 변동과 실패한 까닭을 보지만 다시 처리 단추는 없다", async ({ page }) => {
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(FAILED_PACK);

  const table = page.getByRole("table", { name: "처리되지 않은 변동" });
  await expect(table).toBeVisible();
  const rows = table.locator("tbody tr");
  expect(await rows.count()).toBeGreaterThan(0);
  // 어느 판의 어느 항목이 어느 단계에서 왜 실패했는지, 무엇을 하면 되는지가 한국어로 나온다.
  const first = rows.first();
  await expect(first).toContainText("guideline 2판, 적용일 2026-03-01");
  await expect(first).toContainText("실패 (규칙 실행)");
  await expect(first.locator("li[data-rule-no]").first()).toHaveAttribute("data-failure-code", "DUE_DATE_OUT_OF_RANGE");
  await expect(first).toContainText("규칙의 기한 일수를 고쳐 Pack 을 다시 적재하세요");
  // 화면의 목록은 서버가 준 것과 차례까지 같다.
  const answer = await (await page.request.get(`/api/v1/unprocessed-changes?packId=${FAILED_PACK}`)).json();
  expect(await rows.evaluateAll((all) => all.map((row) => row.getAttribute("data-change-event-id")))).toEqual(
    answer.changes.map((change: { changeEventId: string }) => change.changeEventId),
  );

  await expect(page.getByRole("button", { name: "다시 처리" })).toHaveCount(0);
  await expect(page.getByTestId("reprocess-admin-only")).toBeVisible();
});

test("처리되지 않은 변동이 없는 Pack 은 없다고 알리고 Pack 을 고르기 전에는 목록이 없다", async ({ page }) => {
  await page.goto("/changes");
  await expect(page.getByTestId("unprocessed")).toHaveCount(0);

  await page.getByLabel("Pack").selectOption(CLEAN_PACK);

  await expect(page.getByTestId("unprocessed-empty")).toBeVisible();
  await expect(page.getByRole("button", { name: "다시 처리" })).toHaveCount(0);
});

test("화면이 모르는 코드와 대기인 변동과 규칙이 아닌 실패도 서버가 준 대로 보여 준다", async ({ page }) => {
  // 서버의 답을 지어낸다: 처음 보는 Pack 과 새 코드에서도 화면이 멈추거나 비지 않아야 한다.
  const processing = (status: string, failedStage: string | null, failureCode: string | null, failures: unknown[]) => ({
    status,
    failedStage,
    failureCode,
    failures,
    actionCount: 0,
  });
  const change = (key: string, body: unknown) => ({
    changeEventId: `p|s|2|${key}`,
    editionId: "p|s|2",
    inputSourceId: "s",
    editionNo: 2,
    effectiveDate: "2026-05-01",
    itemKey: key,
    kind: "CHANGED",
    primaryEntityId: `p|T|${key}`,
    processing: body,
  });
  await page.route("**/api/v1/unprocessed-changes?*", (route) =>
    route.fulfill({
      json: {
        packId: CLEAN_PACK,
        changes: [
          change("k1", processing("FAILED", "RULES", "RULE_FAILED", [
            { ruleNo: "0007", code: "지어낸_새_코드", entityId: "p|T|k1", attributeName: "속성", value: "값" },
            { ruleNo: "0009", code: "VALUE_FORMAT_MISMATCH", entityId: null, attributeName: null, value: null },
          ])),
          change("k2", processing("FAILED", "IMPACT", "지어낸_단계_코드", [])),
          change("k3", processing("PENDING", null, null, [])),
        ],
      },
    }),
  );
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(CLEAN_PACK);

  const rows = page.getByRole("table", { name: "처리되지 않은 변동" }).locator("tbody tr");
  await expect(rows).toHaveCount(3);
  // 모르는 코드는 코드를 그대로 적고 어디를 보면 되는지 알린다. 걸린 개체, 속성, 값은 서버가 준 것만 적는다.
  const items = rows.nth(0).locator("li");
  await expect(items).toHaveCount(2);
  await expect(items.nth(0)).toHaveText(
    "규칙 0007: 까닭의 코드: 지어낸_새_코드. 서버의 기록에서 까닭을 확인하세요. (개체 p|T|k1, 속성 속성, 값 값)",
  );
  await expect(items.nth(1)).toContainText("규칙 0009: 저장된 값이 속성의 형식과 맞지 않습니다.");
  await expect(rows.nth(1)).toContainText("실패 (영향 범위 계산). 까닭의 코드: 지어낸_단계_코드.");
  await expect(rows.nth(1).locator("li")).toHaveCount(0);
  await expect(rows.nth(2)).toContainText("대기 (아직 처리하지 못했습니다)");
});

test("처리되지 않은 변동을 받지 못하면 이유를 알리고 올리는 폼은 그대로 쓸 수 있다", async ({ page }) => {
  await page.route("**/api/v1/unprocessed-changes?*", (route) => route.abort());
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(CLEAN_PACK);

  const section = page.getByTestId("unprocessed");
  await expect(section.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");
  await expect(page.getByLabel("입력 자료")).toBeEnabled();
  await page.unroute("**/api/v1/unprocessed-changes?*");
  await section.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByTestId("unprocessed-empty")).toBeVisible();
});

test("담당자가 다시 처리를 직접 부르면 서버가 403 으로 막는다", async ({ request }) => {
  const response = await request.post("/api/v1/change-reprocessings", { data: { packId: FAILED_PACK } });

  expect(response.status()).toBe(403);
});

// 관리자로 로그인해야 하므로 함께 쓰는 준비(담당자로 로그인) 대신 여기서 로그인한다.
baseTest("관리자가 다시 처리하면 결과를 알리고 목록을 다시 받는다", async ({ page, context }) => {
  await login(context, E2E_OTHER_USER);
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(FAILED_PACK);
  const rows = page.getByRole("table", { name: "처리되지 않은 변동" }).locator("tbody tr");
  await baseExpect(rows.first()).toBeVisible();
  const before = await rows.count();

  await page.getByRole("button", { name: "다시 처리" }).click();

  // 규칙을 고치지 않았으므로 모두 다시 실패한다. 수는 서버가 준 것이다.
  await baseExpect(page.getByTestId("reprocess-result")).toHaveText(
    `다시 처리했습니다. 처리됨 0건, 다시 실패 ${before}건.`,
  );
  await baseExpect(rows).toHaveCount(before);
  await baseExpect(rows.first()).toContainText("실패 (규칙 실행)");
});

baseTest("다시 처리가 도는 동안 Pack 을 바꾸면 앞의 Pack 의 결과와 목록이 새 Pack 에 나오지 않는다", async ({ page, context }) => {
  await login(context, E2E_OTHER_USER);
  // 다시 처리의 답을 붙잡아 둔다. Pack 을 바꾼 뒤에 놓는다.
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/change-reprocessings", async (route) => {
    await held;
    await route.continue();
  });
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(FAILED_PACK);
  await page.getByRole("button", { name: "다시 처리" }).click();

  await page.getByLabel("Pack").selectOption(CLEAN_PACK);
  await baseExpect(page.getByTestId("unprocessed-empty")).toBeVisible();
  const answered = page.waitForResponse("**/api/v1/change-reprocessings");
  release();
  await answered;

  await baseExpect(page.getByTestId("unprocessed-empty")).toBeVisible();
  await baseExpect(page.getByTestId("reprocess-result")).toHaveCount(0);
  await baseExpect(page.getByRole("table", { name: "처리되지 않은 변동" })).toHaveCount(0);
});
