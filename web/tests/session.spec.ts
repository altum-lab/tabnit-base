import { expect, test } from "@playwright/test";
import { E2E_USER } from "./fixtures";

// E-02 수용 기준: 로그인하지 않으면 API 와 화면에 접근할 수 없다.
//
// 이 시험은 로그인하지 않은 채로 시작한다. 사용자는 서버의 시험용 실행이 만들어 둔다 (E2eSeedData).
// 틀린 로그인은 없는 아이디로 해 본다: 있는 아이디로 틀리면 띄워 둔 서버를 다시 쓸 때 그 아이디가 막힐 수 있다.

async function signIn(page: import("@playwright/test").Page, loginId: string, password: string) {
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}

test("로그인하지 않으면 어느 화면을 열어도 로그인 화면이 나오고 API 는 401 이다", async ({ page }) => {
  for (const path of ["/", "/changes", "/actions", "/relations", "/없는-화면"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "화면" })).toHaveCount(0);
    await expect(page.getByLabel("Pack")).toHaveCount(0);
  }
  for (const path of ["/api/v1/packs", "/api/v1/actions?packId=e2e-actions", "/api/v1/session"]) {
    const response = await page.request.get(path);
    expect(response.status()).toBe(401);
    expect(((await response.json()) as { code: string }).code).toBe("UNAUTHENTICATED");
  }
});

test("아이디나 비밀번호가 틀리면 서버의 문구가 나오고 로그인되지 않는다", async ({ page }) => {
  await page.goto("/actions");
  await signIn(page, "e2e-none", "틀린-화면-시험-비밀번호-0");

  await expect(page.getByRole("alert")).toContainText("로그인하지 못했습니다");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  // 비밀번호 칸은 비우고, 아이디는 남긴다.
  await expect(page.getByLabel("비밀번호")).toHaveValue("");
  await expect(page.getByLabel("아이디")).toHaveValue("e2e-none");
  expect((await page.request.get("/api/v1/packs")).status()).toBe(401);
});

test("로그인하면 열려던 화면이 나오고 누구로 들어왔는지 보이며 새로 고쳐도 유지된다", async ({ page }) => {
  await page.goto("/actions?packId=e2e-actions");
  await signIn(page, E2E_USER.loginId, E2E_USER.password);

  await expect(page.getByRole("heading", { name: "조치", exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/actions\?packId=e2e-actions/);
  await expect(page.getByTestId("session-user")).toHaveText(`${E2E_USER.loginId} (담당자)`);
  await expect(page.getByLabel("Pack")).toHaveValue("e2e-actions");

  await page.reload();
  await expect(page.getByTestId("session-user")).toHaveText(`${E2E_USER.loginId} (담당자)`);
  await expect(page.getByRole("heading", { name: "로그인" })).toHaveCount(0);
});

test("로그아웃하면 로그인 화면으로 돌아가고 API 는 다시 401 이며 다시 로그인할 수 있다", async ({ page }) => {
  await page.goto("/relations");
  await signIn(page, E2E_USER.loginId, E2E_USER.password);
  await expect(page.getByTestId("session-user")).toBeVisible();

  await page.getByRole("button", { name: "로그아웃" }).click();

  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await expect(page.getByTestId("session-user")).toHaveCount(0);
  expect((await page.request.get("/api/v1/packs")).status()).toBe(401);
  await signIn(page, E2E_USER.loginId, E2E_USER.password);
  await expect(page.getByTestId("session-user")).toBeVisible();
});

test("쓰는 도중에 세션이 끝나면 다음 요청에서 로그인 화면으로 돌아가고 까닭을 알린다", async ({ page, context }) => {
  await page.goto("/actions");
  await signIn(page, E2E_USER.loginId, E2E_USER.password);
  await expect(page.getByLabel("Pack")).toBeVisible();

  // 서버가 세션을 끝낸 것과 같게: 세션 쿠키를 없앤다.
  await context.clearCookies({ name: "JSESSIONID" });
  await page.getByLabel("Pack").selectOption("e2e-actions");

  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("다시 로그인");
});
