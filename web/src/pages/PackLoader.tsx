import { useEffect, useRef, useState } from "react";
import { api } from "../api/client";
import type { components } from "../api/schema";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// Pack 을 골라 적재하는 칸 (카드 D-22, D7 10절, D2 AD-416). 관리자에게만 보인다.
// 서버의 listPackFolders 와 loadPack (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 칸은 Pack 을 읽거나 검증하지 않는다. 무엇이 놓여 있는지, 적재됐는지, 문제와 경고는 서버가 준 것이다.
// 수는 서버가 준 목록의 길이를 적은 것이다. Pack 은 올리지 않는다: 서버에 놓인 것을 고른다.

type Folder = components["schemas"]["PackFolder"];
type Warning = components["schemas"]["PackWarning"];
type PackProblem = components["schemas"]["PackProblem"];

type Folders =
  | { kind: "loading" }
  | { kind: "loaded"; configured: boolean; folders: Folder[] }
  | { kind: "failed"; message: string };

type Result =
  | { kind: "none" }
  | { kind: "loading" }
  | { kind: "loaded"; packId: string; outcome: string; warnings: Warning[] }
  | { kind: "invalid"; packId: string; message: string; problems: PackProblem[] }
  | { kind: "rejected"; packId: string; message: string; reasons: string[] }
  | { kind: "failed"; message: string };

const OUTCOMES: Record<string, string> = {
  LOADED: "적재했습니다",
  UPDATED: "정의를 바꿨습니다",
  UNCHANGED: "이미 있는 것과 같아 바꾸지 않았습니다",
};

type Props = {
  // 적재가 끝났다 (바뀐 것이 없어도 부른다). 화면이 Pack 의 목록을 다시 받고 그 정의를 보인다.
  onLoaded: (packId: string) => void;
};

