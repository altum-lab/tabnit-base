import { expect, test } from "./fixtures";

// D-16 수용 기준: 다음 파일을 올린 뒤에도, 화면을 떠났다 와도 지난 판의 바뀐 항목과 알림을 다시 본다 (D2 AD-383).
//
// 자료는 서버의 시험용 실행이 넣는다 (server/api/src/test 의 E2eSeedData): 시험용 Pack 의 사본 e2e-actions 에 그 시나리오의
// 판 여덟을 올려 둔 것. 이 시험은 그 Pack 을 읽기만 한다. 기대값은 같은 API 를 직접 불러 받은 답이다: 화면이 서버의 값을
// 그대로 보이는지를 본다.

const PACK_ID = "e2e-actions";

type Listed = {
  total: number;
  editions: { editionId: string; inputSourceId: string; inputSourceLabel: string; editionNo: number; effectiveDate: string; itemCount: number; changeCount: number; noticeCount: number }[];
};

test("지난 판이 받은 것이 늦은 것부터 서버가 준 수와 함께 나오고, 고른 판의 바뀐 항목을 다시 본다", async ({ page, request }) => {
  const listed = (await (await request.get("/api/v1/editions", { params: { packId: PACK_ID } })).json()) as Listed;
  expect(listed.total).toBeGreaterThan(1);

  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);

  // 목록: 차례와 수가 서버가 준 그대로다.
  const rows = page.getByRole("table", { name: "지난 판" }).locator("tbody tr");
  await expect(rows).toHaveCount(listed.editions.length);
  for (const [index, edition] of listed.editions.entries()) {
    const row = rows.nth(index);
    await expect(row).toHaveAttribute("data-edition-id", edition.editionId);
    // 입력 자료는 서버가 준 이름표로 보인다 (B-15). 식별자도 함께 있다.
    await expect(row.getByTestId("input-source-label")).toHaveText(edition.inputSourceLabel);
    await expect(row).toContainText(edition.inputSourceId);
    await expect(row).toContainText(`${edition.editionNo}판`);
    await expect(row).toContainText(edition.effectiveDate);
    await expect(row.getByTestId("item-count")).toHaveText(String(edition.itemCount));
    await expect(row.getByTestId("change-count")).toHaveText(String(edition.changeCount));
    await expect(row.getByTestId("notice-count")).toHaveText(String(edition.noticeCount));
  }
  await expect(page.getByTestId("pager-position")).toContainText(`전체 ${listed.total}건`);

  // 변동이 있는 판을 고른다. 맨 위의 판이 아닌 것으로: 다음 판을 올린 뒤에도 보인다는 것을 본다.
  const past = listed.editions.slice(1).find((edition) => edition.changeCount > 0);
  expect(past, "시험 자료에 맨 위가 아닌, 변동이 있는 판이 있어야 한다").toBeDefined();
  const stored = (await (await request.get("/api/v1/edition", { params: { editionId: past!.editionId } })).json()) as {
    changes: { changeEventId: string; itemKey: string; processing: { status: string; actionCount: number } }[];
    notices: unknown[];
  };
  // 단추의 이름에 어느 판의 것인지가 들어 있다.
  await page.getByRole("button", { name: `${past!.inputSourceLabel} ${past!.editionNo}판의 결과 보기` }).click();

  const result = page.getByRole("region", { name: "지난 판의 결과" });
  await expect(result.getByTestId("past-outcome")).toContainText(`${past!.editionNo}판`);
  await expect(result.getByTestId("past-outcome")).toContainText(`적용일 ${past!.effectiveDate}`);
  const changes = result.getByRole("table", { name: "바뀐 항목" }).locator("tbody tr");
  await expect(changes).toHaveCount(stored.changes.length);
  for (const [index, change] of stored.changes.entries()) {
    await expect(changes.nth(index)).toHaveAttribute("data-change-event-id", change.changeEventId);
    // 조치의 수는 지금 저장된 값이다.
    await expect(changes.nth(index).locator("td").last()).toContainText(`${change.processing.actionCount}건`);
  }
  // 고른 판은 주소에 있다.
  expect(new URL(page.url()).searchParams.get("editionId")).toBe(past!.editionId);
  expect(new URL(page.url()).searchParams.get("packId")).toBe(PACK_ID);

  // 그 변동의 영향 범위로 갔다가 돌아와도 같은 Pack 과 같은 판이 보인다.
  await changes.first().getByRole("link", { name: "영향 범위 보기" }).click();
  await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
  await page.goBack();
  await expect(page.getByLabel("Pack")).toHaveValue(PACK_ID);
  await expect(page.getByRole("region", { name: "지난 판의 결과" }).getByTestId("past-outcome")).toContainText(`${past!.editionNo}판`);
  await expect(page.locator(`tr[data-edition-id="${past!.editionId}"]`)).toHaveAttribute("aria-current", "true");

  // 닫으면 결과가 사라지고 주소에서도 빠진다.
  await page.locator(`tr[data-edition-id="${past!.editionId}"]`).getByRole("button", { name: "결과 닫기" }).click();
  await expect(page.getByRole("region", { name: "지난 판의 결과" })).toHaveCount(0);
  expect(new URL(page.url()).searchParams.get("editionId")).toBeNull();
});

