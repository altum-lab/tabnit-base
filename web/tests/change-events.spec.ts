import type { Page } from "@playwright/test";
import { expect as baseExpect, test as baseTest } from "@playwright/test";
import { E2E_OTHER_USER, expect, login, test } from "./fixtures";

// C-15 수용 기준: Pack 의 변동 이벤트를 판을 거치지 않고 목록으로 본다. 줄마다 상태, 조치의 수, 영향 대상의 수가 있다
// (D7 5절 "변동 이벤트의 목록", D2 AD-417).
//
// 화면은 서버(listChangeEvents)가 준 줄과 순서, 수를 그대로 보여 준다. 다시 세거나 정렬하지 않는다.
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): e2e-actions 는 조치까지 만들어 둔 Pack, e2e-failed 는 변동이
// 실패로 남아 있는 Pack 이다. 이 시험은 읽기만 한다.

type List = {
  total: number;
  changes: {
    changeEventId: string;
    itemKey: string;
    status: string;
    actionCount: number;
    impactTargetCount: number | null;
    editionNo: number;
  }[];
};

function section(page: Page) {
  return page.getByRole("region", { name: "변동 이벤트" });
}

async function open(page: Page, packId: string) {
  await page.goto("/changes");
  await page.getByLabel("Pack", { exact: true }).selectOption(packId);
  await expect(section(page).getByRole("table", { name: "변동 이벤트" })).toBeVisible();
}

async function rowIds(page: Page) {
  return section(page)
    .getByRole("table", { name: "변동 이벤트" })
    .locator("tbody tr")
    .evaluateAll((rows) => rows.map((row) => row.getAttribute("data-listed-change-event-id")));
}

test("Pack 의 변동이 서버가 준 차례와 수 그대로 보이고 줄마다 상태와 조치의 수와 영향 대상의 수가 있다", async ({ page, request }) => {
  const answer = (await (await request.get("/api/v1/change-events", { params: { packId: "e2e-actions" } })).json()) as List;
  expect(answer.total).toBeGreaterThan(1);

  await open(page, "e2e-actions");

  expect(await rowIds(page)).toEqual(answer.changes.map((change) => change.changeEventId));
  await expect(section(page).getByTestId("pager-position")).toContainText(`전체 ${answer.total}건`);
  // 줄은 차례로 찾는다: 변동의 id 에는 역빗금과 세로 막대가 들어 있을 수 있어 선택자의 글자로 쓰지 않는다.
  for (const [index, change] of answer.changes.entries()) {
    const row = section(page).locator("tbody tr").nth(index);
    await expect(row.getByTestId("action-count")).toHaveText(String(change.actionCount));
    await expect(row.getByTestId("impact-target-count")).toHaveText(
      change.impactTargetCount === null ? "없음" : String(change.impactTargetCount),
    );
    await expect(row).toContainText(change.itemKey);
    await expect(row).toContainText("처리됨");
  }
});

test("줄에서 그 변동의 조치와 영향 범위로 간다", async ({ page, request }) => {
  const answer = (await (await request.get("/api/v1/change-events", { params: { packId: "e2e-actions" } })).json()) as List;
  const change = answer.changes.find((candidate) => candidate.actionCount > 0 && (candidate.impactTargetCount ?? 0) > 0);
  expect(change, "조치와 영향 대상이 있는 변동이 시험 자료에 있어야 한다").toBeTruthy();
  const index = answer.changes.indexOf(change!);
  await open(page, "e2e-actions");
  const row = section(page).locator("tbody tr").nth(index);
  expect(await row.getAttribute("data-listed-change-event-id")).toBe(change!.changeEventId);

  await row.getByRole("link", { name: "영향 범위" }).click();
  await expect(page).toHaveURL(/\/impact\?/);
  expect(new URL(page.url()).searchParams.get("changeEventId")).toBe(change!.changeEventId);

  await open(page, "e2e-actions");
  await section(page).locator("tbody tr").nth(index).getByRole("link", { name: "조치" }).click();
  await expect(page).toHaveURL(/\/actions\?/);
  expect(new URL(page.url()).searchParams.get("changeEventId")).toBe(change!.changeEventId);
});

