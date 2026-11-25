import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-05 수용 기준: 새 판을 올리면 바뀐 항목이 화면에 나온다.
//
// 서버의 시험용 실행이 시험용 Pack 의 사본(e2e-changes)을 적재해 둔다 (server/api/src/test 의 E2eSeedData).
// 판은 이 시험이 화면으로 올린다. 파일의 내용은 지어낸 값이다.
//
// 띄워 둔 서버를 다시 써도 되도록 쓴다: 올리는 파일은 실행마다 내용이 다르고, 적용일은 화면이 보여 주는
// "지금의 판의 적용일"보다 늦은 날짜로 고른다.

const PACK_ID = "e2e-changes";
const INPUT_SOURCE = "guideline";
const HEADER = "지침번호,제목,점검주기일";

function csv(...rows: string[]) {
  return Buffer.from([HEADER, ...rows].join("\n") + "\n", "utf-8");
}

function nextDay(date: string) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

async function choose(page: Page) {
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  await page.getByLabel("입력 자료").selectOption(INPUT_SOURCE);
  await expect(page.getByTestId("latest-edition")).toBeVisible();
}

// 화면이 보여 주는 지금의 판의 적용일보다 하루 늦은 날짜. 아직 판이 없으면 정해 둔 첫 날짜.
async function laterDate(page: Page) {
  const latest = page.getByTestId("latest-effective-date");
  return (await latest.count()) > 0 ? nextDay((await latest.textContent()) ?? "") : "2026-01-01";
}

async function upload(page: Page, effectiveDate: string, name: string, content: Buffer) {
  await page.getByLabel("적용일").fill(effectiveDate);
  await page.getByLabel("파일").setInputFiles({ name, mimeType: "text/csv", buffer: content });
  await page.getByRole("button", { name: "올리기" }).click();
}

test("새 판을 올리면 바뀐 항목과 처리 결과와 조치의 수가 나온다", async ({ page }) => {
  const run = Date.now();
  await choose(page);

  // 먼저 한 판을 올린다. 다음 판과 견줄 바탕이다.
  const first = await laterDate(page);
  await upload(page, first, "지침 가.csv", csv(`G-01,방문 건강 관리 기준 ${run},30`, "G-02,감염병 대응 교육 기준,90"));
  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");
  await expect(page.getByTestId("outcome")).toContainText(`적용일 ${first}`);
  // 올리고 나면 지금의 판이 방금 올린 것으로 바뀌어 보인다.
  await expect(page.getByTestId("latest-effective-date")).toHaveText(first);

  // G-01 의 점검 주기를 고치고 G-02 를 뺀 판을 올린다.
  const second = nextDay(first);
  await upload(page, second, "지침 나.csv", csv(`G-01,방문 건강 관리 기준 ${run},14`));

  await expect(page.getByTestId("outcome")).toContainText(`적용일 ${second}`);
  const table = page.getByRole("table", { name: "바뀐 항목" });
  await expect(table).toBeVisible();
  // 서버가 준 순서 그대로다: 항목의 키의 글자 코드 순.
  const keys = await table.locator("tbody tr").evaluateAll((rows) => rows.map((row) => row.getAttribute("data-item-key")));
  expect(keys).toEqual(["G-01", "G-02"]);
  const changed = table.locator('tbody tr[data-item-key="G-01"]');
  await expect(changed).toContainText("변경");
  await expect(changed).toContainText(`${PACK_ID}|운영지침|G-01`);
  await expect(changed).toContainText("처리됨");
  const removed = table.locator('tbody tr[data-item-key="G-02"]');
  await expect(removed).toContainText("삭제");
  await expect(removed).toContainText("처리됨");

  // 조치의 수는 서버가 준 값 그대로다. 저장된 조치의 수와 같다 (화면이 세지 않는다).
  const changeEventId = await changed.getAttribute("data-change-event-id");
  expect(changeEventId).not.toBeNull();
  const actions = await page.request.get("/api/v1/actions", { params: { packId: PACK_ID, changeEventId: changeEventId ?? "" } });
  expect(actions.ok()).toBe(true);
  const total = (await actions.json()).total as number;
  await expect(changed.locator("td").last()).toHaveText(`${total}건`);
  // 바뀐 지침 자신에 거는 규칙이 있어 조치가 하나는 있다. 조치가 없는 채로 통과하지 않게 한다.
  expect(total).toBeGreaterThan(0);
});

