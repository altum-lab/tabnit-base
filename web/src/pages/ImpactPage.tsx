import { useCallback, useContext, useEffect, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { api, type ChangeImpactScope } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { SessionContext } from "../session";
import { formatInstant } from "../time";
import { EntityName } from "./EntityName";
import { ImpactPathView } from "./ImpactPathView";
import { Pager } from "./Pager";

// 영향 범위 화면 (D-03, D2 AD-365). 변동 하나의 저장된 영향 범위를 보여 준다.
// 서버의 getChangeImpactScope (docs/api/openapi.yaml, D7 6절) 의 값을 그대로 보여 준다.
//
// 이 화면은 계산하지 않는다.
// - 영향 대상의 수, 유형별 수, 깊이별 수는 서버가 센 값이다. 화면은 목록을 세어 수를 만들지 않는다.
// - 영향 대상과 근거 경로의 단계는 서버가 준 순서 그대로 그린다.
// - 어느 날짜로 보았는지는 서버가 답한 두 날짜(판의 적용일과 그 전날)를 보여 준다. 화면은 오늘을 정하지 않는다.
//
// 어느 변동인지는 주소가 정한다 (?changeEventId=…). 변동 감지 화면과 조치 화면에서 이어 온다:
// 지난 변동의 목록을 주는 API 가 아직 없어, 이 화면에서 변동을 고르지는 못한다.

type View =
  | { kind: "loading" }
  | { kind: "loaded"; scope: ChangeImpactScope }
  | { kind: "failed"; message: string };

export function ImpactPage() {
  const [params, setParams] = useSearchParams();
  const changeEventId = params.get("changeEventId");
  const offsetText = params.get("offset");
  const [view, setView] = useState<View>({ kind: "loading" });
  // 늦게 온 답이 새 답을 덮지 않게 한다.
  const latest = useRef(0);

  const load = useCallback(async () => {
    if (!changeEventId) {
      return;
    }
    const turn = ++latest.current;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/impact-scopes", {
        // 건너뛸 수는 주소의 글자를 그대로 보낸다. 틀린 값은 서버가 알린다.
        params: { query: { changeEventId, ...(offsetText !== null ? { offset: offsetText as unknown as number } : {}) } },
      });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", scope: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }, [changeEventId, offsetText]);

  useEffect(() => {
    void load();
  }, [load]);

  function move(offset: number) {
    const updated = new URLSearchParams(params);
    if (offset > 0) {
      updated.set("offset", String(offset));
    } else {
      updated.delete("offset");
    }
    setParams(updated);
  }

  return (
    <section aria-labelledby="impact-title">
      <h1 id="impact-title">영향 범위</h1>

      {!changeEventId && (
        <p data-testid="impact-guide">
          변동 하나의 영향 범위를 보는 화면입니다. <Link to="/changes">변동 감지</Link> 화면에서 판을 올린 뒤 바뀐 항목의
          "영향 범위 보기"로, 또는 <Link to="/actions">조치</Link> 화면에서 변동 하나의 조치를 볼 때 "이 변동의 영향
          범위"로 들어오세요.
        </p>
      )}

      {changeEventId && view.kind === "loading" && <p role="status">영향 범위를 불러오는 중입니다…</p>}

      {changeEventId && view.kind === "failed" && (
        <p role="alert">
          {view.message}{" "}
          <button type="button" onClick={() => void load()}>
            다시 불러오기
          </button>
        </p>
      )}

      {changeEventId && view.kind === "loaded" && <Scope scope={view.scope} onMove={move} />}
    </section>
  );
}

function Scope({ scope, onMove }: { scope: ChangeImpactScope; onMove: (offset: number) => void }) {
  // 시각은 설치 설정의 시간대로 보인다 (내 정보의 timeZone).
  const timeZone = useContext(SessionContext)?.timeZone;
  const actions = `/actions?${new URLSearchParams({ packId: scope.packId, status: "ALL", changeEventId: scope.changeEventId })}`;
  return (
    <>
      <p>
        변동 이벤트 <code>{scope.changeEventId}</code> (Pack {scope.packId}). <Link to={actions}>이 변동의 조치 보기</Link>
      </p>
      <p>
        영향 대상 <strong data-testid="impact-total">{scope.total}</strong>개. 판의 적용일{" "}
        <time data-testid="impact-as-of-effective">{scope.asOfEffective}</time> 과 그 전날{" "}
        <time data-testid="impact-as-of-previous">{scope.asOfPrevious}</time>, 두 날짜의 관계를 깊이{" "}
        <span data-testid="impact-max-depth">{scope.maxDepth}</span> 까지 따라가 찾았습니다. 변동 대상 자신은 세지 않습니다.
        계산해 저장한 때는 <time dateTime={scope.storedAt}>{formatInstant(scope.storedAt, timeZone)}</time> 입니다.
      </p>
      {scope.truncated && (
        <p data-testid="impact-truncated">
          최대 깊이에서 멈췄습니다. 그 너머에 아직 찾지 못한 대상이 있습니다: 깊이를 늘리면 영향 대상이 늘어납니다.
        </p>
      )}

      {scope.total === 0 ? (
        <p role="status">이 변동의 영향을 받는 대상이 없습니다.</p>
      ) : (
        <>
          <table>
            <caption>유형별 수</caption>
            <thead>
              <tr>
                <th scope="col">개체 유형</th>
                <th scope="col">영향 대상</th>
              </tr>
            </thead>
            <tbody>
              {scope.countsByType.map((one) => (
                <tr key={one.entityType} data-entity-type={one.entityType}>
                  <th scope="row">{one.entityType}</th>
                  <td>{one.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <table>
            <caption>깊이별 수</caption>
            <thead>
              <tr>
                <th scope="col">깊이</th>
                <th scope="col">영향 대상</th>
              </tr>
            </thead>
            <tbody>
              {scope.countsByDepth.map((one) => (
                <tr key={one.depth}>
                  <th scope="row">{one.depth}</th>
                  <td>{one.count}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <Pager total={scope.total} offset={scope.offset} limit={scope.limit} shown={scope.targets.length} onMove={onMove} />

          {scope.targets.length === 0 ? (
            <p role="status">이 자리에는 영향 대상이 없습니다. 이전으로 돌아가세요.</p>
          ) : (
            <table className="impact-targets">
              <caption>영향 대상</caption>
              <thead>
                <tr>
                  <th scope="col">깊이</th>
                  <th scope="col">대상</th>
                  <th scope="col">근거 경로</th>
                  <th scope="col">이어 보기</th>
                </tr>
              </thead>
              <tbody>
                {scope.targets.map((target) => (
                  <tr key={target.entityId} data-target-id={target.entityId}>
                    <td data-testid="target-depth">{target.depth}</td>
                    <td>
                      <EntityName entity={target.entity} />
                    </td>
                    <td>
                      <details>
                        <summary>근거 경로 보기</summary>
                        <ImpactPathView target={target} scope={scope} />
                      </details>
                    </td>
                    <td>
                      {/* 그 개체를 가운데 두고, 판의 적용일의 관계로 지도를 연다. */}
                      <Link to={`/relations?${new URLSearchParams({ entityId: target.entityId, asOf: scope.asOfEffective })}`}>
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
    </>
  );
}
