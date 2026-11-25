import { type ReactNode, useContext, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { api, type EditionList, type EditionSubmission } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { SessionContext } from "../session";
import { formatInstant } from "../time";
import { Pager } from "./Pager";

// 지난 판의 목록과, 그 가운데 고른 판의 결과 (D-16, D7 5절 "지난 판", D2 AD-383).
// 서버의 listEditions 와 getEdition (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 목록의 차례(받은 때가 늦은 것부터)와 수(항목, 바뀐 항목, 알림)는 서버가 준 그대로다. 다시 세거나 정렬하지 않는다.
// - 고른 판의 결과는 올린 직후의 답과 같은 모양이라 같은 방식으로 그린다 (renderResult).
// - 고른 판은 주소(editionId)에 둔다: 다른 화면에 다녀와도, 주소를 건네도 같은 판이 보인다.

type List =
  | { kind: "loading" }
  | { kind: "loaded"; list: EditionList }
  | { kind: "failed"; message: string };

type Shown =
  | { kind: "none" }
  | { kind: "loading" }
  | { kind: "loaded"; result: EditionSubmission }
  | { kind: "failed"; message: string };

type Props = {
  packId: string;
  // 판을 올릴 때마다 바뀌는 값. 목록을 다시 받게 한다.
  refreshKey: number;
  renderResult: (result: EditionSubmission) => ReactNode;
};

export function PastEditions({ packId, refreshKey, renderResult }: Props) {
  const [params, setParams] = useSearchParams();
  const editionId = params.get("editionId");
  const timeZone = useContext(SessionContext)?.timeZone;
  const [offset, setOffset] = useState(0);
  const [list, setList] = useState<List>({ kind: "loading" });
  const [shown, setShown] = useState<Shown>({ kind: "none" });
  const [retry, setRetry] = useState(0);
  const latestList = useRef(0);
  const latestShown = useRef(0);

  useEffect(() => {
    // 가장 나중에 보낸 요청의 답만 쓴다.
    const request = ++latestList.current;
    setList((current) => (current.kind === "loaded" ? current : { kind: "loading" }));
    void (async () => {
      try {
        const { data, error, response } = await api.GET("/api/v1/editions", { params: { query: { packId, offset } } });
        if (request !== latestList.current) {
          return;
        }
        setList(data ? { kind: "loaded", list: data } : { kind: "failed", message: messageOf(error, response.status) });
      } catch {
        if (request === latestList.current) {
          setList({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    })();
  }, [packId, offset, refreshKey, retry]);

  // 판을 올리면 새 판이 맨 위에 들어온다. 첫 쪽으로 돌아가 그 판이 보이게 한다.
  useEffect(() => {
    setOffset(0);
  }, [refreshKey]);

  useEffect(() => {
    const request = ++latestShown.current;
    if (!editionId) {
      setShown({ kind: "none" });
      return;
    }
    setShown({ kind: "loading" });
    void (async () => {
      try {
        const { data, error, response } = await api.GET("/api/v1/edition", { params: { query: { editionId } } });
        if (request !== latestShown.current) {
          return;
        }
        setShown(data ? { kind: "loaded", result: data } : { kind: "failed", message: messageOf(error, response.status) });
      } catch {
        if (request === latestShown.current) {
          setShown({ kind: "failed", message: CONNECTION_FAILED });
        }
      }
    })();
    // 다시 처리된 뒤의 상태를 보도록, 판을 올릴 때에도 다시 받는다.
  }, [editionId, refreshKey, retry]);

  function choose(next: string | null) {
    const updated = new URLSearchParams(params);
    updated.set("packId", packId);
    if (next) {
      updated.set("editionId", next);
    } else {
      updated.delete("editionId");
    }
    setParams(updated);
  }

  return (
    <section aria-labelledby="past-editions-title">
      <h2 id="past-editions-title">지난 판</h2>
      <p>이 Pack 에 받아들인 판입니다. 최근에 받은 것이 위에 있습니다. 판을 고르면 그때의 바뀐 항목과 알림을 다시 봅니다.</p>

      {/* 고른 판의 결과는 목록의 위에 둔다: 목록이 길어도 고르자마자 보인다 (D-10 의 리허설에서 목록의 아래에 있어
          화면을 내려야 보였다). */}
      {shown.kind === "loading" && <p role="status">그 판의 결과를 불러오는 중입니다…</p>}

      {shown.kind === "failed" && (
        <p role="alert">
          {shown.message}{" "}
          <button type="button" onClick={() => setRetry((count) => count + 1)}>
            다시 불러오기
          </button>
        </p>
      )}

      {shown.kind === "loaded" &&
        (shown.result.packId === packId ? (
          renderResult(shown.result)
        ) : (
          // 주소의 판이 고른 Pack 의 것이 아니다. 다른 Pack 의 결과를 이 Pack 의 것처럼 보이지 않는다.
          <p role="status">주소의 판은 다른 Pack ({shown.result.packId}) 의 것입니다. 그 Pack 을 고르면 보입니다.</p>
        ))}

      {list.kind === "loading" && <p role="status">지난 판을 불러오는 중입니다…</p>}

      {list.kind === "failed" && (
        <p role="alert">
          {list.message}{" "}
          <button type="button" onClick={() => setRetry((count) => count + 1)}>
            다시 불러오기
          </button>
        </p>
      )}

      {list.kind === "loaded" && list.list.total === 0 && <p role="status">아직 받아들인 판이 없습니다.</p>}

      {list.kind === "loaded" && list.list.total > 0 && (
        <>
          <Pager
            total={list.list.total}
            offset={list.list.offset}
            limit={list.list.limit}
            shown={list.list.editions.length}
            onMove={setOffset}
          />
          <table>
            <caption className="same-as-heading">지난 판</caption>
            <thead>
              <tr>
                <th scope="col">받은 때</th>
                <th scope="col">입력 자료</th>
                <th scope="col">판</th>
                <th scope="col">적용일</th>
                <th scope="col">파일</th>
                <th scope="col">항목</th>
                <th scope="col">바뀐 항목</th>
                <th scope="col">알림</th>
                <th scope="col">결과</th>
              </tr>
            </thead>
            <tbody>
              {list.list.editions.map((edition) => (
                <tr key={edition.editionId} data-edition-id={edition.editionId} aria-current={edition.editionId === editionId ? "true" : undefined}>
                  <td>
                    <time dateTime={edition.receivedAt}>{formatInstant(edition.receivedAt, timeZone)}</time>
                  </td>
                  {/* 이름표는 서버가 준 것이다 (없으면 식별자와 같다). 식별자는 작게 함께 적는다. */}
                  <td>
                    <span data-testid="input-source-label">{edition.inputSourceLabel}</span>{" "}
                    {edition.inputSourceLabel !== edition.inputSourceId && (
                      <small>
                        <code>{edition.inputSourceId}</code>
                      </small>
                    )}
                  </td>
                  <td>{edition.editionNo}판</td>
                  <td>
                    <time dateTime={edition.effectiveDate}>{edition.effectiveDate}</time>
                  </td>
                  <td>{edition.fileName ?? <small>(이름 없음)</small>}</td>
                  <td data-testid="item-count">{edition.itemCount}</td>
                  <td data-testid="change-count">{edition.changeCount}</td>
                  <td data-testid="notice-count">{edition.noticeCount}</td>
                  <td>
                    {edition.editionId === editionId ? (
                      // 단추의 이름에 어느 판의 것인지 넣는다: 화면을 읽어 주는 도구로 줄마다 같은 이름이 들리지 않게 한다.
                      <button type="button" onClick={() => choose(null)} aria-label={`${edition.inputSourceLabel} ${edition.editionNo}판의 결과 닫기`}>
                        결과 닫기
                      </button>
                    ) : (
                      <button type="button" onClick={() => choose(edition.editionId)} aria-label={`${edition.inputSourceLabel} ${edition.editionNo}판의 결과 보기`}>
                        결과 보기
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

    </section>
  );
}
