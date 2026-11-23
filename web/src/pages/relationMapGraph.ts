import type { RelationMap } from "../api/client";

// 관계 지도의 그림에 넘길 자료 (D-08, D-14). 2D 와 3D 그림이 함께 쓴다.
//
// 서버의 답을 그리는 라이브러리의 모양으로 옮겨 담을 뿐이다: 어느 개체가 이어져 있는지, 깊이, 순서는 서버가 준 값이다.
// 화면이 정하는 것은 그리는 자리뿐이다. 처음 자리를 서버가 준 순서로 정해 두면 같은 답에서 같은 그림이 나온다
// (그리는 라이브러리의 힘 배치는 처음 자리에서 정해진 횟수만 돈다).

export type GraphNode = { id: string; label: string; typeLabel: string; depth: number; x: number; y: number; z?: number };
export type GraphLink = { id: string; source: string; target: string; relationType: string; curvature: number };

const RING = 90;

export function buildGraph(map: RelationMap, dimensions: 2 | 3): { nodes: GraphNode[]; links: GraphLink[] } {
  const perDepth = new Map<number, number>();
  for (const node of map.nodes) {
    perDepth.set(node.depth, (perDepth.get(node.depth) ?? 0) + 1);
  }
  const placed = new Map<number, number>();
  const nodes = map.nodes.map((node) => {
    const index = placed.get(node.depth) ?? 0;
    placed.set(node.depth, index + 1);
    const count = perDepth.get(node.depth) ?? 1;
    const radius = node.depth * RING;
    const base = {
      id: node.entityId,
      label: node.entity.displayName ?? node.entity.entityKey,
      typeLabel: node.entity.entityTypeLabel,
      depth: node.depth,
    };
    if (dimensions === 2) {
      // 깊이마다 둥근 고리 위에 고르게 놓는다.
      const angle = (2 * Math.PI * index) / count;
      return { ...base, x: radius * Math.cos(angle), y: radius * Math.sin(angle) };
    }
    // 깊이마다 공의 겉에 고르게 놓는다 (위에서 아래로 내려가며 한 바퀴씩 도는 자리).
    const height = count === 1 ? 0 : 1 - (2 * (index + 0.5)) / count;
    const around = Math.sqrt(Math.max(0, 1 - height * height));
    const turn = index * Math.PI * (3 - Math.sqrt(5));
    return {
      ...base,
      x: radius * around * Math.cos(turn),
      y: radius * height,
      z: radius * around * Math.sin(turn),
    };
  });
  // 같은 두 개체 사이의 관계가 여럿이면 겹치지 않게 휘어 그린다 (서버가 준 순서대로 번갈아).
  const perPair = new Map<string, number>();
  const links = map.edges.map((edge) => {
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
}
