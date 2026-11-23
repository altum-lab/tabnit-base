import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router";
import { App } from "./App";
// 제목의 글꼴 (D2 AD-366). 제품에 담아 준다: 밖에서 내려받지 않는다. 영문과 숫자만 든 것이고 한글은 PC 의 글꼴로 그린다.
import "@fontsource/outfit/latin-600.css";
import "@fontsource/outfit/latin-700.css";
import "./styles.css";

const root = document.getElementById("root");
if (!root) {
  throw new Error("index.html 에 id 가 root 인 요소가 없습니다.");
}
createRoot(root).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
