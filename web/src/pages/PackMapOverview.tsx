import { type FormEvent, useEffect, useRef, useState } from "react";
import { api, type PackRelationMap, type PackSummary } from "../api/client";
import { CONNECTION_FAILED, messageOf } from "../api/problem";

// Pack 의 전체 지도 (카드 A-20, D7 4절 "Pack 의 전체 지도", D2 AD-419).
// 서버의 getPackRelationMap (docs/api/openapi.yaml) 의 답을 그대로 그린다.
//
// 이 칸은 계산하지 않는다.
// - 무엇이 이어져 있는지, 묶을지, 수와 차례는 서버가 준 것이다. 다시 세거나 정렬하지 않는다.
// - 오늘을 정하지 않는다. 조회 시점이 비어 있으면 서버가 정하고, 서버가 답한 날짜를 보여 준다.
// - 그림의 자리는 서버가 준 차례대로 원 위에 놓는다: 같은 답이면 같은 그림이다 (난수를 쓰지 않는다).

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
  // 개체 하나를 가운데로 지도를 연다.
  onPick: (entityId: string) => void;
  // 유형 묶음을 누르면 개체 찾기를 그 유형으로 거른다.
  onPickType: (filter: { packId: string; entityType: string; label: string }) => void;
};

const SIZE = 560;
const CENTER = SIZE / 2;
const RING = 220;

// 차례대로 원 위에 놓는다. 하나뿐이면 가운데다.
function place(index: number, count: number): { x: number; y: number } {
  if (count === 1) {
    return { x: CENTER, y: CENTER };
  }
  const angle = (2 * Math.PI * index) / count - Math.PI / 2;
  return { x: Math.round(CENTER + RING * Math.cos(angle)), y: Math.round(CENTER + RING * Math.sin(angle)) };
}

export function PackMapOverview({ asOf, onPick, onPickType }: Props) {
  const [packs, setPacks] = useState<Packs>({ kind: "loading" });
  const [packId, setPackId] = useState("");
  const [view, setView] = useState<View>({ kind: "idle" });
  const latest = useRef(0);

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
    if (opened.current !== null) {
      void fetchMap(opened.current);
    }
    // 조회 시점이 바뀔 때만 다시 받는다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [asOf]);

  function open(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    opened.current = packId;
    void fetchMap(packId);
  }

  async function fetchMap(wanted: string) {
    const turn = ++latest.current;
    setView({ kind: "loading" });
    try {
      const { data, error, response } = await api.GET("/api/v1/pack-relation-map", {
        params: { query: { packId: wanted, ...(asOf ? { asOf } : {}) } },
      });
      if (turn !== latest.current) {
        return;
      }
      setView(data ? { kind: "loaded", map: data } : { kind: "failed", message: messageOf(error, response.status) });
    } catch {
      if (turn === latest.current) {
        setView({ kind: "failed", message: CONNECTION_FAILED });
      }
    }
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

      {view.kind === "loading" && <p data-testid="pack-map-loading">전체 지도를 불러오는 중입니다…</p>}
      {view.kind === "failed" && <p role="alert">{view.message}</p>}
      {view.kind === "loaded" && <Overview map={view.map} onPick={onPick} onPickType={onPickType} />}
    </section>
  );
}

