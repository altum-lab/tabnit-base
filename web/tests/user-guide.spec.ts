import { expect as baseExpect, test as baseTest, type Page } from "@playwright/test";
import { E2E_OTHER_USER, E2E_USER, login } from "./fixtures";

// E-25 수용 기준: 사용자취급설명서(docs/manual/user-guide.md)에 적힌 대로 따라 하면 된다.
//
// 설명서의 절을 차례대로 따라간다. 설명서가 이름을 적은 칸, 단추, 표가 화면에 그 이름으로 있는지와, 적힌 차례대로
// 눌렀을 때 적힌 결과가 나오는지를 본다. 화면의 이름을 바꾸면 이 시험이 깨진다: 그때 설명서도 함께 고친다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData). 올리는 파일의 내용은 지어낸 값이다.

const expect = baseExpect.configure({ soft: true });

function nextDay(date: string) {
  const next = new Date(`${date}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

function csv(title: string, cycle: number) {
  return Buffer.from(`지침번호,제목,점검주기일\nG-01,${title},${cycle}\n`, "utf-8");
}

// 메뉴로 화면을 옮기고, 그 화면이 뜬 것을 본 뒤에 다음을 한다 (앞 화면의 같은 이름의 칸을 건드리지 않게 한다).
async function go(page: Page, name: string) {
  await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name }).click();
  await baseExpect(page.getByRole("heading", { level: 1, name })).toBeVisible();
}

async function menu(page: Page) {
  return page.getByRole("navigation", { name: "화면" }).getByRole("link").allInnerTexts();
}

baseTest("담당자: 설명서 0절 ~ 6절을 차례대로 따라간다", async ({ page }) => {
  baseTest.setTimeout(120_000);
  const run = Date.now();

  // 0절. 로그인과 메뉴.
  await page.goto("/changes");
  await baseExpect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await page.getByLabel("아이디").fill(E2E_USER.loginId);
  await page.getByLabel("비밀번호").fill(E2E_USER.password);
  await page.getByRole("button", { name: "로그인" }).click();
  await baseExpect(page.getByTestId("session-user")).toContainText(E2E_USER.loginId);
  expect(await menu(page)).toEqual(["변동 감지", "영향 범위", "조치", "관계 지도", "Pack 정의"]);

  // 2절. 변동 감지: Pack, 입력 자료, 적용일, 파일, 올리기.
  await page.getByLabel("Pack").selectOption("e2e-changes");
  await page.getByLabel("입력 자료").selectOption("guideline");
  await baseExpect(page.getByTestId("latest-edition")).toBeVisible();
  const latest = page.getByTestId("latest-effective-date");
  const first = (await latest.count()) > 0 ? nextDay((await latest.textContent()) ?? "") : "2026-01-01";
  await page.getByLabel("적용일").fill(first);
  await page.getByLabel("파일").setInputFiles({ name: "지침 처음.csv", mimeType: "text/csv", buffer: csv(`설명서 따라가기 ${run}`, 30) });
  await page.getByRole("button", { name: "올리기" }).click();
  await baseExpect(page.getByTestId("outcome")).toContainText(`적용일 ${first}`);
  const second = nextDay(first);
  await page.getByLabel("적용일").fill(second);
  await page.getByLabel("파일").setInputFiles({ name: "지침 고침.csv", mimeType: "text/csv", buffer: csv(`설명서 따라가기 ${run}`, 14) });
  await page.getByRole("button", { name: "올리기" }).click();
  await baseExpect(page.getByTestId("outcome")).toContainText(`적용일 ${second}`);
  // 결과: 바뀐 항목. 변경은 무엇에서 무엇으로 바뀌었는지가 보인다.
  const changed = page.getByRole("table", { name: "바뀐 항목" }).locator('tbody tr[data-item-key="G-01"]');
  await baseExpect(changed).toContainText("변경");
  await expect(changed).toContainText("30");
  await expect(changed).toContainText("14");
  await expect(changed.getByRole("link", { name: "영향 범위 보기" })).toBeVisible();
  // 같은 화면의 다른 칸.
  for (const name of ["처리되지 않은 변동", "제약의 위반", "지난 판", "두 판의 비교", "변동 이벤트"]) {
    await expect(page.getByRole("region", { name, exact: true }), `변동 감지 화면의 칸: ${name}`).toBeVisible();
  }
  // 담당자에게는 다시 처리의 단추가 없다 (8절: 관리자의 일).
  await expect(page.getByRole("button", { name: "다시 처리" })).toHaveCount(0);

  // 3절. 영향 범위: 변동 감지 화면에서 변동 하나를 골라 들어온다. 판의 적용일과 그 전날로 본 것이다.
  await changed.getByRole("link", { name: "영향 범위 보기" }).click();
  await baseExpect(page).toHaveURL(/\/impact\?/);
  await baseExpect(page.getByTestId("impact-total")).toBeVisible();
  await expect(page.getByTestId("impact-as-of-effective")).toHaveText(second);
  await expect(page.getByTestId("impact-as-of-previous")).toHaveText(first);
  // 영향 대상이 있는 변동으로 나머지를 본다 (방금 올린 판의 변동은 이어진 것이 없어 영향 대상이 없다).
  // 시험용 Pack 의 시나리오를 올려 둔 e2e-actions 의 변동이다: 조치 화면에서 그 변동의 조치로 들어가 영향 범위로 간다.
  const seeded = (
    (await (await page.request.get("/api/v1/actions", { params: { packId: "e2e-actions", limit: "200" } })).json()) as {
      actions: { changeEventId: string; appliesTo: string }[];
    }
  ).actions.find((action) => action.appliesTo === "IMPACT_TARGET")!;
  await page.goto(`/actions?${new URLSearchParams({ packId: "e2e-actions", status: "ALL", changeEventId: seeded.changeEventId })}`);
  await page.getByRole("link", { name: "이 변동의 영향 범위" }).click();
  await baseExpect(page.getByRole("table", { name: "영향 대상" })).toBeVisible();
  await expect(page.getByRole("table", { name: "유형별 수", exact: true })).toBeVisible();
  await expect(page.getByRole("table", { name: "깊이별 수", exact: true })).toBeVisible();
  const target = page.getByRole("table", { name: "영향 대상" }).locator("tbody tr[data-target-id]").first();
  await target.getByText("근거 경로 보기").click();
  await expect(target.getByTestId("path-basis")).toHaveText(/변동 (전|후)의 관계/);
  await expect(target.getByRole("link", { name: "관계 지도" })).toBeVisible();
  await expect(page.getByRole("group", { name: "영향 대상 내보내기" }).getByRole("button", { name: "CSV" })).toBeVisible();
  await expect(page.getByRole("group", { name: "영향 대상 내보내기" }).getByRole("button", { name: "JSON" })).toBeVisible();
  // 개체로 영향 묻기: id, 조회 시점, 전날, 최대 깊이, 영향 묻기.
  const asker = page.getByRole("region", { name: "개체로 영향 묻기" });
  await asker.getByLabel("출발 개체의 id").fill("e2e-changes|운영지침|G-01");
  await asker.getByLabel("조회 시점").fill(second);
  await asker.getByLabel("전날의 관계도 함께 따라간다").check();
  await asker.getByLabel("최대 깊이").fill("1");
  await asker.getByRole("button", { name: "영향 묻기" }).click();
  await baseExpect(asker.getByTestId("entity-impact")).toBeVisible();
  await expect(asker.getByTestId("entity-impact")).toContainText("저장하지 않습니다");

  // 4절. 조치: Pack, 상태, 조치를 골라 근거와 이력을 보고 보류했다가 확정한다.
  await go(page, "조치");
  await page.getByLabel("Pack").selectOption("e2e-changes");
  expect(await page.getByLabel("상태").locator("option").allInnerTexts()).toEqual(
    baseExpect.arrayContaining(["대기", "보류", "확정", "전체"]),
  );
  await page.getByLabel("상태").selectOption({ label: "대기" });
  const row = page.getByRole("table", { name: "조치 목록" }).locator("tbody tr[data-action-id]").first();
  await baseExpect(row).toBeVisible();
  await row.getByRole("button", { name: "근거와 이력 보기" }).click();
  const panel = page.locator("[data-detail-of]").filter({ has: page.getByRole("region", { name: "이력" }) }).first();
  await baseExpect(panel).toBeVisible();
  // 사유 없이 보류하면 서버가 알린다. 사유를 적으면 보류된다.
  await panel.getByRole("button", { name: "보류" }).click();
  await expect(panel.getByRole("alert")).toContainText("사유");
  await panel.getByLabel("사유").fill("설명서를 따라가는 시험이다");
  await panel.getByRole("button", { name: "보류" }).click();
  await baseExpect(row.getByTestId("status")).toHaveText("보류");
  // 보류한 조치는 뒤에 확정할 수 있고, 확정한 조치는 바꿀 수 없다.
  await panel.getByRole("button", { name: "확정" }).click();
  await baseExpect(row.getByTestId("status")).toHaveText("확정");
  await expect(panel.getByRole("button", { name: "확정" })).toHaveCount(0);
  await expect(panel.getByRole("button", { name: "보류" })).toHaveCount(0);
  await expect(panel.getByRole("region", { name: "이력" })).toContainText(E2E_USER.loginId);
  // 상태를 바꾼 뒤에는 목록을 다시 받아야 파일로 받을 수 있다.
  const exports = page.getByRole("group", { name: "조치 목록 내보내기" });
  await expect(exports.getByRole("button", { name: "CSV" })).toBeDisabled();
  await page.getByRole("button", { name: "목록 다시 받기" }).click();
  await expect(page.getByTestId("list-outdated")).toHaveCount(0);

  // 5절. 관계 지도: 칸들과 개체 찾기, 지난 날짜.
  await go(page, "관계 지도");
  for (const name of ["개체 찾기", "경로 찾기", "Pack 전체 보기"]) {
    await expect(page.getByRole("region", { name, exact: true }), `관계 지도 화면의 칸: ${name}`).toBeVisible();
  }
  const picker = page.getByRole("region", { name: "개체 찾기" });
  await picker.getByLabel("Pack").selectOption("e2e-compare");
  await picker.getByLabel("검색어").fill("G-01");
  await picker.getByRole("button", { name: "찾기" }).click();
  await page.getByLabel("조회 시점").fill("2026-03-01");
  await picker.getByRole("table", { name: "찾은 개체" }).locator("tbody tr").first().getByRole("button", { name: "고르기" }).click();
  await baseExpect(page.getByTestId("relation-map")).toBeVisible();
  await expect(page.getByRole("radio", { name: "2D" })).toBeChecked();
  await expect(page.getByRole("radio", { name: "3D" })).toBeVisible();
  await expect(page.getByLabel("시점 막대")).toBeVisible();
  await expect(page.getByRole("group", { name: "보일 것" })).toBeVisible();
  await expect(page.getByLabel("깊이")).toBeVisible();
  // 지난 날짜의 상태: 값이 바뀐 날과 그 전날을 차례로 넣으면 같은 개체의 속성 값이 다르게 보인다.
  const values = page.getByRole("region", { name: "속성 값" });
  await baseExpect(values.getByTestId("entity-values")).toHaveAttribute("data-as-of", "2026-03-01");
  const onDay = await values.locator("tbody").innerText();
  await page.getByLabel("조회 시점").fill("2026-02-28");
  await page.getByRole("button", { name: "조회", exact: true }).click();
  await baseExpect(values.getByTestId("entity-values")).toHaveAttribute("data-as-of", "2026-02-28");
  expect(await values.locator("tbody").innerText()).not.toBe(onDay);
  await expect(page.getByRole("group", { name: "관계 목록 내보내기" }).or(page.getByText("이 조회 시점에 유효한 관계가 없습니다")).first()).toBeVisible();

  // 6절. Pack 정의: 고르면 정한 것이 보이고, 고칠 수 없다. 담당자에게는 적재와 검증 질문의 칸이 없다.
  await go(page, "Pack 정의");
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-actions");
  await baseExpect(page.getByTestId("definition")).toBeVisible();
  for (const name of ["개체 유형", "속성", "관계 유형", "입력 자료와 매핑", "규칙"]) {
    await expect(page.getByRole("table", { name, exact: true }), `Pack 정의의 표: ${name}`).toBeVisible();
  }
  await expect(page.getByRole("heading", { name: "제약" })).toBeVisible();
  await expect(page.getByTestId("definition-loaded-at")).toBeVisible();
  await expect(page.getByRole("region", { name: "Pack 적재" })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "검증 질문 돌리기" })).toHaveCount(0);

  expect(baseTest.info().errors, "설명서와 화면이 다른 곳").toEqual([]);
});

baseTest("관리자: 설명서 7절 ~ 10절을 차례대로 따라간다", async ({ page, context }) => {
  baseTest.setTimeout(120_000);
  await login(context, E2E_OTHER_USER);

  // 0절. 관리자에게는 감사 기록의 메뉴가 더 보인다.
  await page.goto("/pack-definition");
  expect(await menu(page)).toEqual(["변동 감지", "영향 범위", "조치", "관계 지도", "Pack 정의", "감사 기록"]);

  // 7절. Pack 을 적재한다: 서버에 둔 Pack, 적재, 틀린 Pack 은 문제를 모두 보인다, 다시 적재는 확인을 받는다.
  const loader = page.getByRole("region", { name: "Pack 적재" });
  await loader.getByLabel("서버에 둔 Pack").selectOption("e2e-broken");
  await loader.getByRole("button", { name: "적재", exact: true }).click();
  await baseExpect(loader.getByRole("alert")).toContainText("적재하지 않았습니다");
  await expect(loader.getByRole("list", { name: "Pack 의 문제" }).locator("li").first()).toBeVisible();
  await loader.getByLabel("서버에 둔 Pack").selectOption("e2e-loadable");
  await loader.getByRole("button", { name: "적재", exact: true }).click();
  const confirm = loader.getByRole("button", { name: "다시 적재" });
  if (await loader.locator('option[data-pack-id="e2e-loadable"][data-loaded="true"]').count()) {
    await baseExpect(loader.getByTestId("reload-confirm")).toContainText("이미 적재된 Pack");
    await confirm.click();
  }
  await baseExpect(loader.getByTestId("load-result")).toContainText("e2e-loadable");
  await baseExpect(page.getByTestId("definition")).toHaveAttribute("data-pack-id", "e2e-loadable");
  await expect(loader.locator('option[data-pack-id="e2e-loadable"]')).toContainText("(적재됨)");

  // 9절. 검증 질문을 돌린다.
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-verified");
  const runner = page.getByRole("region", { name: "검증 질문 돌리기" });
  await runner.getByRole("button", { name: "검증 질문 돌리기" }).click();
  await baseExpect(runner.getByTestId("verification-summary")).toBeVisible();
  await expect(runner.getByTestId("verification-summary")).toContainText("두 번 물은 결과가 같습니다");
  await expect(runner.getByRole("table", { name: "질문마다의 결과" })).toBeVisible();

  // 8절. 처리되지 않은 변동을 다시 처리한다.
  await go(page, "변동 감지");
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-failed");
  await page.getByRole("button", { name: "다시 처리" }).click();
  await baseExpect(page.getByTestId("reprocess-result")).toContainText("다시 처리했습니다");
  await expect(page.getByTestId("reprocess-result")).toContainText(/처리됨 \d+건, 다시 실패 \d+건/);

  // 10절. 감사 기록: 누가, 무엇을, 결과, 시작 날짜, 끝 날짜. 막힌 시도는 accessDenied 로 찾는다.
  await go(page, "감사 기록");
  await baseExpect(page.getByRole("table", { name: "감사 기록" })).toBeVisible();
  for (const label of ["누가", "무엇을", "결과", "시작 날짜", "끝 날짜"]) {
    await expect(page.getByLabel(label, { exact: true }), `감사 기록의 칸: ${label}`).toBeVisible();
  }
  await expect(page.getByTestId("audit-filter-hint")).toContainText("accessDenied");
  await page.getByLabel("무엇을", { exact: true }).fill("reprocessChanges");
  await page.getByRole("button", { name: "찾기" }).click();
  const rows = page.getByRole("table", { name: "감사 기록" }).locator("tbody tr");
  await baseExpect(rows.first()).toContainText(E2E_OTHER_USER.loginId);
  // 처리에 들어간 요청은 두 줄이다: 시작과 결과.
  expect(await rows.count()).toBeGreaterThanOrEqual(2);

  expect(baseTest.info().errors, "설명서와 화면이 다른 곳").toEqual([]);
});
