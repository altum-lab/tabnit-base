import { useEffect, useRef, useState } from "react";
import { api, type ConstraintViolationList } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";
import { Pager } from "./Pager";

// 고른 Pack 의 자료가 조회 시점에 Pack 의 제약을 어긴 곳 (D-18, D7 4절 "제약의 위반", D2 AD-390, AD-395).
// 서버의 listConstraintViolations (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 위반의 차례(심각한 것부터)와 수는 서버가 준 그대로다. 다시 세거나 정렬하지 않는다.
// - 조회 시점을 비우면 서버가 정하고, 화면은 서버가 답한 날짜를 보여 준다. 화면은 오늘을 정하지 않는다.
// - 자료가 한도보다 커서 서버가 검사하지 못했으면 위반이 없다고 하지 않고 그 사실을 알린다.
// - 문구는 서버가 만든 것이다. 화면은 지어내지 않는다.

const SEVERITY_LABEL: Record<string, string> = { VIOLATION: "위반", WARNING: "경고", INFO: "알림" };

type View =
  | { kind: "loading" }
  | { kind: "loaded"; list: ConstraintViolationList }
  | { kind: "failed"; message: string };

type Props = {
  packId: string;
  // 판을 올릴 때마다 바뀌는 값. 자료가 바뀌었으니 다시 받게 한다.
  refreshKey: number;
};

