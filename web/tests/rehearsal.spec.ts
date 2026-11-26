import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test } from "./fixtures";

// D-10 수용 기준: 두 시나리오를 처음부터 끝까지 막힘 없이 보여 준다.
//
// 두 예시 Pack 의 시나리오(questions/S-01.yaml 의 steps)를 적힌 차례대로 화면에서 올리고, 이어서 영향 범위, 조치와 확정,
// 관계 지도까지 화면으로만 간다. 시연하는 사람이 하는 그대로다. 값이 맞는지는 검증 질문과 전수 대조(verify)가 본다:
// 이 시험은 화면이 끝까지 이어지는지, 도중에 오류나 처리되지 않은 변동이 없는지를 본다.
//
// Pack 의 정의는 서버의 시험용 실행이 적재해 둔다 (server/api/src/test 의 E2eSeedData). 판은 이 시험이 올린다.
// 단계마다 화면에 나온 수는 test-results/rehearsal/ 에 남긴다. 시연 대본(docs/demo-script.md)의 수가 여기서 나왔다.
// 올리는 자료는 저장소의 예시 자료다. 모든 이름과 값은 지어낸 것이다.

const PACKS = ["manufacturing-safety", "retirement-pension"];

// 이 파일의 폴더(web/tests). 저장소의 예시 자료와 결과를 남길 곳을 여기서부터 짚는다.
const HERE = path.dirname(fileURLToPath(import.meta.url));

const OUT = path.join(HERE, "..", "test-results", "rehearsal");

type Step = { step: number; inputSource: string; file: string; effectiveDate: string };

type StepLog = Step & { items: number; added: number; changed: number; removed: number; actions: number; notices: number };

// 시나리오의 단계를 Pack 의 파일에서 읽는다. 시험에 단계를 옮겨 적지 않는다.
function stepsOf(pack: string): Step[] {
  const text = readFileSync(path.join(HERE, "..", "..", "packs", pack, "questions", "S-01.yaml"), "utf-8");
  const pattern = /step:\s*(\d+)\s*,\s*inputSource:\s*([^,\s}]+)\s*,\s*file:\s*([^,\s}]+)\s*,\s*effectiveDate:\s*([0-9-]+)/g;
  const steps: Step[] = [];
  for (const match of text.matchAll(pattern)) {
    steps.push({ step: Number(match[1]), inputSource: match[2] ?? "", file: match[3] ?? "", effectiveDate: match[4] ?? "" });
  }
  return steps;
}

