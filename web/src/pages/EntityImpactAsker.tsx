import { type FormEvent, useRef, useState } from "react";
import { Link } from "react-router";
import { api, type EntityImpact } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { EntityName } from "./EntityName";
import { Pager } from "./Pager";

// 개체로 영향을 묻는 칸 (카드 D-23, D7 6절 "출발 개체로 영향을 묻는다", D2 AD-429).
// 서버의 getImpactFromEntity (docs/api/openapi.yaml) 의 값을 그대로 보여 준다.
//
// 이 칸은 계산하지 않는다.
// - 영향 대상의 수, 유형별 수, 깊이별 수, 순서, 근거 경로는 서버가 준 것이다. 다시 세거나 정렬하지 않는다.
// - 오늘을 정하지 않는다. 조회 시점을 비우면 서버가 정하고, 서버가 답한 날짜를 보여 준다.
// - 최대 깊이를 비우면 서버가 그 개체의 Pack 의 기본값을 쓰고, 답한 값을 보여 준다. 틀린 값은 서버가 알린다.
//
// 변동의 저장된 영향 범위(위의 것)와 다르다: 저장하지 않고 부를 때마다 지금의 자료로 계산한다.

type Asked = { entityId: string; asOf: string; includePreviousDay: boolean; maxDepth: string };

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; impact: EntityImpact }
  | { kind: "failed"; message: string };

export function EntityImpactAsker() {
  const [entityId, setEntityId] = useState("");
  const [asOf, setAsOf] = useState("");
  const [includePreviousDay, setIncludePreviousDay] = useState(false);
  const [maxDepth, setMaxDepth] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });
  // 마지막으로 물은 조건. 쪽을 넘길 때는 칸에 적고 있는 것이 아니라 물은 조건으로 넘긴다.
  const asked = useRef<Asked | null>(null);
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  async function load(wanted: Asked, offset: number) {
    const turn = ++latest.current;
    asked.current = wanted;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/impact", {
        params: {
          query: {
            entityId: wanted.entityId,
            // 비운 것은 보내지 않는다: 서버가 정한다.
            ...(wanted.asOf ? { asOf: wanted.asOf } : {}),
            ...(wanted.includePreviousDay ? { includePreviousDay: true } : {}),
            // 최대 깊이는 적은 글자를 그대로 보낸다. 틀린 값은 서버가 알린다.
            ...(wanted.maxDepth ? { maxDepth: wanted.maxDepth as unknown as number } : {}),
            ...(offset > 0 ? { offset } : {}),
          },
        },
      });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", impact: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void load({ entityId: entityId.trim(), asOf, includePreviousDay, maxDepth: maxDepth.trim() }, 0);
  }

  return (
    <section className="entity-impact" aria-labelledby="entity-impact-title">
      <h2 id="entity-impact-title">개체로 영향 묻기</h2>
      <p>
        <small>
          개체 하나가 바뀌면 무엇이 영향을 받는지를 묻습니다. 변동이 없어도 물을 수 있습니다. 지금의 자료로 계산하고
          저장하지 않습니다: 조치를 만들지 않습니다. 어느 관계를 어느 방향으로 따라가는지와 최대 깊이는 그 개체의 Pack 이
          정합니다.
        </small>
      </p>
      <form onSubmit={ask}>
        <p>
          <label htmlFor="entity-impact-id">출발 개체의 id</label>{" "}
          <input id="entity-impact-id" value={entityId} onChange={(e) => setEntityId(e.target.value)} required size={40} />{" "}
          <small>
            id 는 <Link to="/relations">관계 지도</Link> 화면의 개체 찾기에서 찾을 수 있습니다.
          </small>
        </p>
        <p>
          <label htmlFor="entity-impact-as-of">조회 시점</label>{" "}
          <input id="entity-impact-as-of" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} />{" "}
          <small>비우면 서버가 오늘로 정합니다.</small>
        </p>
        <p>
          <label>
            <input type="checkbox" checked={includePreviousDay} onChange={(e) => setIncludePreviousDay(e.target.checked)} />{" "}
            전날의 관계도 함께 따라간다
          </label>{" "}
          <small>그 날짜에 적용된 변동의 저장된 영향 범위와 같은 방식입니다 (그날 끝난 관계로 닿던 대상도 나옵니다).</small>
        </p>
        <p>
          <label htmlFor="entity-impact-depth">최대 깊이</label>{" "}
          <input
            id="entity-impact-depth"
            inputMode="numeric"
            value={maxDepth}
            onChange={(e) => setMaxDepth(e.target.value)}
            size={4}
          />{" "}
          <small>비우면 그 Pack 의 기본값입니다. 그보다 깊게는 묻지 못합니다.</small>
        </p>
        <button type="submit" disabled={view.kind === "loading"}>
          영향 묻기
        </button>
      </form>

      {view.kind === "loading" && <p role="status">영향 범위를 계산하는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && (
        <Result impact={view.impact} onMove={(offset) => asked.current && void load(asked.current, offset)} />
      )}
    </section>
  );
}