function Overview({ map, onPick, onPickType }: { map: PackRelationMap } & Pick<Props, "onPick" | "onPickType">) {
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
      {map.connectedEntities > 0 && (map.grouped ? <GroupDrawing map={map} /> : <NodeDrawing map={map} />)}

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

// 한도 안: 개체마다 점 하나, 관계마다 선 하나. 자리는 서버가 준 차례대로다.
function NodeDrawing({ map }: { map: PackRelationMap }) {
  const at = new Map(map.nodes.map((node, index) => [node.entityId, place(index, map.nodes.length)]));
  return (
    <svg
      className="pack-map-drawing"
      viewBox={`0 0 ${SIZE} ${SIZE}`}
      width={SIZE}
      height={SIZE}
      role="img"
      aria-label={`개체 ${map.nodes.length}개와 관계 ${map.edges.length}건의 지도`}
    >
      {map.edges.map((edge) => {
        const from = at.get(edge.sourceEntityId);
        const to = at.get(edge.targetEntityId);
        return from && to ? (
          <line key={edge.id} data-pack-map-edge={edge.id} x1={from.x} y1={from.y} x2={to.x} y2={to.y} stroke="#8a8a8a" />
        ) : null;
      })}
      {map.nodes.map((node) => {
        const spot = at.get(node.entityId)!;
        return (
          <g key={node.entityId}>
            <circle cx={spot.x} cy={spot.y} r={5} fill="#3b6ea5">
              <title>
                {node.entity.entityTypeLabel}: {node.entity.displayName ?? node.entity.entityKey}
              </title>
            </circle>
            {map.nodes.length <= 40 && (
              <text x={spot.x} y={spot.y - 9} textAnchor="middle" fontSize="11">
                {node.entity.displayName ?? node.entity.entityKey}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

type Pair = { key: string; first: string; second: string; groups: PackRelationMap["relationGroups"] };

// 관계의 묶음을 두 유형의 짝으로 모은다 (방향을 가리지 않는다). 같은 유형 안의 묶음은 뺀다: 두 끝이 같은 자리라
// 선으로 그리지 않는다 (표에 있다). 짝의 차례는 그 짝이 서버의 묶음에 처음 나온 차례다.
function pairs(map: PackRelationMap): Pair[] {
  const found = new Map<string, Pair>();
  for (const group of map.relationGroups) {
    if (group.sourceEntityType === group.targetEntityType) {
      continue;
    }
    const [first, second] =
      group.sourceEntityType < group.targetEntityType
        ? [group.sourceEntityType, group.targetEntityType]
        : [group.targetEntityType, group.sourceEntityType];
    const key = `${first}|${second}`;
    const pair = found.get(key) ?? { key, first, second, groups: [] };
    pair.groups = [...pair.groups, group];
    found.set(key, pair);
  }
  return [...found.values()];
}

// 한도 밖: 유형마다 점 하나, 두 유형의 짝마다 선 하나. 수를 함께 적는다.
function GroupDrawing({ map }: { map: PackRelationMap }) {
  const at = new Map(map.typeGroups.map((group, index) => [group.entityType, place(index, map.typeGroups.length)]));
  return (
    <svg
      className="pack-map-drawing"
      viewBox={`0 0 ${SIZE} ${SIZE}`}
      width={SIZE}
      height={SIZE}
      role="img"
      aria-label={`유형 ${map.typeGroups.length}개로 묶은 지도`}
    >
      {/* 같은 두 유형 사이의 묶음들(방향이 다른 것, 관계 유형이 다른 것)은 선 하나로 그리고 그 모두를 적는다:
          따로 그리면 같은 자리에 겹쳐 하나만 보인다. 차례는 서버가 준 묶음의 차례다. */}
      {pairs(map).map((pair) => {
        const from = at.get(pair.first);
        const to = at.get(pair.second);
        return from && to ? (
          <line
            key={pair.key}
            data-pack-map-pair={pair.key}
            data-group-count={pair.groups.length}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            stroke="#8a8a8a"
          >
            <title>
              {pair.groups
                .map(
                  (group) =>
                    `${labelOf(map, group.sourceEntityType)} → ${labelOf(map, group.targetEntityType)} (${group.relationType}) ${group.relationCount}건`,
                )
                .join("\n")}
            </title>
          </line>
        ) : null;
      })}
      {map.typeGroups.map((group) => {
        const spot = at.get(group.entityType)!;
        return (
          <g key={group.entityType} data-pack-map-group={group.entityType}>
            <circle cx={spot.x} cy={spot.y} r={16} fill="#3b6ea5" />
            <text x={spot.x} y={spot.y - 22} textAnchor="middle" fontSize="12">
              {group.entityTypeLabel} ({group.entityCount})
            </text>
          </g>
        );
      })}
    </svg>
  );
}