test("상태로 거르면 서버가 거른 줄과 수가 나온다", async ({ page, request }) => {
  const failed = (await (
    await request.get("/api/v1/change-events", { params: { packId: "e2e-failed", status: "FAILED" } })
  ).json()) as List;
  expect(failed.total, "실패한 변동이 시험 자료에 있어야 한다").toBeGreaterThan(0);

  await open(page, "e2e-failed");
  await section(page).getByLabel("상태").selectOption("FAILED");

  await expect(section(page).getByTestId("pager-position")).toContainText(`전체 ${failed.total}건`);
  expect(await rowIds(page)).toEqual(failed.changes.map((change) => change.changeEventId));
  for (const row of await section(page).locator("tbody tr").all()) {
    await expect(row).toContainText("실패");
  }

  // 처리됨으로 거르면 서버의 답대로다. 없으면 없다고 말한다.
  const processed = (await (
    await request.get("/api/v1/change-events", { params: { packId: "e2e-failed", status: "PROCESSED" } })
  ).json()) as List;
  await section(page).getByLabel("상태").selectOption("PROCESSED");
  if (processed.total === 0) {
    await expect(section(page).getByRole("status")).toContainText("변동 이벤트가 없습니다");
  } else {
    await expect(section(page).getByTestId("pager-position")).toContainText(`전체 ${processed.total}건`);
  }
});

test("영향 범위가 없는 변동은 영향 대상의 수를 0 이 아니라 없음으로 보이고 영향 범위로 가는 길이 없다", async ({ page }) => {
  await page.route("**/api/v1/change-events?*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        packId: "e2e-actions",
        status: null,
        total: 2,
        offset: 0,
        limit: 50,
        changes: [
          {
            changeEventId: "지어낸-변동-1",
            editionId: "e2e-actions|guideline|2",
            inputSourceId: "guideline",
            inputSourceLabel: "운영지침 목록",
            editionNo: 2,
            effectiveDate: "2026-03-01",
            itemKey: "G-01",
            kind: "CHANGED",
            primaryEntityId: "e2e-actions|운영지침|G-01",
            primaryEntity: {
              entityId: "e2e-actions|운영지침|G-01",
              entityType: "운영지침",
              entityTypeLabel: "운영지침",
              entityKey: "G-01",
              displayName: "지어낸 지침",
            },
            status: "FAILED",
            failedStage: "IMPACT",
            failureCode: "IMPACT_FAILED",
            actionCount: 0,
            impactTargetCount: null,
          },
          {
            changeEventId: "지어낸-변동-2",
            editionId: "e2e-actions|guideline|2",
            inputSourceId: "guideline",
            inputSourceLabel: "운영지침 목록",
            editionNo: 2,
            effectiveDate: "2026-03-01",
            itemKey: "G-02",
            kind: "ADDED",
            primaryEntityId: "e2e-actions|운영지침|G-02",
            primaryEntity: {
              entityId: "e2e-actions|운영지침|G-02",
              entityType: "운영지침",
              entityTypeLabel: "운영지침",
              entityKey: "G-02",
              displayName: null,
            },
            status: "PROCESSED",
            failedStage: null,
            failureCode: null,
            actionCount: 0,
            impactTargetCount: 0,
          },
        ],
      }),
    }),
  );
  await open(page, "e2e-actions");

  const none = section(page).locator('tbody tr[data-listed-change-event-id="지어낸-변동-1"]');
  await expect(none.getByTestId("impact-target-count")).toHaveText("없음");
  await expect(none.getByRole("link", { name: "영향 범위" })).toHaveCount(0);
  await expect(none).toContainText("실패");
  const zero = section(page).locator('tbody tr[data-listed-change-event-id="지어낸-변동-2"]');
  await expect(zero.getByTestId("impact-target-count")).toHaveText("0");
  await expect(zero.getByRole("link", { name: "영향 범위" })).toHaveCount(1);
  // 조치가 없는 변동에는 조치로 가는 길이 없다.
  await expect(zero.getByRole("link", { name: "조치" })).toHaveCount(0);
  await expect(zero).toContainText("추가");
});

