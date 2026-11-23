import { useEffect, useMemo, useRef } from "react";
import ForceGraph2D, { type ForceGraphMethods, type LinkObject, type NodeObject } from "react-force-graph-2d";
import type { RelationMap } from "../api/client";

// 관계 지도의 그림 (D-08, D2 AD-352, AD-353). 관계 지도 API 의 답을 그대로 그린다.
//
// 이 화면은 계산하지 않는다.
// - 어느 개체가 이어져 있는지, 깊이, 순서, 잘렸는지, 어디까지 따라갔는지, 전체 수는 서버가 준 값이다. 화면이 다시 세거나
//   고르지 않는다.
// - 화면이 정하는 것은 그리는 자리뿐이다. 처음 자리는 서버가 준 순서로 깊이마다 둥글게 놓고, 라이브러리의
//   힘 배치를 정해진 횟수만 돌린 뒤 멈춘다. 같은 답이면 같은 그림이 나온다 (움직이는 그림이 아니다).
//
// 그림은 캔버스라 화면 낭독기와 화면 시험이 읽지 못한다. 같은 답으로 만든 노드 목록을 옆에 둔다:
// 목록에서도 개체를 가운데로 옮길 수 있다.

const WIDTH = 760;
const HEIGHT = 460;
const RING = 90;
const WARMUP_TICKS = 80;
// 화면에 맞출 때의 확대 한도. 노드가 몇 안 되면 맞추느라 점이 지나치게 커진다.
const MAX_FIT_ZOOM = 2.5;

type Node = { id: string; label: string; typeLabel: string; depth: number };
type Link = { id: string; relationType: string; curvature: number };

export function RelationMapView({ map, onCenter }: { map: RelationMap; onCenter: (entityId: string) => void }) {
  const graph = useRef<ForceGraphMethods<NodeObject<Node>, LinkObject<Node, Link>> | undefined>(undefined);

  // 라이브러리는 받은 노드에 자리(x, y)를 적어 넣는다. 서버의 답을 건드리지 않게 옮겨 담는다.
  const data = useMemo(() => {
    const perDepth = new Map<number, number>();
    for (const node of map.nodes) {
      perDepth.set(node.depth, (perDepth.get(node.depth) ?? 0) + 1);
    }
    const placed = new Map<number, number>();
    const nodes: NodeObject<Node>[] = map.nodes.map((node) => {
      const index = placed.get(node.depth) ?? 0;
      placed.set(node.depth, index + 1);
      const angle = (2 * Math.PI * index) / (perDepth.get(node.depth) ?? 1);
      return {
        id: node.entityId,
        label: node.entity.displayName ?? node.entity.entityKey,
        typeLabel: node.entity.entityTypeLabel,
        depth: node.depth,
        x: node.depth * RING * Math.cos(angle),
        y: node.depth * RING * Math.sin(angle),
      };
    });
    // 같은 두 개체 사이의 관계가 여럿이면 겹치지 않게 휘어 그린다 (서버가 준 순서대로 번갈아).
    const perPair = new Map<string, number>();
    const links: LinkObject<Node, Link>[] = map.edges.map((edge) => {
      const pair = [edge.sourceEntityId, edge.targetEntityId].sort().join("\u0000");
      const nth = perPair.get(pair) ?? 0;
      perPair.set(pair, nth + 1);
      const side = edge.sourceEntityId <= edge.targetEntityId ? 1 : -1;
      return {
        id: edge.id,
        source: edge.sourceEntityId,
        target: edge.targetEntityId,
        relationType: edge.relationType,
        curvature: nth === 0 ? 0 : side * 0.25 * Math.ceil(nth / 2) * (nth % 2 === 1 ? 1 : -1),
      };
    });
    return { nodes, links };
  }, [map]);

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
    <section
      aria-label="지도"
      data-testid="relation-map"
      data-center={map.centerEntityId}
      data-as-of={map.asOf}
      data-depth={map.depth}
    >
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
            context.textBaseline = "top";
            context.fillStyle = "#1f1f1f";
            context.fillText(node.label, node.x ?? 0, (node.y ?? 0) + 7);
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
      <p>
        <small>
          붉은 점이 가운데 개체입니다. 화살표는 관계에 적힌 방향(출발에서 도착)입니다. 점을 누르면 그 개체를 가운데로
          옮깁니다.
        </small>
      </p>
      <ul aria-label="지도의 노드" style={{ maxHeight: "14rem", overflowY: "auto" }}>
        {map.nodes.map((node) => (
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
