import { type FormEvent, useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { api, type RelationMap, type RelationsAsOf } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { addDays } from "../time";
import { EntityName } from "./EntityName";
import { EntityPicker } from "./EntityPicker";
import { Pager } from "./Pager";
import { type MapView, RelationMapView } from "./RelationMapView";

// 관계 지도 화면 (D-08). 개체 하나를 가운데 둔 지도와, 그 개체의 관계 목록을 조회 시점으로 보여 준다.
// 지도는 관계 지도 API (docs/api/openapi.yaml 의 getRelationMap), 목록은 시점 조회 API (getRelationsAsOf) 의 값이다.
//
// 이 화면은 계산하지 않는다.
// - 오늘을 정하지 않는다. 조회 시점을 비우면 서버가 정하고, 화면은 서버가 답한 날짜를 보여 준다.
// - 목록과 노드를 다시 정렬하거나 세지 않는다. 서버가 준 순서 그대로 그린다.
//
// 시점 막대: 하루 단위로 미는 막대다. 범위는 조회 단추로 받은 답의 조회 시점의 앞뒤 1년이다 (D2 AD-353).
// 막대를 옮기면 그 날짜로 지도와 목록을 다시 받는다. 범위 밖의 날짜는 조회 시점에 적어 조회한다:
// 그러면 막대의 범위도 그 날짜를 가운데로 다시 잡힌다.

const BAR_DAYS = 365;
const DEPTHS = [1, 2, 3] as const;

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; map: RelationMap; relations: RelationsAsOf }
  | { kind: "failed"; message: string };

type Query = { entityId: string; asOf?: string; depth: number };