for (const pack of PACKS) {
  test(`시연 리허설: ${pack} 의 시나리오를 화면으로 처음부터 끝까지 간다`, async ({ page }) => {
    test.setTimeout(900_000);
    mkdirSync(OUT, { recursive: true });
    const steps = stepsOf(pack);
    const firstStep = steps[0];
    if (!firstStep) {
      throw new Error(`${pack} 의 시나리오에 단계가 없습니다.`);
    }
    const shot = (name: string) => page.screenshot({ path: path.join(OUT, `${pack}-${name}.png`), fullPage: true });

    // 화면이 낸 오류(브라우저의 콘솔과 잡히지 않은 예외)가 하나라도 있으면 막힌 것이다.
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(String(error)));
    page.on("console", (message) => {
      if (message.type() === "error") {
        pageErrors.push(message.text());
      }
    });

    // 1) 변동 감지 화면에서 시나리오의 판을 차례대로 올린다.
    await page.goto("/changes");
    await page.getByLabel("Pack").selectOption(pack);
    await page.getByLabel("입력 자료").selectOption(firstStep.inputSource);
    await expect(page.getByTestId("latest-edition")).toBeVisible();
    // 띄워 둔 서버를 다시 쓰면 이미 판이 들어 있다. 같은 시나리오를 두 번 올릴 수는 없다.
    test.skip((await page.getByTestId("latest-effective-date").count()) > 0, "이 서버에는 이미 리허설의 판이 들어 있다");

    const log: StepLog[] = [];
    let lastWithActions: { step: Step; changeEventId: string } | null = null;
    for (const step of steps) {
      await page.getByLabel("입력 자료").selectOption(step.inputSource);
      await expect(page.getByTestId("latest-edition")).toBeVisible();
      await page.getByLabel("적용일").fill(step.effectiveDate);
      await page.getByLabel("파일").setInputFiles(path.join(HERE, "..", "..", "packs", pack, step.file));
      await page.getByRole("button", { name: "올리기" }).click();
      const outcome = page.getByTestId("outcome");
      await expect(outcome, `${step.step}단계 ${step.file}`).toContainText(`적용일 ${step.effectiveDate}`, { timeout: 60_000 });
      await expect(outcome).toContainText("새 판을 받아들였습니다");
      const result = page.getByRole("region", { name: "올린 결과" });
      // 올린 결과에 경고가 없다: 처리를 시작하지 못했다는 알림 등.
      await expect(result.getByRole("alert"), `${step.step}단계에 경고가 없다`).toHaveCount(0);

      const rows = page.getByRole("table", { name: "바뀐 항목" }).locator("tbody tr");
      const count = await rows.count();
      const kinds = count > 0 ? await rows.locator("td:nth-child(2)").allTextContents() : [];
      // 바뀐 항목은 모두 조치까지 처리된다.
      for (let i = 0; i < count; i++) {
        await expect(rows.nth(i).locator("td:nth-child(6)"), `${step.step}단계 ${i + 1}번째 변동`).toContainText("처리됨");
      }
      const actionTexts = count > 0 ? await rows.locator("td:nth-child(7)").allTextContents() : [];
      const actions = actionTexts.reduce((sum, text) => sum + Number(/(\d+)건/.exec(text)?.[1] ?? 0), 0);
      const items = Number(/받아들인 항목\s*(\d+)개/.exec((await outcome.textContent()) ?? "")?.[1] ?? -1);
      const notices = await page.getByRole("table", { name: "투입의 알림" }).locator("tbody tr").count();
      log.push({
        ...step,
        items,
        added: kinds.filter((kind) => kind === "추가").length,
        changed: kinds.filter((kind) => kind === "변경").length,
        removed: kinds.filter((kind) => kind === "삭제").length,
        actions,
        notices,
      });
      if (count > 0) {
        await shot(`step-${String(step.step).padStart(2, "0")}-changes`);
      }
      // 조치가 있는 변동 가운데 마지막 것으로 뒤의 화면을 본다.
      for (let i = 0; i < count; i++) {
        if (Number(/(\d+)건/.exec(actionTexts[i] ?? "")?.[1] ?? 0) > 0) {
          lastWithActions = { step, changeEventId: (await rows.nth(i).getAttribute("data-change-event-id")) ?? "" };
        }
      }
    }
    writeFileSync(path.join(OUT, `${pack}.json`), JSON.stringify(log, null, 2));

    // 예시 Pack 의 자료는 제 제약을 어기지 않는다 (B-17). 시나리오를 다 올린 뒤의 날짜와 처음 자료만 있던 날짜로 물어 본다.
    // 화면은 아직 없다 (D-18): 화면이 쓰는 주소로 API 를 부른다. 로그인은 이 화면의 것을 쓴다.
    for (const asOf of ["2026-12-31", "2026-01-01"]) {
      const answered = await page.request.get("/api/v1/constraint-violations", { params: { packId: pack, asOf } });
      expect(answered.status()).toBe(200);
      const violations = (await answered.json()) as { checked: boolean; total: number; entityCount: number; valueCount: number };
      expect(violations.checked).toBe(true);
      expect(violations.entityCount).toBeGreaterThan(0);
      expect(violations.valueCount).toBeGreaterThan(0);
      expect(violations.total, `${pack} 의 ${asOf} 의 제약의 위반`).toBe(0);
    }
    expect(log.some((entry) => entry.added + entry.changed + entry.removed > 0), "시나리오에 변동이 있어야 한다").toBe(true);
    expect(lastWithActions, "시나리오에 조치가 만들어진 변동이 있어야 한다").not.toBeNull();
    const shown = lastWithActions!;

    // 2) 그 변동의 영향 범위로 간다. 마지막으로 올린 판의 결과가 화면에 남아 있을 때만 그 링크가 있다:
    //    마지막 단계의 변동이 아니면 주소로 연다 (지난 변동의 목록이 화면에 없다).
    const row = page.locator(`tr[data-change-event-id="${shown.changeEventId.replace(/["\\]/g, "\\$&")}"]`);
    if ((await row.count()) > 0) {
      await row.getByRole("link", { name: "영향 범위 보기" }).click();
    } else {
      await page.goto(`/impact?${new URLSearchParams({ changeEventId: shown.changeEventId })}`);
    }
    await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
    await expect(page.getByTestId("impact-total")).toBeVisible();
    const targets = page.getByRole("table", { name: "영향 대상" }).locator("tbody tr[data-target-id]");
    await expect(targets.first()).toBeVisible();
    const impactTotal = (await page.getByTestId("impact-total").textContent()) ?? "";
    await shot("impact");

    // 3) 영향 대상 하나의 관계 지도를 본다.
    await targets.first().getByRole("link", { name: "관계 지도" }).click();
    await expect(page.getByRole("heading", { name: "관계 지도", level: 1 })).toBeVisible();
    await expect(page.getByTestId("relation-map")).toBeVisible();
    await expect(page.getByRole("list", { name: "지도의 노드" }).locator("li").first()).toBeAttached();
    await shot("relation-map");

    // 4) 그 변동의 조치로 가서 근거를 보고 하나를 확정하고 하나를 보류한다.
    await page.goto(`/impact?${new URLSearchParams({ changeEventId: shown.changeEventId })}`);
    await page.getByRole("link", { name: "이 변동의 조치 보기" }).click();
    await expect(page.getByRole("heading", { name: "조치", level: 1 })).toBeVisible();
    const actionRows = page.getByRole("table", { name: "조치 목록" }).locator("tbody tr[data-action-id]");
    await expect(actionRows.first()).toBeVisible();
    // 뒤의 화면에서 본 것도 남긴다: 어느 변동의 영향 대상 몇 개와 조치 몇 건을 봤는가.
    writeFileSync(
      path.join(OUT, `${pack}-after.json`),
      JSON.stringify({ step: shown.step, changeEventId: shown.changeEventId, impactTotal, actionsOnPage: await actionRows.count() }, null, 2),
    );
    await shot("actions");
    const first = actionRows.first();
    await expect(first.getByTestId("status")).toHaveText("대기");
    await first.getByRole("button", { name: "근거와 이력 보기" }).click();
    const panel = page.locator("[data-detail-of]").filter({ has: page.getByRole("region", { name: "이력" }) }).first();
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("region", { name: "이력" })).not.toContainText("불러오는 중");
    await shot("action-detail");
    await panel.getByRole("button", { name: "확정" }).click();
    await expect(first.getByTestId("status")).toHaveText("확정");
    await expect(panel.getByRole("table", { name: "이력" }).locator("tbody tr").first()).toContainText("대기 → 확정");
    await shot("action-confirmed");

    // 5) 지난 변동으로 돌아가는 길: 메뉴에서 조치로 가서 (변동을 고르지 않은 목록) 조치의 근거에서 그 변동의 영향 범위로 간다.
    await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "조치" }).click();
    await expect(page.getByRole("heading", { name: "조치", level: 1 })).toBeVisible();
    await page.getByLabel("Pack").selectOption(pack);
    await page.getByLabel("상태").selectOption("CONFIRMED");
    const confirmed = page.getByRole("table", { name: "조치 목록" }).locator("tbody tr[data-action-id]").first();
    await expect(confirmed.getByTestId("status")).toHaveText("확정");
    await confirmed.getByRole("button", { name: "근거와 이력 보기" }).click();
    await page.getByRole("link", { name: "이 조치를 만든 변동의 영향 범위" }).first().click();
    await expect(page.getByRole("heading", { name: "영향 범위", level: 1 })).toBeVisible();
    await expect(page.getByTestId("impact-total")).toHaveText(impactTotal);

    // 6) 지난 판: 변동 감지 화면으로 돌아가, 앞서 올린 그 판의 바뀐 항목을 지난 판의 목록에서 다시 본다 (D-16).
    await page.getByRole("navigation", { name: "화면" }).getByRole("link", { name: "변동 감지" }).click();
    await page.getByLabel("Pack").selectOption(pack);
    const pastRows = page.getByRole("table", { name: "지난 판" }).locator("tbody tr");
    // 올린 판이 모두 있다 (한 쪽에 50 판까지다).
    await expect(page.getByRole("region", { name: "지난 판" }).getByTestId("pager-position")).toContainText(`전체 ${steps.length}건`);
    await pastRows
      .filter({ hasText: shown.step.inputSource })
      .filter({ hasText: shown.step.effectiveDate })
      .first()
      .getByRole("button", { name: "결과 보기" })
      .click();
    const pastResult = page.getByRole("region", { name: "지난 판의 결과" });
    // 키 열이 둘인 항목의 변동 이벤트 번호에는 역빗금이 있다 (`공정\|물질`). 선택자 안에서는 역빗금도 따옴표와 함께 달아나게 적는다.
    await expect(pastResult.locator(`tr[data-change-event-id="${shown.changeEventId.replace(/["\\]/g, "\\$&")}"]`)).toBeVisible();
    await shot("past-edition");

    // 7) 화면이 낸 오류가 없다.
    expect(pageErrors, "화면의 오류").toEqual([]);
  });
}
