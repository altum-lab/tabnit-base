import { expect, test } from "./fixtures";

// D-18 수용 기준: 변동 감지 화면에서 Pack 을 고르면 제약의 위반이 조회 시점과 함께 목록으로 나온다.
// 위반이 없으면 없다고 말한다 (D7 4절 "제약의 위반", D2 AD-390, AD-395).
//
// 자료는 서버의 시험용 실행이 넣는다 (E2eSeedData): 시험용 Pack 의 사본 e2e-actions. 이 시험은 그 Pack 을 읽기만 한다.
// 기대값은 같은 API 를 직접 불러 받은 답이다: 화면이 서버의 값을 그대로 보이는지를 본다.
// 서버가 내기 어려운 답(검사하지 못함, 경고와 알림, 실패)은 지어낸 답으로 본다.

const PACK_ID = "e2e-actions";

type Entity = { entityId: string; entityType: string; entityTypeLabel: string; entityKey: string; displayName: string | null };
type Violation = {
  severity: string;
  entity: Entity | null;
  pathKind: string;
  pathName: string | null;
  pathLabel: string | null;
  value: string | null;
  valueEntity: Entity | null;
  constraintKind: string;
  constraintName: string | null;
  message: string;
};
type Listed = {
  packId: string;
  asOf: string;
  checked: boolean;
  maxRows: number;
  entityCount: number;
  relationCount: number;
  valueCount: number;
  total: number;
  offset: number;
  limit: number;
  violations: Violation[];
};

test("제약의 위반을 조회 시점과 함께 서버가 준 그대로 보이고, 조회 시점을 바꾸면 그 날짜의 것을 보인다", async ({ page, request }) => {
  const ask = async (asOf?: string) =>
    (await (
      await request.get("/api/v1/constraint-violations", { params: asOf ? { packId: PACK_ID, asOf } : { packId: PACK_ID } })
    ).json()) as Listed;
  // 조회 시점을 비운 것 (서버가 오늘로 정한다)과 자료가 들어오기 전의 날짜.
  const now = await ask();
  const before = await ask("2025-06-01");
  expect(now.checked).toBe(true);
  // 자료가 들어오기 전: 개체는 있고 값이 없어, 값이 있어야 한다는 제약에 걸린다 (개체는 모두 검사한다).
  expect(before.total, "자료가 들어오기 전의 날짜에는 위반이 있어야 이 시험에 뜻이 있다").toBeGreaterThan(1);
  expect(before.total, "두 날짜의 답이 달라야 조회 시점을 바꾼 것이 보인다").not.toBe(now.total);

  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  const section = page.getByRole("region", { name: "제약의 위반" });
  const rows = section.getByRole("table", { name: "제약의 위반" }).locator("tbody tr");

  // 화면이 서버의 답을 그대로 보이는지: 조회 시점, 수, 차례, 글자.
  const shows = async (listed: Listed) => {
    await expect(section.getByTestId("violations-as-of")).toHaveText(listed.asOf);
    if (listed.total === 0) {
      await expect(section.getByTestId("violations-none")).toContainText(`${listed.asOf} 에 제약의 위반이 없습니다`);
      await expect(section.getByRole("table")).toHaveCount(0);
      return;
    }
    await expect(section.getByTestId("violations-none")).toHaveCount(0);
    await expect(section.getByTestId("violation-total")).toHaveText(`${listed.total}건`);
    await expect(rows).toHaveCount(listed.violations.length);
    for (const [index, violation] of listed.violations.entries()) {
      const row = rows.nth(index);
      await expect(row.getByTestId("violation-message")).toHaveText(violation.message);
      await expect(row).toHaveAttribute("data-severity", violation.severity);
      expect(violation.entity, "이 자료의 위반에는 개체가 있다").not.toBeNull();
      await expect(row.getByTestId("violation-entity").getByTestId("entity")).toHaveAttribute("data-entity-id", violation.entity!.entityId);
      expect(violation.pathLabel, "이 자료의 위반은 속성의 것이다").not.toBeNull();
      await expect(row.getByTestId("violation-path")).toContainText(violation.pathLabel!);
      await expect(row.getByTestId("violation-constraint")).toContainText(violation.constraintKind);
      expect(violation.constraintName).not.toBeNull();
      await expect(row.getByTestId("violation-constraint")).toContainText(violation.constraintName!);
    }
  };

  // 조회 시점을 비우면 서버가 정한 날짜의 답이 보인다.
  await shows(now);
  // 조회 시점을 바꾸면 그 날짜의 답이 보인다.
  await section.getByLabel("조회 시점").fill("2025-06-01");
  await shows(before);
  // 조회 시점을 지우면 다시 서버가 정한 날짜다.
  await section.getByLabel("조회 시점").fill("");
  await shows(now);
});

function entity(key: string, type: string, name: string | null): Entity {
  return { entityId: `p|${type}|${key}`, entityType: type, entityTypeLabel: `${type} 이름표`, entityKey: key, displayName: name };
}

function answer(over: Partial<Listed>): Listed {
  return {
    packId: PACK_ID,
    asOf: "2026-04-01",
    checked: true,
    maxRows: 200000,
    entityCount: 3,
    relationCount: 2,
    valueCount: 5,
    total: 0,
    offset: 0,
    limit: 50,
    violations: [],
    ...over,
  };
}

