import { type Locator, type Page, test as anonymous } from "@playwright/test";
import { expect, test } from "./fixtures";

// D-01 수용 기준: 시나리오 화면 네 개가 저장소의 화면으로 뜬다.
//
// 네 화면은 이미 실제 API 에 붙어 있다 (D-03, D-05, D-06, D-08). 이 카드는 시연 앱의 모양을 입힌다 (D2 AD-366):
// 같은 틀(위 띠, 마크, 메뉴), 시연 앱의 색(밝은 바탕과 어두운 바탕, 기기의 설정을 따른다), 제목의 글꼴(Outfit, 제품에 담는다).
// 화면의 내용과 동작은 다른 시험이 본다. 여기서는 모양이 입혀졌는지와, 읽을 수 있는지만 본다.

const SCREENS = [
  { path: "/changes", label: "변동 감지" },
  { path: "/impact", label: "영향 범위" },
  { path: "/actions", label: "조치" },
  { path: "/relations", label: "관계 지도" },
];

// 시연 앱의 바탕색 (web/src/styles.css 의 --bg).
const PAGE_BACKGROUND = { light: "rgb(243, 245, 248)", dark: "rgb(10, 16, 28)" } as const;

// 글자와 그 뒤 바탕의 명암비 (WCAG 2 의 식). 바탕은 색이 칠해진 가장 가까운 조상의 것이다.
async function contrast(target: Locator) {
  return target.evaluate((element) => {
    const parse = (value: string) => {
      const parts = value.match(/[\d.]+/g)!.map(Number);
      return { r: parts[0]!, g: parts[1]!, b: parts[2]!, a: parts[3] ?? 1 };
    };
    const luminance = (c: { r: number; g: number; b: number }) => {
      const channel = (v: number) => {
        const s = v / 255;
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
      };
      return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b);
    };
    let background = { r: 255, g: 255, b: 255, a: 1 };
    for (let node: Element | null = element; node; node = node.parentElement) {
      const style = getComputedStyle(node);
      if (style.backgroundImage !== "none") {
        throw new Error("바탕이 그림이라 명암비를 잴 수 없습니다: " + node.tagName);
      }
      const color = parse(style.backgroundColor);
      if (color.a === 1) {
        background = color;
        break;
      }
      if (color.a !== 0) {
        throw new Error("바탕이 반투명이라 명암비를 잴 수 없습니다: " + node.tagName);
      }
    }
    const text = parse(getComputedStyle(element).color);
    const [high, low] = [luminance(text), luminance(background)].sort((a, b) => b - a);
    return (high! + 0.05) / (low! + 0.05);
  });
}

async function expectReadable(target: Locator, what: string) {
  await expect(target, what).toBeVisible();
  expect(await contrast(target), `${what} 의 명암비`).toBeGreaterThanOrEqual(4.5);
}

async function headingFont(page: Page) {
  return page.getByRole("heading", { level: 1 }).evaluate((element) => getComputedStyle(element).fontFamily);
}