export function PackLoader({ onLoaded }: Props) {
  const [folders, setFolders] = useState<Folders>({ kind: "loading" });
  const [chosen, setChosen] = useState("");
  // 이미 적재된 Pack 을 다시 적재하겠다고 눌러 확인을 기다리는 중인가.
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<Result>({ kind: "none" });
  const latest = useRef(0);
  // 이 화면에서 방금 적재한 Pack. 폴더의 목록을 다시 받기 전이거나 받은 것이 옛것이어도, 다시 적재할 때 확인을 받는다
  // (D2 AD-416).
  const justLoaded = useRef(new Set<string>());

  async function loadFolders() {
    const turn = ++latest.current;
    try {
      const { data, error, response } = await api.GET("/api/v1/pack-folders");
      if (turn !== latest.current) {
        return;
      }
      setFolders(
        data
          ? { kind: "loaded", configured: data.configured, folders: data.folders }
          : { kind: "failed", message: messageOf(error, response.status) },
      );
      // 고른 폴더가 서버에서 사라졌으면 고른 것을 푼다: 고르는 칸은 비어 보이는데 단추만 켜져 있지 않게 한다.
      if (data) {
        setChosen((current) => (current && !data.folders.some((folder) => folder.packId === current) ? "" : current));
      }
    } catch {
      if (turn === latest.current) {
        setFolders({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  useEffect(() => {
    void loadFolders();
  }, []);

  function choose(next: string) {
    setChosen(next);
    setConfirming(false);
    // 앞에 고른 Pack 의 결과를 새로 고른 Pack 옆에 남기지 않는다. 적재하는 중이면 그 결과를 기다린다.
    setResult((current) => (current.kind === "loading" ? current : { kind: "none" }));
  }

  function press() {
    const folder = folders.kind === "loaded" ? folders.folders.find((candidate) => candidate.packId === chosen) : undefined;
    if (folder?.loaded || justLoaded.current.has(chosen)) {
      // 정의가 바뀌면 그 뒤의 계산에 바로 닿는다. 한 번 확인을 받는다.
      setConfirming(true);
      return;
    }
    void load();
  }

  async function load() {
    const packId = chosen;
    setConfirming(false);
    setResult({ kind: "loading" });
    try {
      const { data, error, response } = await api.POST("/api/v1/pack-loads", { body: { packId } });
      if (data) {
        justLoaded.current.add(data.packId);
        setResult({ kind: "loaded", packId: data.packId, outcome: data.outcome, warnings: data.warnings });
        onLoaded(data.packId);
      } else {
        // 거부한 까닭은 서버가 준 목록 그대로다.
        const problem = error as { code?: string; problems?: PackProblem[]; reasons?: string[] } | undefined;
        const message = messageOf(error, response.status);
        if (problem?.code === "PACK_INVALID" && Array.isArray(problem.problems)) {
          setResult({ kind: "invalid", packId, message, problems: problem.problems });
        } else if (problem?.code === "PACK_CHANGE_REJECTED" && Array.isArray(problem.reasons)) {
          setResult({ kind: "rejected", packId, message, reasons: problem.reasons });
        } else {
          setResult({ kind: "failed", message });
        }
      }
    } catch {
      setResult({ kind: "failed", message: CONNECTION_FAILED });
    }
    // 적재됐는지의 표시를 다시 받는다.
    await loadFolders();
  }

  return (
    <section className="pack-loader" aria-labelledby="pack-loader-title">
      <h2 id="pack-loader-title">Pack 적재</h2>
      <p>
        <small>
          서버의 Pack 폴더에 놓인 Pack 을 골라 적재합니다. 틀린 Pack 은 적재하지 않고 문제를 모두 알립니다. 적재하는 것은
          정의뿐입니다: 개체와 관계는 판을 올려 넣습니다.
        </small>
      </p>

      {folders.kind === "loading" && <p>서버에 놓인 Pack 을 불러오는 중입니다…</p>}
      {folders.kind === "failed" && (
        <p role="alert">
          {folders.message}{" "}
          <button type="button" onClick={() => void loadFolders()}>
            다시 불러오기
          </button>
        </p>
      )}
      {folders.kind === "loaded" && !folders.configured && (
        <p>
          이 서버에는 Pack 폴더가 정해져 있지 않습니다. 설치 설정 tabnit.packs-dir 에 Pack 을 둔 폴더를 적고 서버를 다시 띄운
          뒤에 적재할 수 있습니다.
        </p>
      )}
      {folders.kind === "loaded" && folders.configured && folders.folders.length === 0 && (
        <p>서버의 Pack 폴더에 적재할 수 있는 Pack 이 없습니다. 그 폴더에 Pack 의 폴더를 둔 뒤 다시 여세요.</p>
      )}
      {folders.kind === "loaded" && folders.configured && folders.folders.length > 0 && (
        <p>
          <label htmlFor="loader-folder">서버에 둔 Pack</label>{" "}
          <select id="loader-folder" value={chosen} onChange={(e) => choose(e.target.value)}>
            <option value="">적재할 Pack 을 고르세요</option>
            {folders.folders.map((folder) => (
              <option key={folder.packId} value={folder.packId} data-pack-id={folder.packId} data-loaded={String(folder.loaded)}>
                {folder.packId}
                {folder.loaded ? " (적재됨)" : ""}
              </option>
            ))}
          </select>{" "}
          <button type="button" disabled={!chosen || confirming || result.kind === "loading"} onClick={press}>
            적재
          </button>
        </p>
      )}

      {confirming && (
        <p data-testid="reload-confirm">
          <strong>{chosen}</strong> 은(는) 이미 적재된 Pack 입니다. 다시 적재하면 서버의 폴더에 놓인 내용으로 지금의 정의를
          바꿉니다. 바뀐 정의는 그 뒤의 계산부터 쓰입니다.{" "}
          <button type="button" onClick={() => void load()}>
            다시 적재
          </button>{" "}
          <button type="button" onClick={() => setConfirming(false)}>
            그만두기
          </button>
        </p>
      )}

      {result.kind === "loading" && <p>적재하는 중입니다…</p>}
      {result.kind === "failed" && <p role="alert">{result.message}</p>}
      {result.kind === "loaded" && (
        <>
          <p data-testid="load-result">
            <strong>{result.packId}</strong>: {OUTCOMES[result.outcome] ?? result.outcome}.
            {result.warnings.length > 0 && (
              <>
                {" "}
                경고 <span data-testid="warning-count">{result.warnings.length}</span>건이 있습니다. 적재는 됐습니다.
              </>
            )}
          </p>
          {result.warnings.length > 0 && (
            <ul aria-label="적재의 경고">
              {result.warnings.map((warning, index) => (
                <li key={index}>
                  <small>
                    <code>
                      {warning.file} {warning.item}
                    </code>
                  </small>{" "}
                  {warning.message}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {result.kind === "invalid" && (
        <>
          <p role="alert">
            <strong>{result.packId}</strong> 을(를) 적재하지 않았습니다. {result.message} 문제{" "}
            <span data-testid="problem-count">{result.problems.length}</span>건을 고친 뒤 다시 적재하세요.
          </p>
          <ol aria-label="Pack 의 문제">
            {result.problems.map((problem, index) => (
              <li key={index}>
                <small>
                  <code>
                    {problem.file} {problem.item}
                  </code>
                </small>{" "}
                {problem.message}
              </li>
            ))}
          </ol>
        </>
      )}
      {result.kind === "rejected" && (
        <>
          <p role="alert">
            <strong>{result.packId}</strong> 을(를) 적재하지 않았습니다. {result.message}
          </p>
          <ol aria-label="받을 수 없는 변경">
            {result.reasons.map((reason, index) => (
              <li key={index}>{reason}</li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
}