test("심각도와 어긴 곳과 값과 가리키는 개체를 서버가 준 대로 보인다", async ({ page }) => {
  const violations: Violation[] = [
    { severity: "VIOLATION", entity: entity("A-1", "TypeA", "지어낸 개체"), pathKind: "RELATION", pathName: "links", pathLabel: "잇는다",
      value: null, valueEntity: entity("B-9", "TypeB", null), constraintKind: "Class", constraintName: "지어낸제약", message: "지어낸 문구 하나." },
    { severity: "WARNING", entity: entity("A-2", "TypeA", null), pathKind: "ATTRIBUTE", pathName: "count", pathLabel: "수",
      value: "0", valueEntity: null, constraintKind: "MinInclusive", constraintName: null, message: "지어낸 문구 둘." },
    { severity: "INFO", entity: null, pathKind: "EXPRESSION", pathName: "^links/title", pathLabel: null,
      value: null, valueEntity: null, constraintKind: "MinCount", constraintName: "다른제약", message: "지어낸 문구 셋." },
    { severity: "VIOLATION", entity: entity("A-3", "TypeA", "셋째"), pathKind: "NONE", pathName: null, pathLabel: null,
      value: null, valueEntity: null, constraintKind: "Or", constraintName: "지어낸제약", message: "지어낸 문구 넷." },
  ];
  await page.route("**/api/v1/constraint-violations?*", (route) =>
    route.fulfill({ json: answer({ total: 4, violations }) }),
  );
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  const section = page.getByRole("region", { name: "제약의 위반" });
  const rows = section.getByRole("table", { name: "제약의 위반" }).locator("tbody tr");
  await expect(rows).toHaveCount(4);

  // 서버가 준 차례 그대로다. 화면은 다시 정렬하지 않는다.
  await expect(rows.nth(0).getByTestId("violation-severity")).toHaveText("위반");
  await expect(rows.nth(1).getByTestId("violation-severity")).toHaveText("경고");
  await expect(rows.nth(2).getByTestId("violation-severity")).toHaveText("알림");
  // 관계의 위반: 가리키는 개체가 값의 자리에 나온다.
  await expect(rows.nth(0).getByTestId("violation-path")).toContainText("잇는다");
  await expect(rows.nth(0).getByTestId("violation-value").getByTestId("entity")).toHaveAttribute("data-entity-id", "p|TypeB|B-9");
  await expect(rows.nth(0).getByTestId("violation-constraint")).toContainText("지어낸제약");
  // 속성의 위반: 값이 서버가 준 글자 그대로 나온다.
  await expect(rows.nth(1).getByTestId("violation-value")).toHaveText("0");
  await expect(rows.nth(1).getByTestId("violation-path")).toContainText("수");
  // 개체가 없는 위반과 경로의 식: 지어내지 않고 없다고 적거나 서버가 준 식을 적는다.
  await expect(rows.nth(2).getByTestId("violation-entity")).toContainText("개체 없음");
  await expect(rows.nth(2).getByTestId("violation-path")).toContainText("^links/title");
  // 개체 자신에 건 제약.
  await expect(rows.nth(3).getByTestId("violation-path")).toContainText("개체 자신");
  await expect(section.getByTestId("violation-total")).toHaveText("4건");
  // 눈으로 볼 그림을 남긴다 (저장소에 올리지 않는 폴더).
  await section.screenshot({ path: "test-results/constraint-violations-list.png" });
});

test("자료가 한도보다 커서 검사하지 못했으면 위반이 없다고 하지 않고 그 사실을 알린다", async ({ page }) => {
  await page.route("**/api/v1/constraint-violations?*", (route) =>
    route.fulfill({ json: answer({ checked: false, maxRows: 100, entityCount: 70, relationCount: 20, valueCount: 30 }) }),
  );
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  const section = page.getByRole("region", { name: "제약의 위반" });

  const notChecked = section.getByTestId("violations-not-checked");
  await expect(notChecked).toContainText("검사하지 못했습니다");
  // 수와 한도는 서버가 준 값이다. 화면은 더하지 않는다.
  await expect(notChecked).toContainText("개체 70");
  await expect(notChecked).toContainText("관계 20");
  await expect(notChecked).toContainText("속성 값 30");
  await expect(notChecked).toContainText("100");
  await expect(section.getByTestId("violations-none")).toHaveCount(0);
  await expect(section.getByRole("table")).toHaveCount(0);
});

test("제약의 위반을 받지 못하면 까닭을 알리고 다시 불러올 수 있다", async ({ page }) => {
  let failing = true;
  await page.route("**/api/v1/constraint-violations?*", (route) =>
    failing
      ? route.fulfill({
          status: 500,
          contentType: "application/problem+json",
          json: { type: "about:blank", title: "오류", status: 500, code: "INTERNAL_ERROR", detail: "지어낸 오류 문구입니다. 다시 시도하세요." },
        })
      : route.fulfill({ json: answer({}) }),
  );
  await page.goto("/changes");
  await page.getByLabel("Pack").selectOption(PACK_ID);
  const section = page.getByRole("region", { name: "제약의 위반" });

  await expect(section.getByRole("alert")).toContainText("지어낸 오류 문구입니다");
  // 실패를 위반이 없는 것으로 보이지 않는다.
  await expect(section.getByTestId("violations-none")).toHaveCount(0);
  failing = false;
  await section.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(section.getByTestId("violations-none")).toBeVisible();
  // 화면의 다른 부분은 그대로 쓸 수 있다.
  await expect(page.getByRole("table", { name: "지난 판" })).toBeVisible();
});
