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
      command: `./gradlew :api:bootTestRun --args="--server.port=${API_PORT} --management.endpoint.health.probes.enabled=true"`,
      cwd: "../server",
      // 서버가 뜨고 지어낸 자료까지 다 넣어야 준비됨(200)으로 답한다. 그 전에는 503 이다.
      // API 는 로그인해야 열리므로 준비됐는지는 이 주소로 본다.
      url: `http://localhost:${API_PORT}/actuator/health/readiness`,
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
