import { defineConfig, devices } from "@playwright/test";

// 화면 흐름 시험. 실제 서버와 실제 PostgreSQL 에 붙는다.
// 서버는 시험용 실행(bootTestRun)으로 띄운다: Testcontainers 가 빈 PostgreSQL 을 띄우고,
// 시험 쪽 코드가 지어낸 자료를 넣는다 (server/api/src/test 의 E2eSeedData). Docker 가 켜져 있어야 한다.
// 개발 중에 띄워 둔 서버(8080)와 화면(5173)을 건드리지 않도록 시험 전용 포트를 쓴다.
const API_PORT = 18080;
const WEB_PORT = 15173;

export default defineConfig({
  testDir: "./tests",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    locale: "ko-KR",
    // 실패하면 그 화면을 남긴다.
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: `./gradlew :api:bootTestRun --args=--server.port=${API_PORT}`,
      cwd: "../server",
      // 지어낸 자료의 개체를 조회해 200 이 나오면 서버와 자료가 준비된 것이다.
      url: `http://localhost:${API_PORT}/api/v1/relations?entityId=e2e%7CT%7Calone&asOf=2026-03-01`,
      timeout: 240_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npm run dev",
      env: { TABNIT_API_URL: `http://localhost:${API_PORT}`, TABNIT_WEB_PORT: String(WEB_PORT) },
      url: `http://localhost:${WEB_PORT}`,
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
});