export function RelationsPage() {
  // 다른 화면에서 개체와 조회 시점을 주소에 담아 넘겨줄 수 있다 (영향 범위 화면의 "관계 지도": ?entityId=…&asOf=…).
  // 처음 열릴 때 한 번 그 값으로 조회한다. 그 뒤로 이 화면에서 바꾼 것은 주소에 적지 않는다.
  const [params] = useSearchParams();
  const givenEntityId = params.get("entityId") ?? "";
  const givenAsOf = params.get("asOf") ?? "";
  const [entityId, setEntityId] = useState(givenEntityId);
  const [asOf, setAsOf] = useState(givenAsOf);
  const [depth, setDepth] = useState<number>(1);
  const [view, setView] = useState<View>({ kind: "idle" });
  // 지도를 2D 로 그릴지 3D 로 그릴지. 화면을 열어 둔 동안 유지한다 (다시 받아도, 가운데를 옮겨도). D2 AD-361.
  const [mapView, setMapView] = useState<MapView>("2d");
  // 마지막으로 청한 것 (가운데 개체, 조회 시점, 깊이). 막대, 깊이, 노드로 다시 받을 때 여기서 하나만 바꿔 청한다.
  // 받은 것이 아니라 청한 것을 둔다: 청한 것이 실패했어도 화면의 입력란과 막대는 청한 값을 보이고 있으므로,
  // 그 뒤에 깊이나 막대를 바꾸면 화면에 적힌 개체와 날짜로 다시 청해야 한다 (앞서 받은 것으로 돌아가지 않는다).
  const wanted = useRef<Query | null>(null);
  // 막대의 가운데 날짜와, 막대가 가리키는 날수 (가운데에서 며칠 앞뒤인가).
  const [barBase, setBarBase] = useState<string | null>(null);
  const [barOffset, setBarOffset] = useState(0);
  // 늦게 온 답이 새 답을 덮지 않게 한다: 막대를 잇달아 옮기면 요청이 겹친다.
  const latest = useRef(0);

  // 지도를 먼저 받고, 서버가 답한 조회 시점으로 목록을 받는다. 조회 시점을 비워 서버가 정했더라도
  // 지도와 목록이 같은 날짜를 본다.
  async function load(query: Query, rebase: boolean) {
    const turn = ++latest.current;
    wanted.current = query;
    setView({ kind: "loading" });
    try {
      const mapAnswer = await api.GET("/api/v1/relation-map", {
        // 조회 시점을 비웠으면 항목을 보내지 않는다. 그러면 서버가 날짜를 정한다.
        params: { query: query.asOf ? query : { entityId: query.entityId, depth: query.depth } },
      });
      if (turn !== latest.current) {
        return;
      }
      if (!mapAnswer.data) {
        setView({ kind: "failed", message: messageOf(mapAnswer.error, mapAnswer.response.status) });
        return;
      }
      const map = mapAnswer.data;
      const listAnswer = await api.GET("/api/v1/relations", {
        params: { query: { entityId: map.centerEntityId, asOf: map.asOf } },
      });
      if (turn !== latest.current) {
        return;
      }
      if (!listAnswer.data) {
        setView({ kind: "failed", message: messageOf(listAnswer.error, listAnswer.response.status) });
        return;
      }
      // 조회 시점을 비워 서버가 정했으면, 그 뒤로는 서버가 답한 날짜로 청한다.
      wanted.current = { entityId: map.centerEntityId, asOf: map.asOf, depth: query.depth };
      if (rebase) {
        setBarBase(map.asOf);
        setBarOffset(0);
      }
      setView({ kind: "loaded", map, relations: listAnswer.data });
    } catch {
      if (turn === latest.current) {
        // 응답을 받지 못했다 (연결 실패 등).
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  // 목록의 다른 쪽만 받는다. 지도는 그대로 둔다.
  async function movePage(map: RelationMap, offset: number) {
    const turn = ++latest.current;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/relations", {
        params: { query: { entityId: map.centerEntityId, asOf: map.asOf, offset } },
      });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", map, relations: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  const opened = useRef(false);
  useEffect(() => {
    // 주소로 개체를 받았으면 조회 단추를 누른 것처럼 한 번 조회한다.
    if (opened.current || !givenEntityId) {
      return;
    }
    opened.current = true;
    void load(givenAsOf ? { entityId: givenEntityId, asOf: givenAsOf, depth } : { entityId: givenEntityId, depth }, true);
    // 처음 열릴 때의 주소만 본다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // 조회 단추로 받으면 막대의 범위를 답한 날짜의 앞뒤로 다시 잡는다.
    void load(asOf ? { entityId, asOf, depth } : { entityId, depth }, true);
  }

  // 찾아서 고른 개체를 조회 단추를 누른 것처럼 연다 (카드 A-17).
  function pick(pickedId: string) {
    setEntityId(pickedId);
    void load(asOf ? { entityId: pickedId, asOf, depth } : { entityId: pickedId, depth }, true);
  }

  function center(nodeId: string) {
    if (!wanted.current) {
      return;
    }
    setEntityId(nodeId);
    void load({ ...wanted.current, entityId: nodeId }, false);
  }

  function changeDepth(next: number) {
    setDepth(next);
    if (wanted.current) {
      void load({ ...wanted.current, depth: next }, false);
    }
  }

  function moveBar(offset: number) {
    if (!wanted.current || barBase === null) {
      return;
    }
    const date = addDays(barBase, offset);
    setAsOf(date);
    void load({ ...wanted.current, asOf: date }, false);
  }

  return (
    <section aria-labelledby="relations-title">
      <h1 id="relations-title">관계 지도</h1>

      <EntityPicker asOf={asOf} onPick={pick} disabled={view.kind === "loading"} />

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
          {/* 범위는 서버가 받는 날짜의 범위다 (D7 2절). 주지 않으면 브라우저가 연도를 여섯 자리까지 받는다. */}
          <input
            id="as-of"
            name="asOf"
            type="date"
            min="0001-01-01"
            max="9999-12-31"
            value={asOf}
            onChange={(e) => setAsOf(e.target.value)}
          />{" "}
          <small>비우면 서버가 오늘로 정합니다.</small>
        </p>
        <p>
          <label htmlFor="map-depth">깊이</label>{" "}
          <select
            id="map-depth"
            value={depth}
            disabled={view.kind === "loading"}
            onChange={(e) => changeDepth(Number(e.target.value))}
          >
            {DEPTHS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>{" "}
          <small>가운데 개체에서 관계를 몇 번 건너까지 지도에 그릴지입니다.</small>
        </p>
        <button type="submit" disabled={view.kind === "loading"}>
          조회
        </button>
      </form>

      {barBase !== null && (
        <TimeBar
          base={barBase}
          offset={barOffset}
          disabled={view.kind === "loading"}
          onSlide={setBarOffset}
          onCommit={(offset) => {
            setBarOffset(offset);
            moveBar(offset);
          }}
        />
      )}

      {view.kind === "loading" && <p role="status">불러오는 중입니다…</p>}

      {view.kind === "failed" && <p role="alert">{view.message}</p>}

      {view.kind === "loaded" && (
        <>
          <RelationMapView map={view.map} view={mapView} onView={setMapView} onCenter={center} />
          <Result result={view.relations} onMove={(offset) => void movePage(view.map, offset)} />
        </>
      )}
    </section>
  );
}

// 시점 막대. 미는 동안에는 가리키는 날짜만 바꿔 보여 주고, 놓으면 (또는 글쇠로 옮기면) 그 날짜로 다시 받는다.
function TimeBar({
  base,
  offset,
  disabled,
  onSlide,
  onCommit,
}: {
  base: string;
  offset: number;
  // 받아 오는 동안에는 옮기지 못한다: 청한 것이 끝나기 전에 다른 것을 청하지 않는다.
  disabled: boolean;
  onSlide: (offset: number) => void;
  onCommit: (offset: number) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const commit = useRef(onCommit);
  commit.current = onCommit;

  // 놓았을 때를 알려 주는 것은 브라우저의 change 이벤트다. React 의 onChange 는 미는 동안 계속 온다.
  useEffect(() => {
    const element = input.current;
    if (!element) {
      return;
    }
    const listener = () => commit.current(Number(element.value));
    element.addEventListener("change", listener);
    return () => element.removeEventListener("change", listener);
  }, []);

  return (
    <p>
      <label htmlFor="time-bar">시점 막대</label>{" "}
      <small data-testid="time-bar-from">{addDays(base, -BAR_DAYS)}</small>{" "}
      <input
        id="time-bar"
        ref={input}
        type="range"
        min={-BAR_DAYS}
        max={BAR_DAYS}
        step={1}
        value={offset}
        disabled={disabled}
        onChange={(e) => onSlide(Number(e.target.value))}
        style={{ width: "24rem", verticalAlign: "middle" }}
      />{" "}
      <small data-testid="time-bar-to">{addDays(base, BAR_DAYS)}</small>{" "}
      <strong data-testid="time-bar-date">{addDays(base, offset)}</strong>
    </p>
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
                <td>
                  <EntityName entity={relation.source} />
                </td>
                <td>
                  <EntityName entity={relation.target} />
                </td>
                <td>{relation.validFrom}</td>
                <td>{relation.validTo ?? "종료일 없음"}</td>
                <td>
                  {relation.provenance.inputSourceLabel}, {relation.provenance.editionNo}판
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
