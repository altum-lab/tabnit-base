import { type FormEvent, useState } from "react";
import { api, type Session } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// 로그인 화면 (E-02, D7 9절).
//
// 아이디와 비밀번호를 서버에 보내고, 서버가 준 문구를 그대로 보여 준다.
// 왜 실패했는지(없는 아이디, 틀린 비밀번호, 잠시 막힘)는 서버가 가리지 않으므로 화면도 가리지 않는다.

export function LoginPage({ notice, onLogin }: { notice: string | null; onLogin: (session: Session) => void }) {
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const { data, error: problem, response } = await api.POST("/api/v1/session", { body: { loginId, password } });
      if (data) {
        onLogin(data);
        return;
      }
      setError(messageOf(problem, response.status));
    } catch {
      setError(CONNECTION_FAILED);
    }
    // 실패하면 비밀번호 칸을 비운다.
    setPassword("");
    setBusy(false);
  }

  return (
    <main>
      <section aria-labelledby="login-title">
        <h1 id="login-title">로그인</h1>
        {notice && <p role="status">{notice}</p>}
        <form onSubmit={submit}>
          <p>
            <label>
              아이디{" "}
              <input
                name="loginId"
                autoComplete="username"
                autoCapitalize="none"
                value={loginId}
                onChange={(event) => setLoginId(event.target.value)}
                required
              />
            </label>
          </p>
          <p>
            <label>
              비밀번호{" "}
              <input
                name="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
              />
            </label>
          </p>
          <button type="submit" disabled={busy}>
            로그인
          </button>
        </form>
        {error && <p role="alert">{error}</p>}
      </section>
    </main>
  );
}