test("내용이 앞 판과 같으면 새 판을 만들지 않았다고 알린다", async ({ page }) => {
  const run = Date.now();
  await choose(page);
  const first = await laterDate(page);
  const content = csv(`G-01,같은 내용 ${run},30`);
  await upload(page, first, "같은 내용.csv", content);
  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");

  await upload(page, nextDay(first), "같은 내용.csv", content);

  await expect(page.getByTestId("outcome")).toContainText("새 판을 만들지 않았습니다");
  await expect(page.getByRole("table", { name: "바뀐 항목" })).toHaveCount(0);
  // 지금의 판은 그대로다.
  await expect(page.getByTestId("latest-effective-date")).toHaveText(first);
});

test("받아들이지 않은 파일은 이유와 고칠 것을 알리고 화면이 멈추지 않는다", async ({ page }) => {
  await choose(page);
  const date = await laterDate(page);

  await upload(page, date, "틀린 파일.csv", Buffer.from("이런 열은 없다\n값\n", "utf-8"));

  const alert = page.getByRole("alert");
  await expect(alert).toContainText("열");
  await expect(alert).toContainText("다시 올리세요");
  await expect(page.getByTestId("outcome")).toHaveCount(0);
  // 고쳐서 다시 올릴 수 있다.
  await expect(page.getByRole("button", { name: "올리기" })).toBeEnabled();
});

test("건너뛴 행의 알림은 몇 번째 행의 무엇이 왜 걸렸는지와 할 일을 한국어로 보여 준다", async ({ page }) => {
  await choose(page);
  const date = await laterDate(page);
  const run = Date.now();

  // 둘째 행은 받고, 셋째 행은 지침번호가 비었고, 넷째 행은 점검주기일이 수가 아니다.
  await upload(
    page,
    date,
    "알림이 있는 지침.csv",
    csv(`G-01,방문 건강 관리 기준 ${run},30`, `,번호가 없는 지침 ${run},30`, `G-77,주기가 틀린 지침 ${run},서른 날`),
  );

  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");
  const notices = page.getByRole("table", { name: "투입의 알림" }).locator("tbody tr");
  await expect(notices).toHaveCount(2);
  // 서버가 준 차례다: 행 번호 순.
  await expect(notices.nth(0)).toContainText("3번째 행을 건너뛰었습니다");
  await expect(notices.nth(0)).toContainText("지침번호");
  await expect(notices.nth(0)).toContainText("다시 올리세요");
  await expect(notices.nth(1)).toContainText("4번째 행을 건너뛰었습니다");
  await expect(notices.nth(1)).toContainText("점검주기일");
  await expect(notices.nth(1)).toContainText("서른 날");
  // 가르는 데 쓰는 종류와 코드도 함께 보인다.
  await expect(notices.nth(0)).toContainText("SKIPPED_ROW, BLANK_KEY");
});

test("고른 Pack 이나 입력 자료를 바꾸면 앞의 결과를 지운다", async ({ page }) => {
  const run = Date.now();
  await choose(page);
  await upload(page, await laterDate(page), "지침.csv", csv(`G-01,바꾸기 전 ${run},30`));
  await expect(page.getByTestId("outcome")).toContainText(`${PACK_ID} 의 ${INPUT_SOURCE}`);

  // 다른 입력 자료를 고르면 앞의 결과가 새로 고른 것의 결과처럼 남지 않는다.
  await page.getByLabel("입력 자료").selectOption("program");
  await expect(page.getByTestId("outcome")).toHaveCount(0);
  await expect(page.getByRole("table", { name: "바뀐 항목" })).toHaveCount(0);
});

test("판을 올린 뒤 목록을 다시 받지 못해도 폼과 결과가 남고 그 사실을 알린다", async ({ page }) => {
  const run = Date.now();
  await choose(page);
  const date = await laterDate(page);
  // 올린 뒤의 목록 요청만 끊는다.
  await page.route("**/api/v1/packs", (route) => route.abort());

  await upload(page, date, "지침.csv", csv(`G-01,목록이 끊긴 채 ${run},30`));

  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");
  await expect(page.getByRole("alert")).toContainText("옛 값일 수 있습니다");
  await expect(page.getByLabel("Pack")).toHaveValue(PACK_ID);
  // 다시 불러오면 지금의 판이 맞게 보이고 알림이 사라진다.
  await page.unroute("**/api/v1/packs");
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByTestId("latest-effective-date")).toHaveText(date);
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("Pack 의 목록을 받지 못하면 이유를 알리고 다시 불러올 수 있다", async ({ page }) => {
  // 서버에 닿지 못한 것처럼 한 번 끊는다.
  await page.route("**/api/v1/packs", (route) => route.abort());
  await page.goto("/changes");

  await expect(page.getByRole("alert")).toContainText("서버에 연결하지 못했습니다");

  await page.unroute("**/api/v1/packs");
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByLabel("Pack")).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});