for (const scheme of ["light", "dark"] as const) {
  test(`네 화면이 같은 틀과 시연 앱의 모양으로 뜬다 (${scheme === "light" ? "밝은" : "어두운"} 바탕)`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });
    for (const screen of SCREENS) {
      await page.goto(screen.path);
      await expect(page.getByRole("heading", { name: screen.label, level: 1 })).toBeVisible();

      // 같은 틀: 위 띠에 마크와 이름, 메뉴에 지금의 화면이 표시된다.
      const banner = page.getByRole("banner");
      const mark = banner.getByTestId("brand-mark");
      await expect(mark).toBeVisible();
      expect(await mark.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0), "마크 그림이 실제로 그려진다").toBe(true);
      await expect(banner.getByText("Tabnit Base")).toBeVisible();
      const current = banner.getByRole("link", { name: screen.label });
      await expect(current).toHaveAttribute("aria-current", "page");
      // 메뉴는 가로로 놓인다 (꾸미지 않은 목록은 세로다).
      const boxes = await Promise.all(SCREENS.map((one) => banner.getByRole("link", { name: one.label }).boundingBox()));
      expect(new Set(boxes.map((box) => Math.round(box!.y))).size, "메뉴가 한 줄에 놓인다").toBe(1);

      // 시연 앱의 바탕색. 기기의 설정을 따른다.
      expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(PAGE_BACKGROUND[scheme]);
      // 제목의 글꼴.
      expect(await headingFont(page)).toMatch(/^"?Outfit"?,/);

      // 읽을 수 있다.
      await expectReadable(page.getByRole("heading", { name: screen.label, level: 1 }), `${screen.label} 의 제목`);
      await expectReadable(current, `${screen.label} 의 메뉴`);
      await expectReadable(banner.getByRole("link", { name: SCREENS.find((one) => one !== screen)!.label }), "다른 화면의 메뉴");
      await expectReadable(banner.getByTestId("session-user"), "로그인한 사람");
      await expectReadable(banner.getByRole("button", { name: "로그아웃" }), "로그아웃 단추");
    }
  });

  test(`표, 단추, 입력 칸, 알림을 읽을 수 있다 (${scheme === "light" ? "밝은" : "어두운"} 바탕)`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme });

    // 조치 화면: 표와 입력 칸과 단추.
    await page.goto("/actions?packId=e2e-actions&status=ALL");
    const table = page.getByRole("table").first();
    await expectReadable(table.locator("thead th").first(), "표의 머리");
    await expectReadable(table.locator("tbody td").first(), "표의 칸");
    await expectReadable(table.locator("tbody a, tbody button").first(), "표 안의 누르는 것");
    await expectReadable(page.getByLabel("Pack"), "고르는 칸");
    await expectReadable(page.locator("main small").first(), "작은 글자");
    await expectReadable(page.locator("main code").first(), "식별자");

    // 변동 감지 화면: 보내는 단추.
    await page.goto("/changes");
    await expectReadable(page.getByRole("button", { name: "올리기" }), "보내는 단추");

    // 영향 범위 화면: 안내와 알림.
    await page.goto("/impact");
    await expectReadable(page.getByTestId("impact-guide"), "안내");
    await expectReadable(page.getByTestId("impact-guide").getByRole("link").first(), "글 안의 이어 가기");
    await page.goto(`/impact?${new URLSearchParams({ changeEventId: "e2e-actions|없는|변동" })}`);
    await expectReadable(page.getByRole("alert"), "알림");
  });
}

test("글꼴과 그림을 밖에서 받지 않는다: 제품에 담긴 것만 쓴다", async ({ page, baseURL }) => {
  const origins = new Set<string>();
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.protocol === "http:" || url.protocol === "https:") {
      origins.add(url.origin);
    }
  });

  await page.goto("/actions?packId=e2e-actions&status=ALL");
  await expect(page.getByRole("heading", { name: "조치", level: 1 })).toBeVisible();
  // 제목의 글꼴(Outfit)이 실제로 실렸다. 이름만 적히고 다른 글꼴로 그려지면 안 된다.
  expect(await page.evaluate(() => document.fonts.ready.then(() => document.fonts.check('700 24px "Outfit"')))).toBe(true);
  expect(await page.evaluate(() => [...document.fonts].some((face) => face.family.replace(/"/g, "") === "Outfit" && face.status === "loaded"))).toBe(true);

  expect([...origins]).toEqual([new URL(baseURL!).origin]);
});

anonymous("로그인 화면에도 마크와 같은 모양이 입혀진다", async ({ page }) => {
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/changes");
    await expect(page.getByRole("heading", { name: "로그인", level: 1 })).toBeVisible();

    const mark = page.getByTestId("brand-mark");
    await expect(mark).toBeVisible();
    expect(await mark.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true);
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe(PAGE_BACKGROUND[scheme]);
    await expectReadable(page.getByRole("heading", { name: "로그인", level: 1 }), "로그인 화면의 제목");
    await expectReadable(page.getByText("Tabnit Base"), "로그인 화면의 이름");
    await expectReadable(page.getByLabel("아이디"), "아이디 칸");
    await expectReadable(page.getByRole("button", { name: "로그인" }), "로그인 단추");
  }
});
