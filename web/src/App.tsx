import { Navigate, NavLink, Route, Routes } from "react-router";
import { ChangesPage } from "./pages/ChangesPage";
import { RelationsPage } from "./pages/RelationsPage";

// 화면의 뼈대. 주소로 화면을 가른다 (D2 AD-257).
//
// 화면마다의 주소는 여기 한 곳에 둔다. 화면을 더하면 SCREENS 에 한 줄을 더한다.
// 모양은 꾸밈 없이 둔다. 시연 앱의 모양을 입히는 일은 뒤에 한다 (D2 AD-255).

const SCREENS = [
  { path: "/changes", label: "변동 감지", element: <ChangesPage /> },
  { path: "/relations", label: "관계 조회", element: <RelationsPage /> },
];

export function App() {
  return (
    <>
      <header>
        <strong>Tabnit Base</strong>
        <nav aria-label="화면">
          <ul>
            {SCREENS.map((screen) => (
              <li key={screen.path}>
                <NavLink to={screen.path}>{screen.label}</NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/changes" replace />} />
          {SCREENS.map((screen) => (
            <Route key={screen.path} path={screen.path} element={screen.element} />
          ))}
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </>
  );
}

function NotFound() {
  return (
    <section aria-labelledby="not-found-title">
      <h1 id="not-found-title">없는 화면입니다</h1>
      <p>주소를 확인하거나 위의 화면 목록에서 고르세요.</p>
    </section>
  );
}
