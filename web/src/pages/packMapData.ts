import type { PackRelationMap } from "../api/client";

// Pack 의 전체 지도의 그림에 넘길 자료 (카드 A-20). 2D 와 3D 그림이 함께 쓴다.
//
// 서버의 답을 그리는 라이브러리의 모양으로 옮겨 담을 뿐이다. 한도 안이면 서버가 준 개체와 관계를, 한도 밖이면
// 서버가 준 유형 묶음과 관계 묶음을 그대로 담는다. 다시 세거나 고르거나 차례를 바꾸지 않는다.
// 화면이 정하는 것은 그리는 자리뿐이다. 처음 자리를 서버가 준 차례로 정해 두면 같은 답에서 같은 그림이 나온다
// (그리는 라이브러리의 힘 배치는 처음 자리에서 정해진 횟수만 돈다. 난수를 쓰지 않는다).

export type PackGraphNode = { id: string; label: string; hover: string; x: number; y: number; z?: number };
export type PackGraphLink = { id: string; source: string; target: string; hover: string; curvature: number; bend: number };
export type PackGraph = { grouped: boolean; nodes: PackGraphNode[]; links: PackGraphLink[] };

// 가린 것의 이름. 켜 둔 것이 아니라 가린 것을 둔다: 다시 받은 답에 새 유형이 나오면 켜진 채로 보인다.
export type PackMapFilter = { entityTypes: string[]; relationTypes: string[] };

export type PickType = (filter: { packId: string; entityType: string; label: string }) => void;

type End = { id: string; label: string; hover: string };
type Line = { id: string; source: string; target: string; hover: string };

// 관계의 묶음의 이름. 표의 줄과 같은 이름이다.
export function relationGroupId(group: PackRelationMap["relationGroups"][number]): string {
  return `${group.sourceEntityType}|${group.relationType}|${group.targetEntityType}`;
}

// 서버의 답에서 가린 것을 뺀다. 관계는 관계 유형이 켜져 있고 두 끝이 모두 남은 것만 남긴다. 차례는 그대로다.
// 서버가 준 수(이어진 개체의 수, 관계의 수)는 바꾸지 않는다: 그림에 넘길 것만 줄인다.
export function filterPackMap(map: PackRelationMap, hidden: PackMapFilter): PackRelationMap {
  if (hidden.entityTypes.length === 0 && hidden.relationTypes.length === 0) {
    return map;
  }
  const nodes = map.nodes.filter((node) => !hidden.entityTypes.includes(node.entity.entityType));
  const kept = new Set(nodes.map((node) => node.entityId));
  return {
    ...map,
    nodes,
    edges: map.edges.filter(
      (edge) =>
        !hidden.relationTypes.includes(edge.relationType) && kept.has(edge.sourceEntityId) && kept.has(edge.targetEntityId),
    ),
    typeGroups: map.typeGroups.filter((group) => !hidden.entityTypes.includes(group.entityType)),
    relationGroups: map.relationGroups.filter(
      (group) =>
        !hidden.relationTypes.includes(group.relationType) &&
        !hidden.entityTypes.includes(group.sourceEntityType) &&
        !hidden.entityTypes.includes(group.targetEntityType),
    ),
  };
}

function place(index: number, count: number, radius: number, dimensions: 2 | 3): { x: number; y: number; z?: number } {
  if (dimensions === 2) {
    // 차례대로 원 위에 놓는다. 하나뿐이면 가운데다.
    if (count === 1) {
      return { x: 0, y: 0 };
    }
    const angle = (2 * Math.PI * index) / count - Math.PI / 2;
    return { x: radius * Math.cos(angle), y: radius * Math.sin(angle) };
  }
  // 공의 겉에 고르게 놓는다 (위에서 아래로 내려가며 한 바퀴씩 도는 자리).
  if (count === 1) {
    return { x: 0, y: 0, z: 0 };
  }
  const height = 1 - (2 * (index + 0.5)) / count;
  const around = Math.sqrt(Math.max(0, 1 - height * height));
  const turn = index * Math.PI * (3 - Math.sqrt(5));
  return { x: radius * around * Math.cos(turn), y: radius * height, z: radius * around * Math.sin(turn) };
}