test("화면을 주소로 오가고 없는 주소는 없는 화면이라고 알린다", async ({ page }) => {
  await page.goto("/");
  // 첫 주소는 변동 감지로 간다.
  await expect(page).toHaveURL(/\/changes$/);
  await expect(page.getByRole("heading", { name: "변동 감지" })).toBeVisible();

  await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "관계 지도" }).click();
  await expect(page).toHaveURL(/\/relations$/);
  await expect(page.getByRole("heading", { name: "관계 지도" })).toBeVisible();

  // 주소를 직접 열어도 (새로 고쳐도) 그 화면이 뜬다.
  await page.reload();
  await expect(page.getByRole("heading", { name: "관계 지도" })).toBeVisible();

  await page.goto("/no-such-screen");
  await expect(page.getByRole("heading", { name: "없는 화면입니다" })).toBeVisible();
});

test("처리된 변동에서 그 변동의 영향 범위 화면으로 이어 간다", async ({ page }) => {
  const run = Date.now();
  await choose(page);
  const first = await laterDate(page);
  await upload(page, first, "지침 다.csv", csv(`G-01,방문 건강 관리 기준 ${run},30`));
  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");
  // G-01 의 점검 주기를 고친 판을 올린다: 변동 하나가 생기고 처리된다.
  await upload(page, nextDay(first), "지침 라.csv", csv(`G-01,방문 건강 관리 기준 ${run},7`));
  const row = page.locator('tr[data-item-key="G-01"]');
  await expect(row).toContainText("처리됨");
  const changeEventId = await row.getAttribute("data-change-event-id");

  await row.getByRole("link", { name: "영향 범위 보기" }).click();

  await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
  expect(new URL(page.url()).searchParams.get("changeEventId")).toBe(changeEventId);
  // 수는 서버가 준 값이다.
  const answer = await (await page.request.get("/api/v1/impact-scopes", { params: { changeEventId: changeEventId! } })).json();
  await expect(page.getByTestId("impact-total")).toHaveText(String(answer.total));
});

