import { expect, test } from "@playwright/test";
import { E2E_USER } from "./fixtures";

// E-08 수용 기준: 새 판 투입부터 확정까지 화면 시험이 매일 밤 돈다.
//
// 담당자 한 사람이 화면만으로 하는 한 줄기다: 로그인하고, 판을 올리고, 그 변동의 조치로 가서 확정하고,
// 이력에 자기가 남은 것을 본다. 이 시험은 서버를 직접 부르지 않는다. 화면에 보이는 것으로만 확인한다.
//
// 자료는 서버의 시험용 실행이 넣는다 (server/api/src/test 의 E2eSeedData): 사용자와, 시험용 Pack 의 사본 e2e-changes.
// 올리는 파일의 내용은 지어낸 값이다. 띄워 둔 서버를 다시 써도 되도록, 내용은 실행마다 다르고 적용일은 화면이
// 보여 주는 "지금의 판의 적용일"보다 늦은 날짜로 고른다.

const PACK_ID = "e2e-changes";
const INPUT_SOURCE = "guideline";

function nextDay(date: string) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

function csv(title: string, cycle: number) {
  return Buffer.from(`지침번호,제목,점검주기일\nG-01,${title},${cycle}\n`, "utf-8");
}

test("담당자가 로그인해 새 판을 올리고 그 변동의 조치를 확정하면 이력에 남는다", async ({ page }) => {
  const run = Date.now();

  // 1) 로그인한다. 로그인하지 않으면 변동 감지 화면 대신 로그인 화면이 나온다.
  await page.goto("/changes");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await page.getByLabel("아이디").fill(E2E_USER.loginId);
  await page.getByLabel("비밀번호").fill(E2E_USER.password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByTestId("session-user")).toContainText(E2E_USER.loginId);

  // 2) 새 판을 올린다. 먼저 바탕이 될 판을 올리고, 점검 주기를 고친 판을 올린다.
  await page.getByLabel("Pack").selectOption(PACK_ID);
  await page.getByLabel("입력 자료").selectOption(INPUT_SOURCE);
  await expect(page.getByTestId("latest-edition")).toBeVisible();
  const latest = page.getByTestId("latest-effective-date");
  const first = (await latest.count()) > 0 ? nextDay((await latest.textContent()) ?? "") : "2026-01-01";
  await page.getByLabel("적용일").fill(first);
  await page.getByLabel("파일").setInputFiles({ name: "지침 처음.csv", mimeType: "text/csv", buffer: csv(`한 줄기 시험 ${run}`, 30) });
  await page.getByRole("button", { name: "올리기" }).click();
  await expect(page.getByTestId("outcome")).toContainText(`적용일 ${first}`);
  await expect(page.getByTestId("latest-effective-date")).toHaveText(first);

  const second = nextDay(first);
  await page.getByLabel("적용일").fill(second);
  await page.getByLabel("파일").setInputFiles({ name: "지침 고침.csv", mimeType: "text/csv", buffer: csv(`한 줄기 시험 ${run}`, 14) });
  await page.getByRole("button", { name: "올리기" }).click();
  await expect(page.getByTestId("outcome")).toContainText(`적용일 ${second}`);

  // 3) 바뀐 항목이 나오고, 그 변동이 조치까지 처리됐다.
  const changed = page.getByRole("table", { name: "바뀐 항목" }).locator('tbody tr[data-item-key="G-01"]');
  await expect(changed).toContainText("변경");
  await expect(changed).toContainText("처리됨");
  const changeEventId = (await changed.getAttribute("data-change-event-id")) ?? "";
  expect(changeEventId).not.toBe("");
  // 조치가 하나도 없으면 이 시험은 뜻이 없다.
  const count = changed.getByRole("link", { name: /건$/ });
  await expect(count).not.toHaveText("0건");

  // 4) 그 변동의 조치로 간다.
  await count.click();
  await expect(page).toHaveURL(/\/actions\?/);
  expect(new URL(page.url()).searchParams.get("changeEventId")).toBe(changeEventId);
  const table = page.getByRole("table", { name: "조치 목록" });
  await expect(table).toBeVisible();
  const row = table.locator("tbody tr[data-action-id]").first();
  await expect(row.getByTestId("status")).toHaveText("대기");
  const actionId = (await row.getAttribute("data-action-id")) ?? "";
  expect(actionId).not.toBe("");

  // 5) 근거를 보고 확정한다.
  await row.getByRole("button", { name: "근거와 이력 보기" }).click();
  const panel = page.locator("[data-detail-of]").filter({ has: page.getByRole("region", { name: "이력" }) }).first();
  await expect(panel).toBeVisible();
  await expect(panel.getByRole("region", { name: "이력" })).not.toContainText("불러오는 중");
  await panel.getByRole("button", { name: "확정" }).click();

  // 6) 상태가 확정으로 바뀌고, 이력에 누가 했는지가 로그인한 담당자로 남는다.
  await expect(row.getByTestId("status")).toHaveText("확정");
  const history = panel.getByRole("table", { name: "이력" });
  await expect(history.locator("tbody tr")).toHaveCount(1);
  await expect(history.locator("tbody tr").first()).toContainText("대기 → 확정");
  await expect(history.locator("tbody tr").first()).toContainText(E2E_USER.loginId);
  await expect(panel.getByRole("button", { name: "확정" })).toHaveCount(0);

  // 7) 화면을 새로 열어도 그대로다: 서버에 저장된 것이다.
  await page.reload();
  const again = page.locator(`tr[data-action-id="${actionId.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"]`);
  await expect(again.getByTestId("status")).toHaveText("확정");
  await again.getByRole("button", { name: "근거와 이력 보기" }).click();
  await expect(page.getByRole("table", { name: "이력" }).locator("tbody tr").first()).toContainText(E2E_USER.loginId);
});
