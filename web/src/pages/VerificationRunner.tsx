import { useEffect, useRef, useState } from "react";
import { api, type VerificationResults } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// 검증 질문을 화면에서 돌리는 칸 (카드 E-23, D7 10절 "검증 질문을 화면에서 돌린다", D2 AD-422). 관리자에게만 보인다.
// 서버의 getVerificationResults (docs/api/openapi.yaml) 의 답을 그대로 보여 준다.
//
// 이 칸은 견주지 않는다. 통과와 실패, 수, 기대한 값과 실제 값, 차례는 서버가 준 것이다.
// 자료를 바꾸지 않는다: 서버가 읽기만 해서 묻는다.

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; results: VerificationResults }
  | { kind: "failed"; message: string };

export function VerificationRunner({ packId }: { packId: string }) {
  const [view, setView] = useState<View>({ kind: "idle" });
  const latest = useRef(0);

  // Pack 을 바꾸면 앞의 결과를 비운다: 다른 Pack 의 결과를 이 Pack 의 것처럼 보이지 않는다.
  useEffect(() => {
    latest.current++;
    setView({ kind: "idle" });
  }, [packId]);

  async function run() {
    const turn = ++latest.current;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/verification-results", { params: { query: { packId } } });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", results: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  return (
    <section className="verification-runner" aria-labelledby="verification-runner-title">
      <h2 id="verification-runner-title">검증 질문 돌리기</h2>
      <p>
        <small>
          이 Pack 의 검증 질문을 지금의 저장소에 물어 정답과 견줍니다. 자료를 바꾸지 않습니다. 저장소의 판이 시나리오의
          단계와 꼭 같을 때만 묻습니다 (시나리오의 판만 차례대로 올린 저장소).
        </small>
      </p>
      <p>
        <button type="button" onClick={() => void run()} disabled={view.kind === "loading"}>
          검증 질문 돌리기
        </button>
      </p>

      {view.kind === "loading" && <p role="status">검증 질문을 묻는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && !view.results.available && (
        <p role="alert" data-testid="verification-unavailable">
          {view.results.unavailableReason}
        </p>
      )}
      {view.kind === "loaded" &&
        view.results.available &&
        view.results.scenarios.map((scenario) => (
          <div
            key={scenario.fileName}
            data-testid="verification-scenario"
            data-scenario-id={scenario.scenarioId}
            data-matched={String(scenario.matched)}
          >
            <h3>
              시나리오 {scenario.scenarioId} <small>{scenario.title}</small>
            </h3>
            {!scenario.matched && (
              <p role="alert" data-testid="verification-mismatch">
                묻지 않았습니다. 저장소의 판이 이 시나리오와 다릅니다: {scenario.mismatch}
              </p>
            )}
            {scenario.matched && (
              <>
                <p role="status" data-testid="verification-summary">
                  통과 {scenario.passedCount} / {scenario.questionCount}.{" "}
                  {scenario.repeatable
                    ? "두 번 물은 결과가 같습니다."
                    : `두 번 물은 결과가 다릅니다 (${scenario.differingQuestionIds.join(", ")}).`}
                </p>
                <table>
                  <caption>질문마다의 결과</caption>
                  <thead>
                    <tr>
                      <th scope="col">질문</th>
                      <th scope="col">종류</th>
                      <th scope="col">물은 것</th>
                      <th scope="col">결과</th>
                    </tr>
                  </thead>
                  <tbody>
                    {scenario.results.map((result) => (
                      <tr key={result.id} data-question-id={result.id} data-passed={String(result.passed)}>
                        <td>{result.id}</td>
                        <td>{result.kindLabel}</td>
                        <td>{result.label}</td>
                        <td>
                          {result.passed ? (
                            "통과"
                          ) : (
                            <>
                              <strong>실패</strong>
                              {result.reason !== null && <div data-testid="question-reason">{result.reason}</div>}
                              {result.expected !== null && (
                                <div data-testid="question-expected" className="changed-value">
                                  기대: {result.expected}
                                </div>
                              )}
                              {result.actual !== null && (
                                <div data-testid="question-actual" className="changed-value">
                                  실제: {result.actual}
                                </div>
                              )}
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </>
            )}
          </div>
        ))}
    </section>
  );
}
