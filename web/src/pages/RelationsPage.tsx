import { type FormEvent, useState } from "react";
import { api, type RelationsAsOf } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { Pager } from "./Pager";

// 관계 조회 화면. 시점 조회 API (docs/api/openapi.yaml 의 getRelationsAsOf) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 오늘을 정하지 않는다. 조회 시점을 비우면 서버가 정하고, 화면은 서버가 답한 날짜를 보여 준다.
// - 목록을 다시 정렬하거나 세지 않는다. 서버가 준 순서 그대로 그린다.

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; result: RelationsAsOf }
  | { kind: "failed"; message: string };

export function RelationsPage() {
  const [entityId, setEntityId] = useState("");
  const [asOf, setAsOf] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });

  // 조회한 개체와 조회 시점으로 한 쪽을 받는다. 다른 쪽으로 넘길 때는 서버가 답한 조회 시점을 그대로 보낸다:
  // 조회 시점을 비워 서버가 정했더라도 쪽마다 같은 날짜로 본다.
  async function load(query: { entityId: string; asOf?: string; offset?: number }) {
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/relations", {
        // 조회 시점을 비웠으면 항목을 보내지 않는다. 그러면 서버가 날짜를 정한다.
        params: { query },
      });
      if (data) {
        setView({ kind: "loaded", result: data });
      } else {
        setView({ kind: "failed", message: messageOf(error, response.status) });
      }
    } catch {
      // 응답을 받지 못했다 (연결 실패 등).
      setView({ kind: "failed", message: CONNECTION_FAILED });
    }
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void load(asOf ? { entityId, asOf } : { entityId });
  }

  return (
    <section aria-labelledby="relations-title">
      <h1 id="relations-title">관계 조회</h1>

      <form onSubmit={search}>
        <p>
          <label htmlFor="entity-id">개체 id</label>{" "}
          <input
            id="entity-id"
            name="entityId"
            value={entityId}
            onChange={(e) => setEntityId(e.target.value)}
            required
            size={40}
          />
        </p>
        <p>
          <label htmlFor="as-of">조회 시점</label>{" "}
          <input id="as-of" name="asOf" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />{" "}
          <small>비우면 서버가 오늘로 정합니다.</small>
        </p>
        <button type="submit" disabled={view.kind === "loading"}>
          조회
        </button>
      </form>

      {view.kind === "loading" && <p role="status">불러오는 중입니다…</p>}

      {view.kind === "failed" && <p role="alert">{view.message}</p>}

      {view.kind === "loaded" && (
        <Result
          result={view.result}
          onMove={(offset) => void load({ entityId: view.result.entityId, asOf: view.result.asOf, offset })}
        />
      )}
    </section>
  );
}

function Result({ result, onMove }: { result: RelationsAsOf; onMove: (offset: number) => void }) {
  return (
    <section aria-label="조회 결과">
      <p>
        조회 시점 <time data-testid="answered-as-of">{result.asOf}</time> 기준
      </p>

      {result.total > 0 && (
        <Pager
          total={result.total}
          offset={result.offset}
          limit={result.limit}
          shown={result.relations.length}
          onMove={onMove}
        />
      )}

      {result.total === 0 ? (
        <p role="status">이 조회 시점에 유효한 관계가 없습니다.</p>
      ) : result.relations.length === 0 ? (
        <p role="status">이 자리에는 관계가 없습니다. 이전으로 돌아가세요.</p>
      ) : (
        <table>
          <caption>관계 목록</caption>
          <thead>
            <tr>
              <th scope="col">관계 유형</th>
              <th scope="col">출발 개체</th>
              <th scope="col">도착 개체</th>
              <th scope="col">시작일</th>
              <th scope="col">종료일</th>
              <th scope="col">출처 (입력 자료, 판)</th>
            </tr>
          </thead>
          <tbody>
            {result.relations.map((relation) => (
              <tr key={relation.id} data-relation-id={relation.id}>
                <td>{relation.relationType}</td>
                <td>{relation.sourceEntityId}</td>
                <td>{relation.targetEntityId}</td>
                <td>{relation.validFrom}</td>
                <td>{relation.validTo ?? "종료일 없음"}</td>
                <td>
                  {relation.provenance.inputSourceId}, {relation.provenance.editionNo}판
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
