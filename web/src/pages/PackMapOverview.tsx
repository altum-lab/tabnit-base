import { type ComponentType, type FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api, type PackRelationMap, type PackSummary } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";
import { addDays } from "../time";
import { buildPackGraph, filterPackMap, type PackGraphProps, type PackMapFilter } from "./packMapData";
import { PackMapGraph, type Spot } from "./PackMapGraph";
import { TimeBar } from "./TimeBar";

// Pack 의 전체 지도 (카드 A-20, D7 4절 "Pack 의 전체 지도", D2 AD-419).
// 서버의 getPackRelationMap (docs/api/openapi.yaml) 의 답을 그대로 그린다.
//
// 이 칸은 계산하지 않는다.
// - 무엇이 이어져 있는지, 묶을지, 수와 차례는 서버가 준 것이다. 다시 세거나 정렬하지 않는다.
// - 오늘을 정하지 않는다. 조회 시점이 비어 있으면 서버가 정하고, 서버가 답한 날짜를 보여 준다.
// - 그림은 PackMapGraph(2D)와 PackMapGraph3D 가 그린다: 개체 하나의 둘레의 지도와 같은 그래프 그림이다.
//
// 개체 하나의 둘레의 지도에 있는 것을 여기에도 둔다.
// - 2D 와 3D. 3D 의 코드는 고를 때 내려받는다 (D2 AD-361).
// - 시점 막대. 옮기면 화면의 조회 시점을 그 날짜로 바꾼다: 전체 보기가 따로 날짜를 갖지 않는다. 그래서 전체 보기가
//   보이는 날짜와 여기서 고른 개체가 열리는 날짜가 늘 같다. 범위는 전체 보기를 연 날짜의 앞뒤 1년이다.
// - 가리기. 유형과 관계 유형을 꺼서 그림에서 뺀다. 서버에 다시 묻지 않고, 서버가 준 수와 아래의 표는 바꾸지 않는다.

type Packs =
  | { kind: "loading" }
  | { kind: "loaded"; packs: PackSummary[] }
  | { kind: "failed"; message: string };

type View =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "loaded"; map: PackRelationMap }
  | { kind: "failed"; message: string };

type Props = {
  // 화면의 조회 시점. 비어 있으면 보내지 않는다.
  asOf: string;
  // 시점 막대를 옮기면 화면의 조회 시점을 그 날짜로 바꾼다.
  onAsOf: (date: string) => void;
  // 개체 하나를 가운데로 지도를 연다.
  onPick: (entityId: string) => void;
  // 유형 묶음을 누르면 개체 찾기를 그 유형으로 거른다.
  onPickType: (filter: { packId: string; entityType: string; label: string }) => void;
};

type MapView = "2d" | "3d";

// 3D 그림은 고를 때 내려받는다. 한 번 받은 것은 화면을 열어 둔 동안 다시 받지 않는다.
let loaded3D: ComponentType<PackGraphProps> | null = null;

function toggled(names: string[], name: string, on: boolean) {
  return on ? names.filter((candidate) => candidate !== name) : [...names, name];
}