function Result({ impact, onMove }: { impact: EntityImpact; onMove: (offset: number) => void }) {
  return (
    <div data-testid="entity-impact" data-entity-id={impact.entityId}>
      <p>
        출발 개체 <EntityName entity={impact.entity} /> (Pack {impact.packId}). 영향 대상{" "}
        <strong data-testid="entity-impact-total">{impact.total}</strong>개. 조회 시점{" "}
        <time data-testid="entity-impact-as-of">{impact.asOf}</time>
        {impact.asOfPrevious !== null && (
          <>
            {" "}
            과 그 전날 <time data-testid="entity-impact-as-of-previous">{impact.asOfPrevious}</time>, 두 날짜
          </>
        )}
        의 관계를 깊이 <span data-testid="entity-impact-max-depth">{impact.maxDepth}</span> 까지 따라가 찾았습니다. 출발
        개체 자신은 세지 않습니다.
      </p>
      <p>
        <small>지금의 자료로 계산한 것입니다. 저장하지 않습니다: 자료가 바뀌면 다시 물었을 때 답이 달라집니다.</small>
      </p>
      {impact.truncated && (
        <p data-testid="entity-impact-truncated">
          최대 깊이에서 멈췄습니다. 그 너머에 아직 찾지 못한 대상이 있습니다.
        </p>
      )}

      {impact.total === 0 ? (
        <p role="status">이 조회 시점에 이 개체의 영향을 받는 대상이 없습니다.</p>
      ) : (
        <>
          <table>
            <caption>유형별 수 (개체로 물은 것)</caption>
            <thead>
              <tr>
                <th scope="col">개체 유형</th>
                <th scope="col">영향 대상</th>
              </tr>
            </thead>
            <tbody>
              {impact.countsByType.map((one) => (
                <tr key={one.entityType} data-entity-type={one.entityType}>
                  <th scope="row">{one.entityType}</th>
                  <td>{one.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <table>
            <caption>깊이별 수 (개체로 물은 것)</caption>
            <thead>
              <tr>
                <th scope="col">깊이</th>
                <th scope="col">영향 대상</th>
              </tr>
            </thead>
            <tbody>
              {impact.countsByDepth.map((one) => (
                <tr key={one.depth} data-depth={one.depth}>
                  <th scope="row">{one.depth}</th>
                  <td>{one.count}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <Pager total={impact.total} offset={impact.offset} limit={impact.limit} shown={impact.targets.length} onMove={onMove} />

          {impact.targets.length === 0 ? (
            <p role="status">이 자리에는 영향 대상이 없습니다. 이전으로 돌아가세요.</p>
          ) : (
            <table className="impact-targets" aria-label="영향 대상">
              <thead>
                <tr>
                  <th scope="col">깊이</th>
                  <th scope="col">대상</th>
                  <th scope="col">근거 경로</th>
                  <th scope="col">이어 보기</th>
                </tr>
              </thead>
              <tbody>
                {impact.targets.map((target) => (
                  <tr key={target.entityId} data-target-id={target.entityId}>
                    <td data-testid="target-depth">{target.depth}</td>
                    <td>
                      <EntityName entity={target.entity} />
                    </td>
                    <td>
                      <details>
                        <summary>근거 경로 보기</summary>
                        <p>
                          출발 개체 <EntityName entity={target.path.start} /> 에서 관계 {target.depth}개를 건너 닿았습니다.
                          관계는 <time data-testid="path-as-of">{target.path.asOf}</time> 에 유효한 것입니다.
                        </p>
                        {/* 단계의 차례는 서버가 준 그대로다: 출발 개체 쪽부터. */}
                        <ol>
                          {target.path.steps.map((step) => (
                            <li key={step.stepNo} data-relation-id={step.relationId}>
                              {step.relationType}: <EntityName entity={step.source} /> → <EntityName entity={step.target} />
                            </li>
                          ))}
                        </ol>
                      </details>
                    </td>
                    <td>
                      {/* 그 개체를 가운데 두고, 서버가 답한 조회 시점의 관계로 지도를 연다. */}
                      <Link to={`/relations?${new URLSearchParams({ entityId: target.entityId, asOf: impact.asOf })}`}>
                        관계 지도
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </div>
  );
}
