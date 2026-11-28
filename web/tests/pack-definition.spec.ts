import type { Page } from "@playwright/test";
import { expect, test } from "./fixtures";

// B-19 수용 기준: 적재된 Pack 의 유형, 속성, 관계 유형과 영향 방향, 제약, 입력 자료와 매핑, 규칙을 화면으로 본다.
// 고치는 단추가 없다 (D7 8절 "Pack 의 정의", D2 AD-415).
//
// 화면은 서버(getPackDefinition)가 준 정의를 그 순서 그대로 보여 준다. 다시 세거나 정렬하지 않는다.
// 자료는 서버의 시험용 실행이 적재해 둔 Pack 이다 (E2eSeedData): 시험용 Pack 의 사본과 두 예시 Pack.

type Definition = {
  label: string;
  entityTypes: { name: string; label: string; parents: string[] }[];
  attributes: { name: string }[];
  relationTypes: { name: string; impactDirection: string }[];
  inputSources: { inputSourceId: string; requiredColumns: string[] }[];
  rules: { ruleNo: string; actionText: string; dueInDays: number }[];
  constraints: string;
};

async function open(page: Page, packId: string) {
  await page.goto("/pack-definition");
  await page.getByLabel("Pack", { exact: true }).selectOption(packId);
  await expect(page.getByTestId("definition")).toHaveAttribute("data-pack-id", packId);
}

async function names(page: Page, table: string, attribute: string) {
  return page
    .getByRole("table", { name: table })
    .locator("tbody tr")
    .evaluateAll((rows, name) => rows.map((row) => row.getAttribute(name)), attribute);
}

for (const packId of ["e2e-actions", "manufacturing-safety", "retirement-pension"]) {
  test(`${packId}: 정의의 항목이 서버가 준 수와 순서 그대로 보인다`, async ({ page, request }) => {
    const answer = (await (await request.get("/api/v1/pack-definition", { params: { packId } })).json()) as Definition;
    expect(answer.entityTypes.length).toBeGreaterThan(3);

    await open(page, packId);

    expect(await names(page, "개체 유형", "data-name")).toEqual(answer.entityTypes.map((type) => type.name));
    expect(await names(page, "속성", "data-name")).toEqual(answer.attributes.map((attribute) => attribute.name));
    expect(await names(page, "관계 유형", "data-name")).toEqual(answer.relationTypes.map((type) => type.name));
    expect(await names(page, "입력 자료와 매핑", "data-input-source-id")).toEqual(
      answer.inputSources.map((source) => source.inputSourceId),
    );
    expect(await names(page, "규칙", "data-rule-no")).toEqual(answer.rules.map((rule) => rule.ruleNo));
    // 수는 서버가 준 목록의 길이를 그대로 적은 것이다.
    await expect(page.getByTestId("count-entity-types")).toHaveText(String(answer.entityTypes.length));
    await expect(page.getByTestId("count-relation-types")).toHaveText(String(answer.relationTypes.length));
    await expect(page.getByTestId("count-rules")).toHaveText(String(answer.rules.length));
  });
}

test("유형의 이름표와 상위 유형, 관계 유형의 영향 방향, 규칙의 할 일과 기한이 보인다", async ({ page, request }) => {
  const packId = "e2e-actions";
  const answer = (await (await request.get("/api/v1/pack-definition", { params: { packId } })).json()) as Definition;
  await open(page, packId);

  const child = answer.entityTypes.find((type) => type.parents.length > 0)!;
  expect(child, "상위 유형이 있는 유형이 시험 자료에 있어야 한다").toBeTruthy();
  const typeRow = page.getByRole("table", { name: "개체 유형" }).locator(`tbody tr[data-name="${child.name}"]`);
  await expect(typeRow).toContainText(child.label);
  await expect(typeRow).toContainText(child.parents[0]!);

  // 영향 방향은 한국어로 풀어 적는다.
  const relationRows = page.getByRole("table", { name: "관계 유형" }).locator("tbody tr");
  await expect(relationRows.first()).toContainText(/출발에서 도착으로|도착에서 출발로|양쪽|퍼지지 않음/);

  const rule = answer.rules[0]!;
  const ruleRow = page.getByRole("table", { name: "규칙" }).locator(`tbody tr[data-rule-no="${rule.ruleNo}"]`);
  await expect(ruleRow).toContainText(rule.actionText);
  await expect(ruleRow).toContainText(`${rule.dueInDays}일`);

  const source = answer.inputSources[0]!;
  const sourceRow = page
    .getByRole("table", { name: "입력 자료와 매핑" })
    .locator(`tbody tr[data-input-source-id="${source.inputSourceId}"]`);
  await expect(sourceRow).toContainText(source.requiredColumns[0]!);

  // 제약은 파일의 글 그대로다.
  await expect(page.getByTestId("constraints")).toHaveText(answer.constraints.trim() === "" ? "" : answer.constraints, {
    useInnerText: false,
  });
});

