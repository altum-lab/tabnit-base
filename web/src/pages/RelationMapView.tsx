import { type ComponentType, useEffect, useMemo, useRef, useState } from "react";
import ForceGraph2D, { type ForceGraphMethods, type LinkObject, type NodeObject } from "react-force-graph-2d";
import type { RelationMap } from "../api/client";
import { buildGraph, type GraphLink, type GraphNode } from "./relationMapGraph";

// 관계 지도의 그림 (D-08, D2 AD-352, AD-353). 관계 지도 API 의 답을 그대로 그린다.
//
// 이 화면은 계산하지 않는다.
// - 어느 개체가 이어져 있는지, 깊이, 순서, 잘렸는지, 어디까지 따라갔는지, 전체 수는 서버가 준 값이다. 화면이 다시 세거나
//   고르지 않는다.
// - 화면이 정하는 것은 그리는 자리뿐이다. 처음 자리는 서버가 준 순서로 깊이마다 둥글게 놓고, 라이브러리의
//   힘 배치를 정해진 횟수만 돌린 뒤 멈춘다. 같은 답이면 같은 그림이 나온다 (움직이는 그림이 아니다).
//
// 가리기 (카드 D-20): 유형과 관계 유형을 꺼서 그 노드와 간선을 그리지 않을 수 있다. 서버가 준 것에서 가리기만 한다:
// 남은 것의 순서는 서버가 준 순서 그대로이고, 서버가 준 수(전체 수, 잘렸는지)는 바꾸지 않는다. 다시 묻지 않는다.
//
// 그림은 캔버스라 화면 낭독기와 화면 시험이 읽지 못한다. 같은 답으로 만든 노드 목록을 옆에 둔다:
// 목록에서도 개체를 가운데로 옮길 수 있다.

const WIDTH = 760;
const HEIGHT = 460;
const WARMUP_TICKS = 80;
// 화면에 맞출 때의 확대 한도. 노드가 몇 안 되면 맞추느라 점이 지나치게 커진다.
const MAX_FIT_ZOOM = 2.5;

export type MapView = "2d" | "3d";

// 가린 것의 이름. 켜 둔 것이 아니라 가린 것을 둔다: 다시 받은 답에 새 유형이 나오면 켜진 채로 보인다.
export type MapFilter = { entityTypes: string[]; relationTypes: string[] };

// 서버의 답에서 가린 것을 뺀다. 간선은 관계 유형이 켜져 있고 두 끝이 모두 남은 것만 남긴다. 순서는 그대로다.
export function filterMap(map: RelationMap, hidden: MapFilter): RelationMap {
  if (hidden.entityTypes.length === 0 && hidden.relationTypes.length === 0) {
    return map;
  }
  const nodes = map.nodes.filter((node) => !hidden.entityTypes.includes(node.entity.entityType));
  const kept = new Set(nodes.map((node) => node.entityId));
  const edges = map.edges.filter(
    (edge) =>
      !hidden.relationTypes.includes(edge.relationType) && kept.has(edge.sourceEntityId) && kept.has(edge.targetEntityId),
  );
  return { ...map, nodes, edges };
}

function toggled(names: string[], name: string, on: boolean) {
  return on ? names.filter((candidate) => candidate !== name) : [...names, name];
}

type Node = NodeObject<GraphNode>;
type Link = LinkObject<GraphNode, GraphLink>;

type GraphProps = { map: RelationMap; onCenter: (entityId: string) => void };

// 3D 그림은 고를 때 내려받는다 (D2 AD-361). 한 번 받은 것은 화면을 열어 둔 동안 다시 받지 않는다.
let loaded3D: ComponentType<GraphProps> | null = null;

