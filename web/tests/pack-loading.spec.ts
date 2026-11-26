import { expect as baseExpect, test as baseTest, type Page } from "@playwright/test";
import { apiAs, E2E_OTHER_USER, expect, login, test } from "./fixtures";

// D-22 수용 기준: 관리자가 화면에서 서버에 둔 Pack 을 골라 적재한다. 틀린 Pack 은 문제를 모두 보이며 거부한다.
// 적재의 경고가 보인다. 담당자에게는 열리지 않는다 (D7 10절, D2 AD-416).
//
// 시험용 서버의 Pack 폴더에는 시험용 Pack 의 사본 셋이 놓여 있다 (E2eSeedData.preparePacksDirectory):
// e2e-loadable (문제없다), e2e-warning (경고가 하나 나온다), e2e-broken (검증에서 거부된다).
// 띄워 둔 서버를 다시 쓸 수 있다: 이미 적재된 것을 다시 적재하면 "바꾸지 않았다"로 답한다. 그래서 처음인지 다시인지를
// 고정해 기대하지 않고 서버의 답과 견준다.

function loader(page: Page) {
  return page.getByRole("region", { name: "Pack 적재" });
}

async function openAsAdmin(page: Page, context: import("@playwright/test").BrowserContext) {
  await login(context, E2E_OTHER_USER);
  await page.goto("/pack-definition");
  await baseExpect(loader(page).getByLabel("서버에 둔 Pack")).toBeVisible();
}

// 고르고 적재를 누른다. 이미 적재된 것이면 확인을 받으므로 한 번 더 누른다.
async function load(page: Page, packId: string) {
  await loader(page).getByLabel("서버에 둔 Pack").selectOption(packId);
  await loader(page).getByRole("button", { name: "적재", exact: true }).click();
  const confirm = loader(page).getByRole("button", { name: "다시 적재" });
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.click();
  }
}

baseTest("서버에 놓인 Pack 의 폴더가 서버가 준 이름과 순서 그대로 보인다", async ({ page, context, baseURL }) => {
  await openAsAdmin(page, context);

  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const answer = (await (await admin.get("/api/v1/pack-folders")).json()) as {
    configured: boolean;
    folders: { packId: string; loaded: boolean }[];
  };
  await admin.dispose();
  baseExpect(answer.configured).toBe(true);
  baseExpect(answer.folders.map((folder) => folder.packId)).toEqual(
    baseExpect.arrayContaining(["e2e-broken", "e2e-loadable", "e2e-warning"]),
  );
  const options = await loader(page)
    .getByLabel("서버에 둔 Pack")
    .locator("option[data-pack-id]")
    .evaluateAll((items) => items.map((item) => item.getAttribute("data-pack-id")));
  baseExpect(options).toEqual(answer.folders.map((folder) => folder.packId));
});

baseTest("문제없는 Pack 을 골라 적재하면 결과를 알리고 그 Pack 의 정의를 보인다", async ({ page, context }) => {
  await openAsAdmin(page, context);

  await load(page, "e2e-loadable");

  // 처음이면 적재했다, 다시면 바꾸지 않았다. 둘 다 적재된 것이다.
  await baseExpect(loader(page).getByTestId("load-result")).toContainText(/적재했습니다|바꾸지 않았습니다|정의를 바꿨습니다/);
  await baseExpect(loader(page).getByTestId("load-result")).toContainText("e2e-loadable");
  await baseExpect(loader(page).getByRole("list", { name: "적재의 경고" })).toHaveCount(0);
  await baseExpect(page.getByTestId("definition")).toHaveAttribute("data-pack-id", "e2e-loadable");
  // 적재된 것으로 표시가 바뀐다.
  await baseExpect(loader(page).locator('option[data-pack-id="e2e-loadable"]')).toHaveAttribute("data-loaded", "true");
});