test("고치는 단추가 없고 Pack 을 고르기 전에는 정의가 없다", async ({ page }) => {
  await page.goto("/pack-definition");
  await expect(page.getByTestId("definition")).toHaveCount(0);
  await expect(page.getByText("Pack 을 고르면 그 정의가 나옵니다")).toBeVisible();

  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-actions");
  await expect(page.getByTestId("definition")).toBeVisible();
  // 이 화면의 단추는 없다 (위 띠의 로그아웃은 화면의 것이 아니다).
  await expect(page.getByRole("main").getByRole("button")).toHaveCount(0);
  await expect(page.getByRole("main").getByRole("textbox")).toHaveCount(0);
});

test("담당자에게도 메뉴가 보이고 정의를 받지 못하면 까닭을 알린다", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "Pack 정의" }).click();
  await expect(page.getByRole("heading", { name: "Pack 정의" })).toBeVisible();

  await page.route("**/api/v1/pack-definition?*", (route) => route.abort());
  await page.getByLabel("Pack", { exact: true }).selectOption("e2e-actions");
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.getByTestId("definition")).toHaveCount(0);
});

// ── 코드 검토(/code-review 124)가 짚은 것 ─────────────────────────────────────

test("판의 적용일과 견주는 조건은 무엇과 견주는지 적고, 언제 적재한 정의인지 보인다", async ({ page, request }) => {
  const packId = "e2e-actions";
  const answer = (await (await request.get("/api/v1/pack-definition", { params: { packId } })).json()) as Definition & {
    loadedAt: string;
  };
  // 시험용 Pack 에는 판의 적용일과 견주는 조건이 없다. 서버의 답에 그런 조건 하나를 끼워 넣는다.
  await page.route("**/api/v1/pack-definition?*", async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { rules: { conditions: unknown[] }[] };
    body.rules[0]!.conditions = [
      {
        subject: "CHANGE_TARGET",
        linkedBy: null,
        typeIs: null,
        attributeName: "지어낸날짜",
        operator: "LESS_THAN_OR_EQUAL",
        compareTo: "EFFECTIVE_DATE",
        compareSubject: null,
        compareAttributeName: null,
        values: [],
      },
    ];
    await route.fulfill({ response, json: body });
  });
  await open(page, packId);

  const rule = page.getByRole("table", { name: "규칙" }).locator("tbody tr").first();
  await expect(rule).toContainText("지어낸날짜 LESS_THAN_OR_EQUAL 판의 적용일");

  // 적재한 때: 서버가 준 시각 그대로를 담고, 설치 설정의 시간대로 바꿔 보인다.
  const loaded = page.getByTestId("definition-loaded-at");
  await expect(loaded).toHaveAttribute("datetime", answer.loadedAt);
  await expect(loaded).toHaveText(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
});

test("표시 이름의 속성을 적지 않은 유형에 상위 유형의 것을 받는다고 단정하지 않는다", async ({ page, request }) => {
  const packId = "e2e-actions";
  const answer = (await (await request.get("/api/v1/pack-definition", { params: { packId } })).json()) as {
    entityTypes: { name: string; parents: string[]; displayNameAttribute: string | null }[];
  };
  const child = answer.entityTypes.find((type) => type.parents.length > 0 && type.displayNameAttribute === null)!;
  expect(child, "상위 유형이 있고 표시 이름의 속성을 적지 않은 유형이 시험 자료에 있어야 한다").toBeTruthy();
  await open(page, packId);

  const row = page.getByRole("table", { name: "개체 유형" }).locator(`tbody tr[data-name="${child.name}"]`);
  // 상위 유형에도 없을 수 있다: 화면은 그것을 알지 못한다.
  await expect(row).toContainText("이 유형에는 적지 않았습니다");
  await expect(row).not.toContainText("받습니다");
});