export function RelationMapView({
  map,
  view,
  onView,
  onCenter,
  hidden,
  onHidden,
}: GraphProps & {
  view: MapView;
  onView: (view: MapView) => void;
  hidden: MapFilter;
  onHidden: (hidden: MapFilter) => void;
}) {
  // 3D 그림의 코드를 받는 일의 상태: 받는 중, 받지 못함. 받았는지는 loaded3D 로 본다 (이 상태로 보지 않는다):
  // 받는 사이에 2D 로 돌아갔다 와도, 화면이 다시 그려져도 받은 코드는 그대로 있다.
  const [loading3D, setLoading3D] = useState<"loading" | "failed">("loading");
  // 받은 것이 화면에 반영되도록 다시 그리게 하는 값.
  const [, setArrived] = useState(0);

  useEffect(() => {
    if (view !== "3d" || loaded3D) {
      return;
    }
    let current = true;
    setLoading3D("loading");
    import("./RelationMapGraph3D")
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
  }, [view]);

  const Graph3D = loaded3D;

  // 고를 것: 서버의 답에 나온 유형과 관계 유형. 답에 처음 나온 차례다 (화면이 정렬하지 않는다).
  const entityTypes = useMemo(() => {
    const seen = new Map<string, string>();
    for (const node of map.nodes) {
      if (!seen.has(node.entity.entityType)) {
        seen.set(node.entity.entityType, node.entity.entityTypeLabel);
      }
    }
    return [...seen].map(([name, label]) => ({ name, label }));
  }, [map]);
  const relationTypes = useMemo(() => [...new Set(map.edges.map((edge) => edge.relationType))], [map]);
  const shown = useMemo(() => filterMap(map, hidden), [map, hidden]);
  const filtered = shown !== map && (shown.nodes.length !== map.nodes.length || shown.edges.length !== map.edges.length);

  return (
    <section
      aria-label="지도"
      data-testid="relation-map"
      data-center={map.centerEntityId}
      data-as-of={map.asOf}
      data-depth={map.depth}
      data-view={view}
      data-shown-edges={JSON.stringify(shown.edges.map((edge) => edge.id))}
    >
      <fieldset>
        <legend>그림</legend>
        <label>
          <input type="radio" name="map-view" checked={view === "2d"} onChange={() => onView("2d")} /> 2D
        </label>{" "}
        <label>
          <input type="radio" name="map-view" checked={view === "3d"} onChange={() => onView("3d")} /> 3D
        </label>{" "}
        <small>3D 는 마우스로 끌어 돌리고 굴려서 확대합니다.</small>
      </fieldset>
      <p role="group" aria-label="보일 것">
        <strong>보일 것</strong> <small>끄면 그 유형의 개체와 관계를 지도에 그리지 않습니다. 서버에 다시 묻지 않습니다.</small>
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
        {relationTypes.length > 0 && (
          <>
            <br />
            <span>관계 유형:</span>
          </>
        )}{" "}
        {relationTypes.map((name) => (
          <label key={name}>
            <input
              type="checkbox"
              data-relation-type={name}
              checked={!hidden.relationTypes.includes(name)}
              onChange={(e) => onHidden({ ...hidden, relationTypes: toggled(hidden.relationTypes, name, e.target.checked) })}
            />{" "}
            {name}{" "}
          </label>
        ))}
      </p>
      {map.truncated && map.reachedDepth < map.depth && (
        <p data-testid="map-truncated">
          이웃이 많아 깊이 {map.reachedDepth} 에서 더 따라가지 않았습니다. 거기까지 찾은 {map.totalNodes}개 가운데 가까운{" "}
          {map.maxNodes}개만 그렸습니다. 보려는 쪽의 개체를 가운데로 옮겨 보세요.
        </p>
      )}
      {map.truncated && map.reachedDepth >= map.depth && (
        <p data-testid="map-truncated">
          이웃이 많아 가까운 {map.maxNodes}개만 그렸습니다. 이 깊이 안의 개체는 모두 {map.totalNodes}개입니다. 보려는
          쪽의 개체를 가운데로 옮겨 보세요.
        </p>
      )}
      {filtered && (
        <p data-testid="map-filtered">
          가린 것이 있습니다. 위의 수와 아래의 관계 목록은 가리기 전의 것 그대로입니다.
        </p>
      )}
      {shown.nodes.length === 0 && (
        <p data-testid="map-empty">켜 둔 유형의 개체가 없어 지도에 보일 것이 없습니다. "보일 것"에서 유형을 켜세요.</p>
      )}
      {shown.nodes.length > 0 && view === "2d" && <Graph2D map={shown} onCenter={onCenter} />}
      {shown.nodes.length > 0 && view === "3d" && Graph3D && <Graph3D map={shown} onCenter={onCenter} />}
      {view === "3d" && !Graph3D && loading3D === "loading" && (
        <p data-testid="map-3d-loading">3D 그림을 불러오는 중입니다…</p>
      )}
      {view === "3d" && !Graph3D && loading3D === "failed" && (
        <p data-testid="map-3d-failed" role="alert">
          3D 그림을 불러오지 못했습니다. 연결을 확인하고 3D 를 다시 고르거나, 2D 로 보세요.
        </p>
      )}
      <p>
        <small>
          붉은 점이 가운데 개체입니다. 화살표는 관계에 적힌 방향(출발에서 도착)입니다. 점을 누르면 그 개체를 가운데로
          옮깁니다.
        </small>
      </p>
      <ul aria-label="지도의 노드" style={{ maxHeight: "14rem", overflowY: "auto" }}>
        {shown.nodes.map((node) => (
          <li key={node.entityId} data-node-id={node.entityId}>
            <span>{node.entity.displayName ?? node.entity.entityKey}</span>{" "}
            <small>
              ({node.entity.entityTypeLabel} {node.entity.entityKey})
            </small>{" "}
            <small>{node.depth === 0 ? "가운데" : `깊이 ${node.depth}`}</small>{" "}
            {node.depth !== 0 && (
              <button type="button" onClick={() => onCenter(node.entityId)}>
                가운데로
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

// 2D 그림. 처음 자리는 서버가 준 순서로 깊이마다 둥글게 놓고, 힘 배치를 정해진 횟수만 돌린 뒤 멈춘다.
function Graph2D({ map, onCenter }: GraphProps) {
  const graph = useRef<ForceGraphMethods<Node, Link> | undefined>(undefined);
  const data = useMemo(() => buildGraph(map, 2), [map]);

  // 배치가 끝난 그림을 한눈에 들어오게 맞춘다. 너무 크게 키우지는 않는다.
  function fit() {
    const current = graph.current;
    if (!current) {
      return;
    }
    current.zoomToFit(0, 40);
    if (current.zoom() > MAX_FIT_ZOOM) {
      current.zoom(MAX_FIT_ZOOM, 0);
      current.centerAt(0, 0, 0);
    }
  }

  useEffect(() => {
    const timer = window.setTimeout(fit, 0);
    return () => window.clearTimeout(timer);
  }, [data]);

  return (
    <div role="img" aria-label="관계 지도 그림. 같은 내용이 아래의 노드 목록에 있습니다.">
      <ForceGraph2D
        ref={graph}
        graphData={data}
        width={WIDTH}
        height={HEIGHT}
        warmupTicks={WARMUP_TICKS}
        cooldownTicks={0}
        enableNodeDrag={false}
        nodeRelSize={5}
        nodeColor={(node) => (node.depth === 0 ? "#b3261e" : "#3b6ea5")}
        nodeLabel={(node) => `${node.label} (${node.typeLabel})`}
        nodeCanvasObjectMode={() => "after"}
        nodeCanvasObject={(node, context, scale) => {
          const size = 12 / scale;
          context.font = `${size}px sans-serif`;
          context.textAlign = "center";
          // 가운데 개체의 이름은 점의 위에, 나머지는 점의 아래에 적는다. 가운데 개체의 옆에 붙은 이웃의 이름과
          // 한 줄에 이어져 읽히지 않게 한다 (D-10 의 리허설에서 겹쳐 보였다).
          const above = node.depth === 0;
          context.textBaseline = above ? "bottom" : "top";
          const y = (node.y ?? 0) + (above ? -7 : 7);
          // 선이나 다른 이름과 겹쳐도 읽히게 글자의 둘레를 바탕색으로 두른다.
          context.lineWidth = 3 / scale;
          context.strokeStyle = "#ffffff";
          context.strokeText(node.label, node.x ?? 0, y);
          context.fillStyle = "#1f1f1f";
          context.fillText(node.label, node.x ?? 0, y);
        }}
        linkLabel={(link) => link.relationType}
        linkCurvature={(link) => link.curvature}
        linkColor={() => "#8a8a8a"}
        linkDirectionalArrowLength={5}
        linkDirectionalArrowRelPos={1}
        onNodeClick={(node) => onCenter(String(node.id))}
        onEngineStop={fit}
      />
    </div>
  );
}