function build(grouped: boolean, ends: End[], lines: Line[], dimensions: 2 | 3): PackGraph {
  // 많을수록 처음의 원이 커진다.
  const radius = Math.max(60, ends.length * 6);
  const nodes = ends.map((end, index) => ({ ...end, ...place(index, ends.length, radius, dimensions) }));
  // 같은 두 끝 사이의 선이 여럿이면 겹치지 않게 번갈아 휘어 그린다 (서버가 준 차례대로).
  // 라이브러리의 휨은 선의 방향을 기준으로 하므로, 방향이 반대인 선은 부호를 뒤집어야 같은 쪽으로 휜다.
  // bend 는 두 끝을 이름 차례로 놓고 본 휨이다 (방향이 반대인 선도 같은 기준으로 견줄 수 있다).
  // 끝이 점에 없는 선은 그리지 못한다 (그리는 라이브러리가 멈춘다). 뺀다: 화면이 몇을 뺐는지 알린다.
  const known = new Set(ends.map((end) => end.id));
  const perPair = new Map<string, number>();
  const links = lines
    .filter((line) => known.has(line.source) && known.has(line.target))
    .map((line) => {
    const pair = [line.source, line.target].sort().join("\u0000");
    const nth = perPair.get(pair) ?? 0;
    perPair.set(pair, nth + 1);
    if (line.source === line.target) {
      // 두 끝이 같으면 고리로 그린다. 여럿이면 고리를 점점 크게 한다.
      const loop = 0.6 + 0.3 * nth;
      return { ...line, curvature: loop, bend: loop };
    }
    const bend = nth === 0 ? 0 : 0.25 * Math.ceil(nth / 2) * (nth % 2 === 1 ? 1 : -1);
    const side = line.source <= line.target ? 1 : -1;
    return { ...line, curvature: side * bend, bend };
    });
  return { grouped, nodes, links };
}

export function buildPackGraph(map: PackRelationMap, dimensions: 2 | 3): PackGraph {
  if (map.grouped) {
    // 이름표는 서버가 준 유형의 묶음에서 찾는다. 가려서 그림에 없는 유형은 선의 끝이 될 수 없으니 여기 없어도 된다.
    const labels = new Map(map.typeGroups.map((group) => [group.entityType, group.entityTypeLabel]));
    return build(
      true,
      map.typeGroups.map((group) => ({
        id: group.entityType,
        label: `${group.entityTypeLabel} (${group.entityCount})`,
        hover: `${group.entityTypeLabel}: 이어진 개체 ${group.entityCount}개`,
      })),
      map.relationGroups.map((group) => ({
        id: relationGroupId(group),
        source: group.sourceEntityType,
        target: group.targetEntityType,
        hover: `${labels.get(group.sourceEntityType) ?? group.sourceEntityType} → ${
          labels.get(group.targetEntityType) ?? group.targetEntityType
        } (${group.relationType}) ${group.relationCount}건`,
      })),
      dimensions,
    );
  }
  return build(
    false,
    map.nodes.map((node) => ({
      id: node.entityId,
      label: node.entity.displayName ?? node.entity.entityKey,
      hover: `${node.entity.entityTypeLabel}: ${node.entity.displayName ?? node.entity.entityKey}`,
    })),
    map.edges.map((edge) => ({
      id: edge.id,
      source: edge.sourceEntityId,
      target: edge.targetEntityId,
      hover: edge.relationType,
    })),
    dimensions,
  );
}

// 점을 눌렀을 때: 한도 안에서는 그 개체를 가운데로 지도를 열고, 묶음에서는 개체 찾기를 그 유형으로 거른다.
export function pickNode(map: PackRelationMap, id: string, onPick: (entityId: string) => void, onPickType: PickType) {
  if (!map.grouped) {
    onPick(id);
    return;
  }
  const group = map.typeGroups.find((item) => item.entityType === id);
  if (group) {
    onPickType({ packId: map.packId, entityType: group.entityType, label: group.entityTypeLabel });
  }
}

// 개체가 이보다 많으면 이름을 그림에 적지 않는다 (겹쳐서 읽을 수 없다). 점에 올리면 이름이 뜬다.
export const MAX_LABELLED = 40;

// 그림의 크기와 힘 배치를 돌리는 횟수. 2D 와 3D 가 같다.
export const WIDTH = 760;
export const HEIGHT = 460;
export const WARMUP_TICKS = 80;

export type PackGraphProps = {
  map: PackRelationMap;
  // 개체의 점을 누르면 그 개체를 가운데로 지도를 연다.
  onPick: (entityId: string) => void;
  // 유형 묶음의 점을 누르면 개체 찾기를 그 유형으로 거른다.
  onPickType: PickType;
};