export function PackMapOverview({ asOf, onAsOf, onPick, onPickType }: Props) {
  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  const [packId, setPackId] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });
  const latest = useRef(0);
  // 그림을 보는 법과 가린 것. 다시 받아도 (시점 막대, 조회 시점) 그대로 둔다.
  const [mapView, setMapView] = useState<MapView>("2d");
  const [hidden, setHidden] = useState<PackMapFilter>({ entityTypes: [], relationTypes: [] });
  // 시점 막대의 가운데 날짜와 거기서 옮긴 날 수. 가운데는 막대가 아닌 것으로 받은 답의 조회 시점이다.
  const [barBase, setBarBase] = useState<string | null>(null);
  const [barOffset, setBarOffset] = useState(0);
  // 막대가 화면에 청한 조회 시점. 조회 시점이 이 날짜로 바뀐 것이면 막대가 옮긴 것이니 막대의 범위를 다시 잡지 않는다.
  // 청마다 따로 정한다: 버려진 청의 것이 다음 청에 남지 않는다.
  const barDate = useRef<string | null>(null);

  async function loadPacks() {
    setPacks({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/packs");
      setPacks(data ? { kind: "loaded", packs: data.packs } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      setPacks({ kind: "failed", message: CONNECTION_FAILED });
    }
  }

  useEffect(() => {
    void loadPacks();
  }, []);

  // 마지막으로 연 Pack. 조회 시점이 바뀌면 이 Pack 의 전체 보기를 그 날짜로 다시 받는다.
  const opened = useRef<string | null>(null);

  // 화면의 조회 시점이 바뀌면 열어 둔 전체 보기를 그 날짜로 다시 받는다 (단추를 다시 누르지 않는다). 그래서 전체
  // 보기가 보이는 날짜와, 여기서 고른 개체나 유형이 열리는 날짜가 늘 같다.
  useEffect(() => {
    const byBar = barDate.current === asOf;
    barDate.current = null;
    if (opened.current !== null) {
      void fetchMap(opened.current, !byBar);
    }
    // 조회 시점이 바뀔 때만 다시 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asOf]);

  function open(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // 다른 Pack 을 열면 가린 것을 푼다: 이름이 같은 유형이 가려진 채로 열리지 않는다.
    if (opened.current !== packId) {
      setHidden({ entityTypes: [], relationTypes: [] });
    }
    opened.current = packId;
    void fetchMap(packId, true);
  }

  // rebase: 받은 답의 조회 시점을 가운데로 막대의 범위를 다시 잡는다 (막대가 옮겨서 받는 것이 아닐 때).
  async function fetchMap(wanted: string, rebase: boolean) {
    const turn = ++latest.current;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/pack-relation-map", {
        params: { query: { packId: wanted, ...(asOf ? { asOf } : {}) } },
      });
      if (turn !== latest.current) {
        return;
      }
      if (data && rebase) {
        setBarBase(data.asOf);
        setBarOffset(0);
      }
      setView(data ? { kind: "loaded", map: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
  }

  function moveBar(offset: number) {
    if (barBase === null) {
      return;
    }
    const date = addDays(barBase, offset);
    setBarOffset(offset);
    if (date === asOf) {
      return;
    }
    // 화면의 조회 시점을 바꾼다. 위의 효과가 그 날짜로 다시 받는다.
    barDate.current = date;
    onAsOf(date);
  }

  return (
    <section className="pack-map" aria-labelledby="pack-map-title">
      <h2 id="pack-map-title">Pack 전체 보기</h2>
      <p>
        <small>
          가운데 개체를 고르지 않고 Pack 의 관계를 위의 조회 시점으로 봅니다. 이어진 개체가 많으면 유형별로 묶어 보입니다.
        </small>
      </p>

      {packs.kind === "loading" && <p>Pack 의 목록을 불러오는 중입니다…</p>}
      {packs.kind === "failed" && (
        <p role="alert">
          {packs.message}{" "}
          <button type="button" onClick={() => void loadPacks()}>
            다시 불러오기
          </button>
        </p>
      )}
      {packs.kind === "loaded" && packs.packs.length === 0 && <p>적재된 Pack 이 없습니다.</p>}
      {packs.kind === "loaded" && packs.packs.length > 0 && (
        <form onSubmit={open}>
          <p>
            <label htmlFor="pack-map-pack">전체를 볼 Pack</label>{" "}
            <select id="pack-map-pack" value={packId} onChange={(e) => setPackId(e.target.value)} required>
              <option value="">고르세요</option>
              {packs.packs.map((candidate) => (
                <option key={candidate.packId} value={candidate.packId}>
                  {candidate.label} ({candidate.packId})
                </option>
              ))}
            </select>{" "}
            <button type="submit" disabled={view.kind === "loading"}>
              전체 지도 보기
            </button>
          </p>
        </form>
      )}

      {barBase !== null && (
        <TimeBar
          id="pack-map-bar"
          label="전체 보기의 시점을 옮기는 막대"
          base={barBase}
          offset={barOffset}
          disabled={view.kind === "loading"}
          onSlide={setBarOffset}
          onCommit={moveBar}
        />
      )}

      {view.kind === "loading" && <p data-testid="pack-map-loading">전체 지도를 불러오는 중입니다…</p>}
      {view.kind === "failed" && (
        <p role="alert">
          {view.message}{" "}
          {opened.current !== null && (
            // 같은 Pack 을 같은 조회 시점으로 다시 청한다. 막대의 자리는 그대로 둔다.
            <button type="button" onClick={() => void fetchMap(opened.current!, barBase === null)}>
              다시 받기
            </button>
          )}
        </p>
      )}
      {view.kind === "loaded" && (
        <Overview
          map={view.map}
          onPick={onPick}
          onPickType={onPickType}
          mapView={mapView}
          onMapView={setMapView}
          hidden={hidden}
          onHidden={setHidden}
        />
      )}
    </section>
  );
}

function Overview({
  map,
  onPick,
  onPickType,
  mapView,
  onMapView,
  hidden,
  onHidden,
}: { map: PackRelationMap } & Pick<Props, "onPick" | "onPickType"> & {
    mapView: MapView;
    onMapView: (view: MapView) => void;
    hidden: PackMapFilter;
    onHidden: (hidden: PackMapFilter) => void;
  }) {
  // 3D 그림의 코드를 받는 일의 상태. 받았는지는 loaded3D 로 본다.
  const [loading3D, setLoading3D] = useState<"loading" | "failed">("loading");
  const [, setArrived] = useState(0);
  useEffect(() => {
    if (mapView !== "3d" || loaded3D) {
      return;
    }
    let current = true;
    setLoading3D("loading");
    import("./PackMapGraph3D")
      .then((module) => {
        loaded3D = module.default;
        if (current) {
          setArrived((count) => count + 1);
        }
      })
      .catch(() => {
        if (current) {
          setLoading3D("failed");
        }
      });
    return () => {
      current = false;
    };
  }, [mapView]);
  const Graph3D = loaded3D;

  // 고를 것: 서버의 답에 나온 유형과 관계 유형. 답에 처음 나온 차례다 (화면이 정렬하지 않는다).
  const entityTypes = useMemo(() => {
    const seen = new Map<string, string>();
    for (const group of map.typeGroups) {
      seen.set(group.entityType, group.entityTypeLabel);
    }
    return [...seen].map(([name, label]) => ({ name, label }));
  }, [map]);
  const relationTypes = useMemo(() => [...new Set(map.relationGroups.map((group) => group.relationType))], [map]);
  const shown = useMemo(() => filterPackMap(map, hidden), [map, hidden]);
  // 그리는 것. 두 끝이 그리는 점에 없는 선은 그리지 못해 뺀다 (packMapData 의 build).
  const drawn = useMemo(() => buildPackGraph(shown, 2), [shown]);
  const shownNodes = drawn.nodes.map((node) => node.id);
  const shownLinks = drawn.links.map((link) => link.id);
  const filtered =
    shown.nodes.length !== map.nodes.length ||
    shown.edges.length !== map.edges.length ||
    shown.typeGroups.length !== map.typeGroups.length ||
    shown.relationGroups.length !== map.relationGroups.length;
  // 가리지 않았는데도 그리지 못한 선: 서버의 답에서 끝이 점의 목록에 없는 것이다. 표에는 있다.
  const unmatched = (map.grouped ? map.relationGroups.length : map.edges.length) - buildPackGraph(map, 2).links.length;

  // 2D 그림이 알려 준 점의 자리. 그린 것이 바뀌면 비운다.
  const [spots, setSpots] = useState<Spot[]>([]);
  useEffect(() => setSpots([]), [shown, mapView]);

  return (
    <div
      data-testid="pack-map"
      data-pack-id={map.packId}
      data-as-of={map.asOf}
      data-grouped={String(map.grouped)}
      data-connected={map.connectedEntities}
    >
      <p data-testid="pack-map-summary">
        조회 시점 <span data-testid="pack-map-as-of">{map.asOf}</span>: 이어진 개체 {map.connectedEntities}개, 관계{" "}
        {map.relations}건. 관계가 없는 개체 {map.isolatedEntities}개는 지도에 넣지 않았습니다.
      </p>
      {map.connectedEntities === 0 && <p data-testid="pack-map-empty">이 조회 시점에 유효한 관계가 없습니다.</p>}
      {map.grouped && (
        <p data-testid="pack-map-grouped">
          이어진 개체가 한도({map.maxNodes}개)를 넘어 유형별로 묶었습니다. 유형을 누르면 위의 개체 찾기가 그 유형으로
          걸러집니다. 거기서 개체를 골라 그 둘레의 지도를 엽니다.
        </p>
      )}
      {map.connectedEntities > 0 && (
        <>
          <fieldset>
            <legend>전체 보기의 그림</legend>
            <label>
              <input type="radio" name="pack-map-view" checked={mapView === "2d"} onChange={() => onMapView("2d")} /> 2D
            </label>{" "}
            <label>
              <input type="radio" name="pack-map-view" checked={mapView === "3d"} onChange={() => onMapView("3d")} /> 3D
            </label>{" "}
            <small>3D 는 마우스로 끌어 돌리고 굴려서 확대합니다.</small>
          </fieldset>
          <p role="group" aria-label="전체 보기에 보일 것">
            <strong>보일 것</strong>{" "}
            <small>끄면 그 유형의 개체와 관계를 그림에 그리지 않습니다. 서버에 다시 묻지 않습니다.</small>
            <br />
            <span>유형:</span>{" "}
            {entityTypes.map((type) => (
              <label key={type.name}>
                <input
                  type="checkbox"
                  data-entity-type={type.name}
                  checked={!hidden.entityTypes.includes(type.name)}
                  onChange={(e) => onHidden({ ...hidden, entityTypes: toggled(hidden.entityTypes, type.name, e.target.checked) })}
                />{" "}
                {type.label}{" "}
              </label>
            ))}
            <br />
            <span>관계 유형:</span>{" "}
            {relationTypes.map((name) => (
              <label key={name}>
                <input
                  type="checkbox"
                  data-relation-type={name}
                  checked={!hidden.relationTypes.includes(name)}
                  onChange={(e) =>
                    onHidden({ ...hidden, relationTypes: toggled(hidden.relationTypes, name, e.target.checked) })
                  }
                />{" "}
                {name}{" "}
              </label>
            ))}
          </p>
          {unmatched > 0 && (
            <p data-testid="pack-map-unmatched">
              관계 {map.grouped ? "묶음 " : ""}
              {unmatched}건은 끝이 지도의 점에 없어 그림에 그리지 못했습니다. 아래의 표에는 있습니다.
            </p>
          )}
          {filtered && (
            <p data-testid="pack-map-filtered">가린 것이 있습니다. 위의 수와 아래의 표, 목록은 가리기 전의 것 그대로입니다.</p>
          )}
          {/* 그림은 캔버스라 시험이 안을 읽지 못한다. 무엇을 그렸는지와 점의 자리를 이 칸에 적어 둔다. */}
          <div
            data-testid="pack-map-graph"
            data-view={mapView}
            data-mode={map.grouped ? "groups" : "nodes"}
            data-node-ids={JSON.stringify(shownNodes)}
            data-link-ids={JSON.stringify(shownLinks)}
            data-link-bends={JSON.stringify(drawn.links.map((link) => link.bend))}
            data-node-spots={JSON.stringify(spots)}
          >
            {shownNodes.length === 0 && (
              <p data-testid="pack-map-all-hidden">켜 둔 유형이 없어 그림에 보일 것이 없습니다. "보일 것"에서 유형을 켜세요.</p>
            )}
            {shownNodes.length > 0 && mapView === "2d" && (
              <PackMapGraph map={shown} onPick={onPick} onPickType={onPickType} onSpots={setSpots} />
            )}
            {shownNodes.length > 0 && mapView === "3d" && Graph3D && (
              <Graph3D map={shown} onPick={onPick} onPickType={onPickType} />
            )}
            {mapView === "3d" && !Graph3D && loading3D === "loading" && <p>3D 그림을 불러오는 중입니다…</p>}
            {mapView === "3d" && !Graph3D && loading3D === "failed" && (
              <p role="alert">3D 그림을 불러오지 못했습니다. 연결을 확인하고 3D 를 다시 고르거나, 2D 로 보세요.</p>
            )}
          </div>
          <p>
            <small>
              화살표는 관계에 적힌 방향(출발에서 도착)입니다. 점을 누르면{" "}
              {map.grouped ? "위의 개체 찾기가 그 유형으로 걸러집니다." : "그 개체를 가운데로 지도를 엽니다."}
            </small>
          </p>
        </>
      )}

      {map.connectedEntities > 0 && (
        <p data-testid="type-group-note">
          <small>
            아래의 수는 그 유형에 적힌 개체 가운데 이 조회 시점에 관계가 있는 것만 센 것입니다. "이 유형의 개체 찾기"는 그
            유형의 하위 유형과 관계가 없는 개체까지 찾으므로 찾은 수가 더 많을 수 있습니다.
          </small>
        </p>
      )}
      {map.connectedEntities > 0 && (
        <table>
          <caption>유형마다의 이어진 개체</caption>
          <thead>
            <tr>
              <th scope="col">유형</th>
              <th scope="col">이어진 개체의 수</th>
              <th scope="col">찾기</th>
            </tr>
          </thead>
          <tbody>
            {map.typeGroups.map((group) => (
              <tr key={group.entityType} data-type-group={group.entityType}>
                <td>
                  {group.entityTypeLabel}{" "}
                  {group.entityTypeLabel !== group.entityType && (
                    <small>
                      <code>{group.entityType}</code>
                    </small>
                  )}
                </td>
                <td data-testid="type-group-count">{group.entityCount}</td>
                <td>
                  <button
                    type="button"
                    aria-label={`${group.entityTypeLabel} 유형의 개체 찾기`}
                    onClick={() => onPickType({ packId: map.packId, entityType: group.entityType, label: group.entityTypeLabel })}
                  >
                    이 유형의 개체 찾기
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {map.connectedEntities > 0 && (
        <table>
          <caption>유형 사이의 관계</caption>
          <thead>
            <tr>
              <th scope="col">출발 유형</th>
              <th scope="col">관계 유형</th>
              <th scope="col">도착 유형</th>
              <th scope="col">관계의 수</th>
            </tr>
          </thead>
          <tbody>
            {map.relationGroups.map((group) => (
              <tr
                key={`${group.sourceEntityType}|${group.relationType}|${group.targetEntityType}`}
                data-relation-group={`${group.sourceEntityType}|${group.relationType}|${group.targetEntityType}`}
              >
                {/* 유형은 위의 표와 같은 이름표로 보인다. 관계 유형의 이름표는 서버가 주지 않아 이름 그대로다. */}
                <td data-testid="relation-group-source">
                  <TypeName map={map} entityType={group.sourceEntityType} />
                </td>
                <td>{group.relationType}</td>
                <td data-testid="relation-group-target">
                  <TypeName map={map} entityType={group.targetEntityType} />
                </td>
                <td data-testid="relation-group-count">{group.relationCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {!map.grouped && map.nodes.length > 0 && (
        <ul className="pack-map-nodes" aria-label="전체 지도의 개체">
          {map.nodes.map((node) => (
            <li key={node.entityId} data-pack-map-node={node.entityId}>
              {node.entity.entityTypeLabel}: {node.entity.displayName ?? node.entity.entityKey}{" "}
              <button
                type="button"
                aria-label={`${node.entity.displayName ?? node.entity.entityKey} 을(를) 가운데로 지도 열기`}
                onClick={() => onPick(node.entityId)}
              >
                가운데로
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

// 유형의 이름표. 서버가 준 유형의 묶음에서 찾는다 (관계의 묶음에 나오는 유형은 모두 거기에 있다). 없으면 이름이다.
function labelOf(map: PackRelationMap, entityType: string): string {
  return map.typeGroups.find((group) => group.entityType === entityType)?.entityTypeLabel ?? entityType;
}

function TypeName({ map, entityType }: { map: PackRelationMap; entityType: string }) {
  const label = labelOf(map, entityType);
  return (
    <>
      {label}{" "}
      {label !== entityType && (
        <small>
          <code>{entityType}</code>
        </small>
      )}
    </>
  );
}