export function ConstraintViolations({ packId, refreshKey }: Props) {
  // 조회 시점: 칸에 적는 중인 값(draft)과 서버에 물은 값(asOf)을 따로 둔다. 날짜를 글쇠로 적는 동안의 값
  // (연도를 한 자리씩)마다 묻지 않는다: 물을 때마다 서버가 검사하고 감사 기록을 남긴다. 칸을 떠나거나 Enter 를 누를 때 묻는다.
  const [draft, setDraft] = useState("");
  const [asOf, setAsOf] = useState("");
  const [offset, setOffset] = useState(0);
  const [seenRefresh, setSeenRefresh] = useState(refreshKey);
  const [view, setView] = useState<View>({ kind: "loading" });
  const [retry, setRetry] = useState(0);
  const latest = useRef(0);

  useEffect(() => {
    // 가장 나중에 보낸 요청의 답만 쓴다.
    const request = ++latest.current;
    setView((current) => (current.kind === "loaded" ? current : { kind: "loading" }));
    void (async () => {
      try {
        const query = asOf ? { packId, asOf, offset } : { packId, offset };
        const { data, error, response } = await api.GET("/api/v1/constraint-violations", { params: { query } });
        if (request !== latest.current) {
          return;
        }
        setView(data ? { kind: "loaded", list: data } : { kind: "failed", message: messageOf(error, response.status) });
      } catch {
        if (request === latest.current) {
          setView({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    })();
  }, [packId, asOf, offset, refreshKey, retry]);

  // 판을 올리면 첫 쪽부터 다시 본다. 그리는 중에 맞춘다: 따로 효과로 맞추면 옛 자리로 한 번 더 묻게 된다.
  if (seenRefresh !== refreshKey) {
    setSeenRefresh(refreshKey);
    setOffset(0);
  }

  // 조회 시점을 정한다. 첫 쪽부터 다시 본다.
  function commit(next: string) {
    if (next !== asOf) {
      setAsOf(next);
      setOffset(0);
    }
  }

  return (
    <section aria-labelledby="constraint-violations-title">
      <h2 id="constraint-violations-title">제약의 위반</h2>
      <p>
        이 Pack 의 자료가 Pack 의 제약을 어긴 곳입니다. 물을 때마다 조회 시점의 자료에 제약을 걸어 봅니다. 조치로
        이어지지 않습니다.
      </p>
      <p>
        <label htmlFor="violations-as-of-input">조회 시점</label>{" "}
        <input
          id="violations-as-of-input"
          name="violationsAsOf"
          type="date"
          // 서버가 받는 날짜의 범위다 (D7 2절). 연도를 네 자리까지만 적게 한다: 범위를 주지 않으면 브라우저가 여섯 자리까지
          // 받아, 글쇠로 20270102 를 치면 70102년이 된다.
          min="0001-01-01"
          max="9999-12-31"
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            // 비우는 것은 바로 묻는다 (지우기 단추로 지운 경우: 칸을 떠나지 않는다).
            if (event.target.value === "") {
              commit("");
            }
          }}
          onBlur={() => commit(draft)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              commit(draft);
            }
          }}
        />{" "}
        <small>날짜를 넣고 Enter 를 누르거나 칸을 떠나면 그 날짜로 봅니다. 비우면 서버가 오늘로 정합니다.</small>
      </p>

      {view.kind === "loading" && <p role="status">제약의 위반을 불러오는 중입니다…</p>}

      {view.kind === "failed" && (
        <p role="alert">
          {view.message}{" "}
          <button type="button" onClick={() => setRetry((count) => count + 1)}>
            다시 불러오기
          </button>
        </p>
      )}

      {view.kind === "loaded" && (
        <>
          <p>
            조회 시점 <time data-testid="violations-as-of" dateTime={view.list.asOf}>{view.list.asOf}</time> 기준
          </p>

          {!view.list.checked && (
            // 검사하지 못한 것을 위반이 없는 것으로 보이지 않는다.
            <p role="status" data-testid="violations-not-checked">
              자료가 한 번에 검사하는 한도보다 커서 검사하지 못했습니다 (개체 {view.list.entityCount}, 관계{" "}
              {view.list.relationCount}, 속성 값 {view.list.valueCount}. 한도는 이 셋을 더해 {view.list.maxRows} 입니다).
              위반이 없다는 뜻이 아닙니다. 관리자에게 한도를 늘려 달라고 하세요.
            </p>
          )}

          {view.list.checked && view.list.total === 0 && (
            <p role="status" data-testid="violations-none">
              조회 시점 {view.list.asOf} 에 제약의 위반이 없습니다.
            </p>
          )}

          {view.list.checked && view.list.total > 0 && (
            <>
              <p>
                위반 <span data-testid="violation-total">{view.list.total}건</span>
              </p>
              <Pager
                total={view.list.total}
                offset={view.list.offset}
                limit={view.list.limit}
                shown={view.list.violations.length}
                onMove={setOffset}
              />
              {view.list.violations.length === 0 ? (
                <p role="status">이 자리에는 위반이 없습니다. 이전으로 돌아가세요.</p>
              ) : (
                <table className="constraint-violations">
                  <caption className="same-as-heading">제약의 위반</caption>
                  <thead>
                    <tr>
                      <th scope="col">심각도</th>
                      <th scope="col">개체</th>
                      <th scope="col">어긴 곳</th>
                      <th scope="col">값</th>
                      <th scope="col">내용</th>
                      <th scope="col">제약</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.list.violations.map((violation, index) => (
                      // 같은 글자의 위반이 둘일 수 있다 (한 제약에 같은 종류를 값 여럿으로 적은 경우). 자리로 가린다.
                      <tr key={`${view.list.offset}-${index}`} data-severity={violation.severity}>
                        <td data-testid="violation-severity">{SEVERITY_LABEL[violation.severity] ?? violation.severity}</td>
                        <td data-testid="violation-entity">
                          {violation.entity ? <EntityName entity={violation.entity} /> : <small>(개체 없음)</small>}
                        </td>
                        <td data-testid="violation-path">
                          {violation.pathKind === "NONE" ? (
                            <small>(개체 자신)</small>
                          ) : violation.pathLabel ? (
                            <>
                              {violation.pathLabel}{" "}
                              <small>
                                <code>{violation.pathName}</code>
                              </small>
                            </>
                          ) : (
                            <code>{violation.pathName}</code>
                          )}
                        </td>
                        <td data-testid="violation-value">
                          {violation.valueEntity ? <EntityName entity={violation.valueEntity} /> : violation.value}
                        </td>
                        <td data-testid="violation-message">{violation.message}</td>
                        <td data-testid="violation-constraint">
                          {violation.constraintName ?? <small>(이름 없는 제약)</small>}{" "}
                          <small>
                            <code>{violation.constraintKind}</code>
                          </small>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}
