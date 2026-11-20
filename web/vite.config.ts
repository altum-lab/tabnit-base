import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// 개발 서버는 /api 로 오는 요청을 서버로 넘긴다. 화면과 API 가 같은 출처가 되어
// 서버에 교차 출처 설정을 따로 두지 않아도 된다.
// 화면 흐름 시험은 개발 중인 서버와 부딪히지 않도록 다른 포트를 환경 변수로 넘긴다 (playwright.config.ts).
const apiUrl = process.env.TABNIT_API_URL ?? "http://localhost:8080";
const webPort = Number(process.env.TABNIT_WEB_PORT ?? 5173);

export default defineConfig({
  plugins: [react()],
  server: {
    port: webPort,
    strictPort: true,
    proxy: {
      "/api": apiUrl,
    },
  },
});
