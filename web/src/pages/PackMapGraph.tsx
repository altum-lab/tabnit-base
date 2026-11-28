import { useEffect, useMemo, useRef } from "react";
import ForceGraph2D, { type ForceGraphMethods, type LinkObject, type NodeObject } from "react-force-graph-2d";
import {
  buildPackGraph,
  HEIGHT,
  MAX_LABELLED,
  type PackGraphLink,
  type PackGraphNode,
  type PackGraphProps,
  pickNode,
  WARMUP_TICKS,
  WIDTH,
} from "./packMapData";

// Pack 의 전체 지도의 2D 그림 (카드 A-20). 개체 하나의 둘레의 지도(RelationMapView)와 같은 그래프 그림이다.
// 무엇을 그리는지는 packMapData.ts 가 서버의 답에서 옮겨 담는다. 이 파일은 그리기만 한다.
//
// 그림은 캔버스라 화면 낭독기와 화면 시험이 읽지 못한다. 같은 답이 아래의 표와 목록에 있다.

type Node = NodeObject<PackGraphNode>;
type Link = LinkObject<PackGraphNode, PackGraphLink>;

// 화면에 맞출 때의 확대 한도. 점이 몇 안 되면 맞추느라 지나치게 커진다.
const MAX_FIT_ZOOM = 2.5;

export type Spot = { id: string; x: number; y: number };

export function PackMapGraph({
  map,
  onPick,
  onPickType,
  onSpots,
}: PackGraphProps & {
  // 배치가 끝나 화면에 맞춘 뒤, 점마다 그림 칸 안의 자리를 알린다 (그림 칸의 왼쪽 위에서 잰 점).
  onSpots: (spots: Spot[]) => void;
}) {
  const graph = useRef<ForceGraphMethods<Node, Link> | undefined>(undefined);
  const data = useMemo(() => buildPackGraph(map, 2), [map]);
  const grouped = data.grouped;
  const labelled = grouped || data.nodes.length <= MAX_LABELLED;
  const report = useRef(onSpots);
  report.current = onSpots;

  // 배치가 끝난 그림을 한눈에 들어오게 맞춘다. 너무 크게 키우지는 않는다.
  // 점의 자리는 배치가 끝났다는 알림에서만 알린다: 그 전의 자리는 처음에 놓은 원 위의 자리다.
  function fit(settled: boolean) {
    const current = graph.current;
    if (!current) {
      return;
    }
    current.zoomToFit(0, 50);
    if (current.zoom() > MAX_FIT_ZOOM) {
      current.zoom(MAX_FIT_ZOOM, 0);
    }
    if (!settled) {
      return;
    }
    report.current(
      data.nodes.map((node) => {
        const at = current.graph2ScreenCoords(node.x, node.y);
        return { id: node.id, x: Math.round(at.x), y: Math.round(at.y) };
      }),
    );
  }

  useEffect(() => {
    const timer = window.setTimeout(() => fit(false), 0);
    return () => window.clearTimeout(timer);
  }, [data]);

  return (
    <div
      role="img"
      aria-label={
        grouped
          ? `Pack 전체 지도 그림: 유형 ${data.nodes.length}개로 묶었습니다. 같은 내용이 아래의 표에 있습니다.`
          : `Pack 전체 지도 그림: 개체 ${data.nodes.length}개와 관계 ${data.links.length}건. 같은 내용이 아래의 목록에 있습니다.`
      }
    >
      <ForceGraph2D
        ref={graph}
        graphData={data}
        width={WIDTH}
        height={HEIGHT}
        warmupTicks={WARMUP_TICKS}
        cooldownTicks={0}
        enableNodeDrag={false}
        nodeRelSize={grouped ? 8 : 4}
        nodeColor={() => "#3b6ea5"}
        nodeLabel={(node) => node.hover}
        nodeCanvasObjectMode={() => "after"}
        nodeCanvasObject={(node, context, scale) => {
          if (!labelled) {
            return;
          }
          const size = 12 / scale;
          context.font = `${size}px sans-serif`;
          context.textAlign = "center";
          context.textBaseline = "top";
          const y = (node.y ?? 0) + (grouped ? 10 : 6);
          // 선이나 다른 이름과 겹쳐도 읽히게 글자의 둘레를 바탕색으로 두른다.
          context.lineWidth = 3 / scale;
          context.strokeStyle = "#ffffff";
          context.strokeText(node.label, node.x ?? 0, y);
          context.fillStyle = "#1f1f1f";
          context.fillText(node.label, node.x ?? 0, y);
        }}
        linkLabel={(link) => link.hover}
        linkCurvature={(link) => link.curvature}
        linkColor={() => "#8a8a8a"}
        linkDirectionalArrowLength={grouped ? 6 : 4}
        linkDirectionalArrowRelPos={1}
        onNodeClick={(node) => pickNode(map, String(node.id), onPick, onPickType)}
        onEngineStop={() => fit(true)}
      />
    </div>
  );
}