baseTest("이미 적재된 Pack 을 다시 적재할 때는 한 번 확인을 받고 그만둘 수 있다", async ({ page, context }) => {
  await openAsAdmin(page, context);
  await load(page, "e2e-loadable");
  await baseExpect(loader(page).getByTestId("load-result")).toBeVisible();

  // 이제는 적재된 Pack 이다: 적재를 누르면 바로 보내지 않고 확인을 받는다.
  let sent = 0;
  await page.route("**/api/v1/pack-loads", (route) => {
    sent++;
    return route.continue();
  });
  await loader(page).getByLabel("서버에 둔 Pack").selectOption("e2e-loadable");
  await loader(page).getByRole("button", { name: "적재", exact: true }).click();
  await baseExpect(loader(page).getByTestId("reload-confirm")).toContainText("지금의 정의를 바꿉니다");
  baseExpect(sent).toBe(0);

  await loader(page).getByRole("button", { name: "그만두기" }).click();
  await baseExpect(loader(page).getByTestId("reload-confirm")).toHaveCount(0);
  baseExpect(sent).toBe(0);

  await loader(page).getByRole("button", { name: "적재", exact: true }).click();
  await loader(page).getByRole("button", { name: "다시 적재" }).click();
  await baseExpect(loader(page).getByTestId("load-result")).toContainText("바꾸지 않았습니다");
  baseExpect(sent).toBe(1);
});

baseTest("적재의 경고를 서버가 준 수와 문구 그대로 보인다", async ({ page, context, baseURL }) => {
  await openAsAdmin(page, context);

  await load(page, "e2e-warning");

  await baseExpect(loader(page).getByTestId("load-result")).toBeVisible();
  // 다시 적재해도 같은 경고가 나온다: 서버에 한 번 더 물어 견준다.
  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const answer = (await (await admin.post("/api/v1/pack-loads", { data: { packId: "e2e-warning" } })).json()) as {
    warnings: { file: string; item: string; message: string }[];
  };
  await admin.dispose();
  baseExpect(answer.warnings.length).toBeGreaterThan(0);
  const shown = loader(page).getByRole("list", { name: "적재의 경고" }).locator("li");
  await baseExpect(shown).toHaveCount(answer.warnings.length);
  await baseExpect(shown.first()).toContainText(answer.warnings[0]!.message);
  await baseExpect(shown.first()).toContainText(answer.warnings[0]!.item);
  await baseExpect(loader(page).getByTestId("warning-count")).toHaveText(String(answer.warnings.length));
});

baseTest("틀린 Pack 은 적재하지 않고 문제를 서버가 준 수만큼 모두 보인다", async ({ page, context, baseURL }) => {
  await openAsAdmin(page, context);

  await load(page, "e2e-broken");

  const admin = await apiAs(baseURL, E2E_OTHER_USER);
  const response = await admin.post("/api/v1/pack-loads", { data: { packId: "e2e-broken" } });
  baseExpect(response.status()).toBe(422);
  const answer = (await response.json()) as { problems: { file: string; item: string; message: string }[] };
  await admin.dispose();
  baseExpect(answer.problems.length).toBeGreaterThan(0);

  await baseExpect(loader(page).getByRole("alert")).toContainText("적재하지 않았습니다");
  const shown = loader(page).getByRole("list", { name: "Pack 의 문제" }).locator("li");
  // 화면에 보인 문제의 수가 API 의 답과 같다.
  await baseExpect(shown).toHaveCount(answer.problems.length);
  await baseExpect(loader(page).getByTestId("problem-count")).toHaveText(String(answer.problems.length));
  for (let i = 0; i < answer.problems.length; i++) {
    await baseExpect(shown.nth(i)).toContainText(answer.problems[i]!.message);
  }
  // 적재되지 않았다.
  await baseExpect(loader(page).locator('option[data-pack-id="e2e-broken"]')).toHaveAttribute("data-loaded", "false");
  await baseExpect(loader(page).getByTestId("load-result")).toHaveCount(0);
});

test("담당자에게는 Pack 적재의 칸이 없고 서버는 403 으로 막는다", async ({ page, request }) => {
  await page.goto("/pack-definition");
  await expect(page.getByRole("heading", { name: "Pack 정의" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Pack 적재" })).toHaveCount(0);

  expect((await request.get("/api/v1/pack-folders")).status()).toBe(403);
  expect((await request.post("/api/v1/pack-loads", { data: { packId: "e2e-loadable" } })).status()).toBe(403);
});

baseTest("서버에 Pack 폴더가 정해져 있지 않으면 설치 설정을 확인하라고 알린다", async ({ page, context }) => {
  await login(context, E2E_OTHER_USER);
  await page.route("**/api/v1/pack-folders", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ configured: false, folders: [] }) }),
  );
  await page.goto("/pack-definition");

  await baseExpect(loader(page)).toContainText("Pack 폴더가 정해져 있지 않습니다");
  await baseExpect(loader(page).getByRole("button", { name: "적재", exact: true })).toHaveCount(0);
});
