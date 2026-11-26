import { expect, test } from "./fixtures";

// D-18 에 이어지는 일: 날짜 칸이 받는 범위가 서버가 받는 범위와 같다 (D7 2절: 0001-01-01 부터 9999-12-31 까지).
// 범위를 주지 않으면 브라우저가 연도를 여섯 자리까지 받아, 글쇠로 숫자를 이어 치면 70102년 같은 날짜가 된다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData). 이 시험은 읽기만 한다.

const PACK_ID = "e2e-actions";

async function expectServerRange(input: import("@playwright/test").Locator) {
  await expect(input).toHaveAttribute("type", "date");
  await expect(input).toHaveAttribute("min", "0001-01-01");
  await expect(input).toHaveAttribute("max", "9999-12-31");
}

test("변동 감지 화면의 날짜 칸 둘이 서버가 받는 범위만 받는다", async ({ page }) => {
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  await expectServerRange(page.locator("#effective-date"));
  await expectServerRange(page.getByRole("region", { name: "제약의 위반" }).getByLabel("조회 시점"));
});

test("관계 화면의 조회 시점 칸이 서버가 받는 범위만 받는다", async ({ page }) => {
  await page.goto("/relations");
  await expectServerRange(page.locator("#as-of"));
});

test("적용일을 글쇠로 이어 쳐도 연도가 네 자리를 넘지 않는다", async ({ page }) => {
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  const input = page.locator("#effective-date");
  // 범위가 없으면 이 글쇠가 여섯 자리 연도를 만든다.
  await input.focus();
  await input.pressSequentially("01021234567");
  const value = await input.inputValue();
  expect(value === "" || /^\d{4}-\d{2}-\d{2}$/.test(value), `칸의 값: ${value}`).toBe(true);
});