// C-07 수용 기준: 개정 조항 표를 넣으면 이전 조문과 비교된다 (D2 AD-367).
test("바뀐 속성마다 이전 값과 새 값이 서버가 준 그대로 나온다", async ({ page }) => {
  const run = Date.now();
  await choose(page);
  const first = await laterDate(page);
  // 제목은 지어낸 조문이다. 줄이 둘이고 쉼표가 있다.
  const before = `방문 건강 관리 기준 ${run}\n다만, 분기마다 점검한다`;
  const after = `방문 건강 관리 기준 ${run}\n다만, 달마다 점검한다`;
  await upload(page, first, "지침 마.csv", csv(`G-01,"${before}",30`, `G-02,감염병 대응 교육 기준 ${run},90`));
  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");

  // G-01 은 제목과 점검 주기를 고치고, G-02 는 빼고, G-03 을 더한다.
  const answered = page.waitForResponse((response) => response.url().includes("/api/v1/editions") && response.request().method() === "POST");
  await upload(page, nextDay(first), "지침 바.csv", csv(`G-01,"${after}",14`, `G-03,새 기준 ${run},60`));
  const body = (await (await answered).json()) as {
    changes: {
      itemKey: string;
      primaryEntity: { entityId: string; entityKey: string; entityTypeLabel: string; displayName: string | null };
      targetEntities: { entityId: string; entityKey: string; displayName: string | null }[];
      changedValues:
        | {
            attributeName: string;
            attributeLabel: string;
            before: string | null;
            after: string | null;
            beforeDisplay: string | null;
            afterDisplay: string | null;
            entity: { entityKey: string; displayName: string | null };
          }[]
        | null;
    }[];
  };

  for (const change of body.changes) {
    const cell = page.locator(`tr[data-item-key="${change.itemKey}"]`).getByTestId("changed-values");
    const items = cell.locator("li");
    expect(change.changedValues, "방금 올린 두 판 사이의 변동은 이전 값을 안다").not.toBeNull();
    await expect(items).toHaveCount(change.changedValues!.length);
    // 서버의 차례와 값 그대로다.
    for (const [index, value] of change.changedValues!.entries()) {
      const item = items.nth(index);
      await expect(item).toHaveAttribute("data-attribute-name", value.attributeName);
      await expect(item).toContainText(value.attributeLabel);
      // 보이는 글자는 서버가 준 것이다. 참·거짓이 아닌 속성은 저장한 글자와 같다.
      expect(value.beforeDisplay).toBe(value.before);
      await expect(item.getByTestId("value-before")).toHaveText(value.beforeDisplay ?? "(없음)");
      await expect(item.getByTestId("value-after")).toHaveText(value.afterDisplay ?? "(없음)");
      await expect(item.getByTestId("value-entity")).toHaveText(`(${value.entity.displayName ?? value.entity.entityKey})`);
    }
    // D-15: 개체는 식별자가 아니라 서버가 준 표시 이름과 유형의 이름표와 키로 보인다. 표시 이름이 없으면 키다.
    const row = page.locator(`tr[data-item-key="${change.itemKey}"]`);
    const primary = row.getByTestId("primary-entity");
    await expect(primary.getByTestId("entity-name")).toHaveText(change.primaryEntity.displayName ?? change.primaryEntity.entityKey);
    await expect(primary.getByTestId("entity-type")).toHaveText(`(${change.primaryEntity.entityTypeLabel} ${change.primaryEntity.entityKey})`);
    const targets = row.getByTestId("target-entities").getByTestId("entity");
    await expect(targets).toHaveCount(change.targetEntities.length);
    for (const [index, target] of change.targetEntities.entries()) {
      await expect(targets.nth(index)).toHaveAttribute("data-entity-id", target.entityId);
      await expect(targets.nth(index).getByTestId("entity-name")).toHaveText(target.displayName ?? target.entityKey);
    }
  }
  // 고친 행의 표시 이름은 새 판의 제목이고, 뺀 행은 그 판의 적용일에 제목이 없어 키로 보인다.
  const names = new Map(body.changes.map((change) => [change.itemKey, change.primaryEntity.displayName]));
  expect(names.get("G-01")).toBe(after);
  expect(names.get("G-02")).toBeNull();
  // 서버가 준 것이 기대한 변동인지도 본다: 고친 행은 두 속성, 뺀 행은 새 값이 없고, 더한 행은 이전 값이 없다.
  const byKey = new Map(body.changes.map((change) => [change.itemKey, change.changedValues!]));
  expect(byKey.get("G-01")!.map((value) => [value.before, value.after])).toEqual(expect.arrayContaining([[before, after], ["30", "14"]]));
  expect(byKey.get("G-02")!.every((value) => value.after === null)).toBe(true);
  expect(byKey.get("G-03")!.every((value) => value.before === null)).toBe(true);
  // 긴 글의 줄바꿈이 화면에서도 줄바꿈으로 보인다.
  const title = page.locator('tr[data-item-key="G-01"]').getByTestId("value-after").filter({ hasText: "달마다" });
  expect(await title.evaluate((element) => getComputedStyle(element).whiteSpace)).toBe("pre-wrap");
});


// D-15: 참·거짓 속성의 바뀐 값은 저장한 글자(true, false)가 아니라 서버가 준 보일 글자(예, 아니오)로 나온다 (D2 AD-381).
test("참·거짓 속성의 바뀐 값은 예와 아니오로 보인다", async ({ page }) => {
  const run = Date.now();
  const header = "사업코드,사업명,운영여부,연간목표인원,연계사업코드";
  const program = (flag: string) => Buffer.from(`${header}\nP-${run},지어낸 사업 ${run},${flag},10,\n`, "utf-8");
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  await page.getByLabel("입력 자료").selectOption("program");
  await expect(page.getByTestId("latest-edition")).toBeVisible();
  const first = await laterDate(page);
  await upload(page, first, "사업 가.csv", program("아니오"));
  await expect(page.getByTestId("outcome")).toContainText("새 판을 받아들였습니다");

  const answered = page.waitForResponse((response) => response.url().includes("/api/v1/editions") && response.request().method() === "POST");
  await upload(page, nextDay(first), "사업 나.csv", program("예"));
  const body = (await (await answered).json()) as {
    changes: { itemKey: string; changedValues: { attributeName: string; before: string | null; after: string | null }[] }[];
  };

  const flag = page.locator(`tr[data-item-key="P-${run}"]`).locator('li[data-attribute-name="운영여부"]');
  await expect(flag.getByTestId("value-before")).toHaveText("아니오");
  await expect(flag.getByTestId("value-after")).toHaveText("예");
  // 저장한 글자는 그대로 온다. 화면은 그것을 바꿔 적지 않고 서버가 준 보일 글자를 쓴다.
  const stored = body.changes.find((change) => change.itemKey === `P-${run}`)!.changedValues.find((value) => value.attributeName === "운영여부")!;
  expect([stored.before, stored.after]).toEqual(["false", "true"]);
});