test("받지 못하면 까닭을 알리고 올리는 폼은 그대로 쓸 수 있다", async ({ page }) => {
  await page.route("**/api/v1/change-events?*", (route) => route.abort());
  await page.goto("/changes");
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-actions");

  await expect(section(page).getByRole("alert")).toBeVisible();
  await expect(page.getByRole("button", { name: "올리기" })).toBeVisible();
});

// ── /code-review 가 짚은 것 (C-15 의 후속 수정) ─────────────────────────────

baseTest("관리자가 다시 처리하면 변동 이벤트의 목록도 다시 받는다", async ({ page, context }) => {
  // e2e-failed 는 변동이 실패로 남아 있는 Pack 이다. 다시 처리해도 다시 실패하지만 (규칙을 고치지 않는다),
  // 처리가 끝나면 이 목록이 서버에 다시 물어야 한다: 고쳐서 처리됐다면 상태와 수가 바뀌었을 것이다.
  await login(context, E2E_OTHER_USER);
  await page.goto("/changes");
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-failed");
  await baseExpect(section(page).getByRole("table", { name: "변동 이벤트" })).toBeVisible();

  const asked: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/change-events?")) {
      asked.push(request.url());
    }
  });
  await page.getByRole("button", { name: "다시 처리" }).click();
  await baseExpect(page.getByTestId("reprocess-result")).toContainText("다시 처리했습니다");

  await baseExpect.poll(() => asked.length, { message: "다시 처리한 뒤 변동 이벤트의 목록을 다시 묻는다" }).toBeGreaterThan(0);
  await baseExpect(section(page).getByRole("table", { name: "변동 이벤트" })).toBeVisible();
});

test("거르거나 쪽을 넘겨 새로 받는 동안에는 받는 중이라고 알리고 쪽 넘기기를 잠근다", async ({ page }) => {
  await open(page, "e2e-actions");
  // 상태로 거른 요청의 답을 붙들어 둔다.
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/change-events?**status=FAILED**", async (route) => {
    await held;
    await route.continue();
  });

  await section(page).getByLabel("상태").selectOption("FAILED");

  // 새 답이 오기 전: 옛 줄이 새 조건의 것처럼 보이지 않게 받는 중이라고 알린다.
  await expect(section(page).getByTestId("change-events-pending")).toBeVisible();
  await expect(section(page).getByRole("table", { name: "변동 이벤트" })).toHaveAttribute("aria-busy", "true");
  for (const button of await section(page).getByTestId("pager").getByRole("button").all()) {
    await expect(button).toBeDisabled();
  }

  release();
  await expect(section(page).getByTestId("change-events-pending")).toHaveCount(0);
});

test("빈 목록의 문구는 고른 값이 아니라 서버가 답한 조건으로 고른다", async ({ page }) => {
  await open(page, "e2e-actions");
  // 실패한 변동이 없는 Pack: 실패로 거르면 "이 상태의 변동 이벤트가 없습니다".
  await section(page).getByLabel("상태").selectOption("FAILED");
  await expect(section(page).getByText("이 상태의 변동 이벤트가 없습니다.")).toBeVisible();

  // 모두로 돌아가는 요청의 답을 붙들어 둔다. 그 사이에 "이 Pack 에는 변동 이벤트가 없습니다"라고 하지 않는다:
  // 이 Pack 에는 변동이 있다.
  let release: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/change-events?**", async (route) => {
    if (!route.request().url().includes("status=")) {
      await held;
    }
    await route.continue();
  });
  await section(page).getByLabel("상태").selectOption("");
  await expect(section(page).getByTestId("change-events-pending")).toBeVisible();
  await expect(section(page).getByText("이 Pack 에는 변동 이벤트가 없습니다")).toHaveCount(0);

  release();
  await expect(section(page).getByRole("table", { name: "변동 이벤트" })).toBeVisible();
});

