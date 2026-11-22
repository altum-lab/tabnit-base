import { type APIRequestContext, type BrowserContext, test as base, expect, request as apiRequest } from "@playwright/test";

// 화면 시험이 함께 쓰는 준비: 로그인한 채로 시작한다 (E-02).
//
// 사용자는 서버의 시험용 실행이 만들어 둔다 (server/api/src/test 의 E2eSeedData). 아이디와 비밀번호는 지어낸 시험용 값이다.
// 로그인하지 않은 상태를 보는 시험(session.spec.ts)은 이 파일의 test 가 아니라 @playwright/test 의 test 를 쓴다.

export const E2E_USER = { loginId: "e2e-officer", password: "지어낸-화면-시험-비밀번호-1" };

// 다른 사람. 관리자는 담당자의 일을 모두 한다 (D2 AD-270). 다른 사람이 먼저 바꾼 경우를 만드는 데 쓴다.
export const E2E_OTHER_USER = { loginId: "e2e-admin", password: "지어낸-화면-시험-비밀번호-2" };

const TOKEN_COOKIE = "XSRF-TOKEN";

async function token(context: BrowserContext) {
  const cookie = (await context.cookies()).find((candidate) => candidate.name === TOKEN_COOKIE);
  if (!cookie) {
    throw new Error("서버가 위조 요청 방어의 토큰 쿠키를 주지 않았습니다.");
  }
  return cookie.value;
}

// 브라우저가 하는 대로: 내 정보를 물어 토큰을 받고, 그 토큰을 실어 로그인한다.
export async function login(context: BrowserContext, user = E2E_USER) {
  await context.request.get("/api/v1/session");
  const response = await context.request.post("/api/v1/session", {
    data: user,
    headers: { "X-XSRF-TOKEN": await token(context) },
  });
  expect(response.status(), "시험용 사용자로 로그인하지 못했습니다").toBe(200);
}

// 다른 사람으로 로그인해 서버를 직접 부르는 길. 화면의 세션과 따로다. 다 쓰면 dispose 한다.
export async function apiAs(baseURL: string | undefined, user: { loginId: string; password: string }) {
  const tokenOf = async (api: APIRequestContext) => {
    const cookie = (await api.storageState()).cookies.find((candidate) => candidate.name === TOKEN_COOKIE);
    if (!cookie) {
      throw new Error("서버가 위조 요청 방어의 토큰 쿠키를 주지 않았습니다.");
    }
    return cookie.value;
  };
  const anonymous = await apiRequest.newContext({ baseURL });
  await anonymous.get("/api/v1/session");
  const response = await anonymous.post("/api/v1/session", {
    data: user,
    headers: { "X-XSRF-TOKEN": await tokenOf(anonymous) },
  });
  expect(response.status(), "다른 시험용 사용자로 로그인하지 못했습니다").toBe(200);
  // 로그인하면 토큰이 바뀔 수 있다. 다시 받아 그 뒤의 요청에 싣는다.
  await anonymous.get("/api/v1/session");
  const api = await apiRequest.newContext({
    baseURL,
    storageState: await anonymous.storageState(),
    extraHTTPHeaders: { "X-XSRF-TOKEN": await tokenOf(anonymous) },
  });
  await anonymous.dispose();
  return api;
}

export const test = base.extend<{ request: APIRequestContext }>({
  context: async ({ context }, use) => {
    await login(context);
    await use(context);
  },
  // 시험이 서버를 직접 부를 때 쓴다. 화면과 같은 사람으로 로그인되어 있고, 바꾸는 요청에 토큰이 실린다.
  request: async ({ context, baseURL }, use) => {
    const api = await apiRequest.newContext({
      baseURL,
      storageState: await context.storageState(),
      extraHTTPHeaders: { "X-XSRF-TOKEN": await token(context) },
    });
    await use(api);
    await api.dispose();
  },
});

export { expect };