test("판이 없는 Pack 은 없다고 말하고, 목록을 받지 못하면 까닭을 알리고 다시 불러올 수 있다", async ({ page }) => {
  let fail = true;
  await page.route("**/api/v1/editions?*", (route) => {
    if (route.request().method() !== "GET") {
      return route.fallback();
    }
    if (fail) {
      return route.fulfill({
        status: 500,
        contentType: "application/problem+json",
        json: { type: "about:blank", title: "서버 오류", status: 500, detail: "지어낸 실패입니다. 잠시 뒤 다시 하세요.", code: "INTERNAL" },
      });
    }
    return route.fulfill({ json: { packId: PACK_ID, total: 0, offset: 0, limit: 50, editions: [] } });
  });
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);

  const section = page.getByRole("region", { name: "지난 판" });
  await expect(section.getByRole("alert")).toContainText("지어낸 실패입니다");
  fail = false;
  await section.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(section.getByRole("status")).toContainText("아직 받아들인 판이 없습니다");
});

test("판이 한 쪽을 넘으면 나눠 받고, 서버가 준 전체 수와 자리를 그대로 보인다", async ({ page }) => {
  // 서버의 답을 지어낸다: 판 120개. 화면이 건너뛸 수를 바꿔 다시 묻는지, 수를 다시 세지 않는지를 본다.
  const asked: string[] = [];
  const edition = (index: number) => ({
    editionId: `p|s|${index}`,
    inputSourceId: "s",
    inputSourceLabel: "지어낸 자료",
    editionNo: index,
    effectiveDate: "2026-01-01",
    receivedAt: "2026-01-01T00:00:00Z",
    fileName: null,
    itemCount: index,
    changeCount: 0,
    noticeCount: 0,
  });
  await page.route("**/api/v1/editions?*", (route) => {
    if (route.request().method() !== "GET") {
      return route.fallback();
    }
    const offset = Number(new URL(route.request().url()).searchParams.get("offset") ?? 0);
    asked.push(String(offset));
    const editions = Array.from({ length: Math.min(50, 120 - offset) }, (_, i) => edition(120 - offset - i));
    return route.fulfill({ json: { packId: PACK_ID, total: 120, offset, limit: 50, editions } });
  });
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);

  const section = page.getByRole("region", { name: "지난 판" });
  const rows = section.getByRole("table", { name: "지난 판" }).locator("tbody tr");
  await expect(rows).toHaveCount(50);
  await expect(section.getByTestId("pager-position")).toHaveText("전체 120건 가운데 1~50번째");
  await expect(rows.first()).toHaveAttribute("data-edition-id", "p|s|120");
  // 파일의 이름이 없는 판은 없다고 적는다.
  await expect(rows.first()).toContainText("(이름 없음)");

  await section.getByRole("button", { name: "다음" }).click();
  await expect(section.getByTestId("pager-position")).toHaveText("전체 120건 가운데 51~100번째");
  await expect(rows.first()).toHaveAttribute("data-edition-id", "p|s|70");
  await section.getByRole("button", { name: "다음" }).click();
  await expect(rows).toHaveCount(20);
  await expect(section.getByTestId("pager-position")).toHaveText("전체 120건 가운데 101~120번째");
  await expect(section.getByRole("button", { name: "다음" })).toBeDisabled();
  // 건너뛸 수를 차례로 바꿔 물었다. 개발용 실행은 처음 한 번을 두 번 물을 수 있어 잇달아 같은 것은 하나로 본다.
  expect(asked.filter((offset, index) => index === 0 || asked[index - 1] !== offset)).toEqual(["0", "50", "100"]);
});
