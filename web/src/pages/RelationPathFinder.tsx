import { type FormEvent, useEffect, useRef, useState } from "react";
import { api, type RelationPath } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";

// 두 개체 사이의 가장 짧은 경로를 찾는 칸 (카드 A-18, D7 4절 "두 개체 사이의 경로", D2 AD-413).
// 서버의 getRelationPath (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 칸은 계산하지 않는다.
// - 경로를 찾거나 여럿 가운데 고르지 않는다. 걸음과 그 차례, 길이는 서버가 준 것이다.
// - 오늘을 정하지 않는다. 조회 시점이 비어 있으면 서버가 정하고, 서버가 답한 날짜를 보여 준다.

type Found =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; path: RelationPath }
  | { kind: "failed"; message: string };

type Props = {
  // 출발 개체와 도착 개체의 id. 화면(RelationsPage)이 들고 있다: 개체 찾기에서 골라 넣을 수 있다.
  from: string;
  to: string;
  onFrom: (entityId: string) => void;
  onTo: (entityId: string) => void;
  // 화면의 조회 시점. 비어 있으면 보내지 않는다.
  asOf: string;
};

export function RelationPathFinder({ from, to, onFrom, onTo, asOf }: Props) {
  const [found, setFound] = useState<Found>({ kind: "idle" });
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  // 마지막으로 찾은 두 개체. 조회 시점이 바뀌면 이 둘의 경로를 그 날짜로 다시 받는다.
  const asked = useRef<{ from: string; to: string } | null>(null);

  // 화면의 조회 시점이 바뀌면 찾아 둔 경로를 그 날짜로 다시 받는다: 지도와 경로가 다른 날짜를 보이지 않는다.
  useEffect(() => {
    if (asked.current !== null) {
      void fetchPath(asked.current.from, asked.current.to);
    }
    // 조회 시점이 바뀔 때만 다시 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asOf]);

  // 출발이나 도착을 고치면 앞의 결과를 비운다: 적힌 두 개체의 것이 아닌 결과가 남지 않는다.
  useEffect(() => {
    if (asked.current !== null && (asked.current.from !== from || asked.current.to !== to)) {
      asked.current = null;
      latest.current++;
      setFound({ kind: "idle" });
    }
  }, [from, to]);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    asked.current = { from, to };
    void fetchPath(from, to);
  }

  async function fetchPath(fromEntityId: string, toEntityId: string) {
    const turn = ++latest.current;
    setFound({ kind: "loading" });
    try {
      const query = { fromEntityId, toEntityId, ...(asOf ? { asOf } : {}) };
      const { data, error, response } = await api.GET("/api/v1/relation-path", { params: { query } });
      if (turn !== latest.current) {
        return;
      }
      setFound(data ? { kind: "loaded", path: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setFound({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  return (
    <section className="path-finder" aria-labelledby="path-finder-title">
      <h2 id="path-finder-title">경로 찾기</h2>
      <form onSubmit={search}>
        <p>
          <label htmlFor="path-from">경로의 출발</label>{" "}
          <input id="path-from" name="fromEntityId" value={from} onChange={(e) => onFrom(e.target.value)} required size={32} />{" "}
          <label htmlFor="path-to">경로의 도착</label>{" "}
          <input id="path-to" name="toEntityId" value={to} onChange={(e) => onTo(e.target.value)} required size={32} />{" "}
          <button type="submit" disabled={found.kind === "loading"}>
            경로 찾기
          </button>
        </p>
        <p>
          <small>
            두 개체의 id 를 적거나 위의 개체 찾기에서 골라 넣습니다. 아래의 조회 시점에 유효한 관계로 이은 가장 짧은 경로
            하나를 찾습니다. 관계의 방향은 가리지 않습니다.
          </small>
        </p>
      </form>

      {found.kind === "loading" && <p>경로를 찾는 중입니다…</p>}
      {found.kind === "failed" && <p role="alert">{found.message}</p>}
      {found.kind === "loaded" && <PathResult path={found.path} />}
    </section>
  );
}

function PathResult({ path }: { path: RelationPath }) {
  const asOf = <span data-testid="path-as-of">{path.asOf}</span>;
  if (!path.found) {
    return path.limitReached ? (
      <p role="status">
        {/* 한도는 둘이다: 경로의 길이와 찾은 개체의 수. 어느 쪽에서 멈췄는지는 서버가 알려 주지 않는다. */}
        조회 시점 {asOf} 에 한도 안에서 찾지 못했습니다. 길이 {path.maxLength} 을 넘는 경로는 찾지 않고, 이어진 개체가
        많으면 그보다 일찍 멈춥니다. 찾지 못한 경로가 있을 수 있습니다.
      </p>
    ) : (
      <p role="status">조회 시점 {asOf} 에 유효한 경로가 없습니다. 두 개체는 이 날짜에 이어져 있지 않습니다.</p>
    );
  }
  return (
    <>
      <p>
        조회 시점 {asOf} 기준, 길이 <strong data-testid="path-length">{path.length}</strong> 의 가장 짧은 경로입니다.
      </p>
      {path.steps.length === 0 ? (
        <p role="status">두 개체가 같은 개체입니다.</p>
      ) : (
        <ol aria-label="경로의 걸음">
          {path.steps.map((step) => (
            <li key={step.stepNo} data-relation-id={step.relation.id}>
              <EntityName entity={step.from} />{" "}
              {/* 화살표는 관계에 적힌 방향이다: 걸어간 방향과 다를 수 있다. */}
              <strong>
                {step.relation.sourceEntityId === step.from.entityId ? "—" : "←"} {step.relation.relationType}{" "}
                {step.relation.sourceEntityId === step.from.entityId ? "→" : "—"}
              </strong>{" "}
              <EntityName entity={step.to} />
            </li>
          ))}
        </ol>
      )}
      {path.steps.length > 0 && (
        <p>
          <small>화살표는 관계에 적힌 방향(출발에서 도착)입니다. 길이가 같은 경로가 여럿이면 서버가 정한 하나입니다.</small>
        </p>
      )}
    </>
  );
}
