import { useCallback, useEffect, useState } from "react";
import { Navigate, NavLink, Route, Routes } from "react-router";
import { api, SESSION_ENDED, type Session } from "./api/client";
import { CONNECTION_FAILED, messageOf } from "./api/problem";
import { ActionsPage } from "./pages/ActionsPage";
import { ChangesPage } from "./pages/ChangesPage";
import { LoginPage } from "./pages/LoginPage";
import { RelationsPage } from "./pages/RelationsPage";

// 화면의 뼈대. 주소로 화면을 가른다 (D2 AD-257).
//
// 화면마다의 주소는 여기 한 곳에 둔다. 화면을 더하면 SCREENS 에 한 줄을 더한다.
// 모양은 꾸밈 없이 둔다. 시연 앱의 모양을 입히는 일은 뒤에 한다 (D2 AD-255).
//
// 로그인하지 않으면 어느 주소에서도 로그인 화면만 보인다 (E-02, D7 9절). 로그인했는지는 서버에 묻는다:
// 화면은 세션을 들고 있지 않다. 주소는 바꾸지 않으므로 로그인하면 열려던 화면이 나온다.

const SCREENS = [
  { path: "/changes", label: "변동 감지", element: <ChangesPage /> },
  { path: "/actions", label: "조치", element: <ActionsPage /> },
  { path: "/relations", label: "관계 조회", element: <RelationsPage /> },
];

const ROLE_LABELS: Record<Session["role"], string> = { ADMIN: "관리자", OFFICER: "담당자" };

const SESSION_ENDED_NOTICE = "로그인이 끝났습니다. 다시 로그인하세요.";

// 확인하고 있음, 로그인하지 않음, 로그인함, 확인하지 못함.
type SessionState =
  | { kind: "checking" }
  | { kind: "anonymous"; notice: string | null }
  | { kind: "signedIn"; session: Session }
  | { kind: "failed"; message: string };

export function App() {
  const [state, setState] = useState<SessionState>({ kind: "checking" });
  const [logoutError, setLogoutError] = useState<string | null>(null);

  // 서버에 내 정보를 묻는다. 이 응답과 함께 위조 요청 방어의 토큰 쿠키가 온다.
  const check = useCallback(async (notice: string | null) => {
    try {
      const { data, error, response } = await api.GET("/api/v1/session");
      if (data) {
        setState({ kind: "signedIn", session: data });
      } else if (response.status === 401) {
        setState({ kind: "anonymous", notice });
      } else {
        setState({ kind: "failed", message: messageOf(error, response.status) });
      }
    } catch {
      setState({ kind: "failed", message: CONNECTION_FAILED });
    }
  }, []);

  useEffect(() => {
    void check(null);
  }, [check]);

  // 쓰는 도중에 세션이 끝났다: 로그인 화면으로 돌아가고 까닭을 알린다.
  useEffect(() => {
    const ended = () =>
      setState((current) => (current.kind === "signedIn" ? { kind: "anonymous", notice: SESSION_ENDED_NOTICE } : current));
    window.addEventListener(SESSION_ENDED, ended);
    return () => window.removeEventListener(SESSION_ENDED, ended);
  }, []);

  async function logout() {
    setLogoutError(null);
    try {
      const { error, response } = await api.DELETE("/api/v1/session");
      if (!response.ok) {
        setLogoutError(messageOf(error, response.status));
        return;
      }
    } catch {
      setLogoutError(CONNECTION_FAILED);
      return;
    }
    // 로그아웃하면 토큰도 버려진다. 내 정보를 다시 물어 새 토큰을 받는다.
    await check(null);
  }

  if (state.kind === "checking") {
    return (
      <main>
        <p role="status">로그인했는지 확인하고 있습니다.</p>
      </main>
    );
  }
  if (state.kind === "failed") {
    return (
      <main>
        <p role="alert">{state.message}</p>
        <button type="button" onClick={() => void check(null)}>
          다시 시도
        </button>
      </main>
    );
  }
  if (state.kind === "anonymous") {
    return <LoginPage notice={state.notice} onLogin={(session) => setState({ kind: "signedIn", session })} />;
  }

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
        <p>
          <span data-testid="session-user">
            {state.session.loginId} ({ROLE_LABELS[state.session.role]})
          </span>{" "}
          <button type="button" onClick={() => void logout()}>
            로그아웃
          </button>
        </p>
        {logoutError && <p role="alert">{logoutError}</p>}
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
