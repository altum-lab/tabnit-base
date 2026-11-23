import { useEffect, useMemo, useRef } from "react";
import ForceGraph3D, { type ForceGraphMethods, type LinkObject, type NodeObject } from "react-force-graph-3d";
import { CanvasTexture, Sprite, SpriteMaterial } from "three";
import type { RelationMap } from "../api/client";
import { buildGraph, type GraphLink, type GraphNode } from "./relationMapGraph";

// 관계 지도의 3D 그림 (D-14, D2 AD-361). 2D 그림과 같은 답을 같은 규칙으로 그린다.
//
// 이 파일은 3D 를 고를 때만 내려받는다 (three.js 가 크다). 그래서 다른 화면 파일이 이 파일을 바로 들여오지 않는다.
// 마우스로 끌면 돌고, 굴리면 확대된다. 점을 누르면 그 개체를 가운데로 옮긴다.

const WIDTH = 760;
const HEIGHT = 460;
const WARMUP_TICKS = 80;
// 카메라가 보는 각(라이브러리의 기본값, 도), 가장 가까이 서는 거리, 이름표가 잘리지 않게 두는 여유.
const VIEW_ANGLE = 50;
const MIN_DISTANCE = 120;
const NODE_MARGIN = 20;

type Node = NodeObject<GraphNode>;
type Link = LinkObject<GraphNode, GraphLink>;

// 이름표: 글자를 작은 그림으로 만들어 점 아래에 띄운다. 어느 쪽에서 봐도 보는 쪽을 향한다.
function label(text: string): Sprite {
  const size = 28;
  const canvas = document.createElement("canvas");
  const context = canvas.getContext("2d")!;
  context.font = `${size}px sans-serif`;
  canvas.width = Math.ceil(context.measureText(text).width) + 8;
  canvas.height = size + 8;
  // 크기를 바꾸면 글꼴이 처음으로 돌아가므로 다시 정한다.
  context.font = `${size}px sans-serif`;
  context.textBaseline = "middle";
  context.fillStyle = "#1f1f1f";
  context.fillText(text, 4, canvas.height / 2);
  const sprite = new Sprite(new SpriteMaterial({ map: new CanvasTexture(canvas), depthWrite: false }));
  sprite.scale.set(canvas.width / 5, canvas.height / 5, 1);
  sprite.position.set(0, -9, 0);
  return sprite;
}

export default function RelationMapGraph3D({ map, onCenter }: { map: RelationMap; onCenter: (entityId: string) => void }) {
  const graph = useRef<ForceGraphMethods<Node, Link> | undefined>(undefined);
  const data = useMemo(() => buildGraph(map, 3), [map]);

  // 그림 전체가 들어오게 카메라를 놓는다. 배치가 끝난 노드의 자리에서 셈한다: 노드를 모두 담는 공의 가운데를 보고,
  // 그 공이 화면에 들어올 만큼 물러선다. 라이브러리의 맞추기는 배치가 끝나기 전에 불리면 가운데에 바짝 붙는다.
  function frame() {
    const current = graph.current;
    const box = current?.getGraphBbox();
    if (!current || !box) {
      return;
    }
    const center = { x: (box.x[0] + box.x[1]) / 2, y: (box.y[0] + box.y[1]) / 2, z: (box.z[0] + box.z[1]) / 2 };
    const radius = Math.hypot(box.x[1] - box.x[0], box.y[1] - box.y[0], box.z[1] - box.z[0]) / 2;
    // 보는 각이 좁은 쪽(세로)에 공이 들어오는 거리. 노드가 하나뿐이어도 너무 다가서지 않는다.
    const distance = Math.max(MIN_DISTANCE, (radius + NODE_MARGIN) / Math.sin((VIEW_ANGLE / 2) * (Math.PI / 180)));
    current.cameraPosition({ x: center.x, y: center.y, z: center.z + distance }, center, 0);
  }

  useEffect(() => {
    // 배치가 끝났다는 알림(onEngineStop)이 먼저 오지만, 오지 않는 경우에도 한 번은 맞춘다.
    const timer = window.setTimeout(frame, 300);
    return () => window.clearTimeout(timer);
  }, [data]);

  return (
    <div
      data-testid="map-3d"
      role="img"
      aria-label="관계 지도 그림 (3D). 같은 내용이 아래의 노드 목록에 있습니다."
      style={{ width: WIDTH, height: HEIGHT }}
    >
      <ForceGraph3D
        ref={graph}
        graphData={data}
        width={WIDTH}
        height={HEIGHT}
        backgroundColor="#ffffff"
        showNavInfo={false}
        numDimensions={3}
        warmupTicks={WARMUP_TICKS}
        cooldownTicks={0}
        enableNodeDrag={false}
        nodeRelSize={4}
        nodeOpacity={1}
        nodeColor={(node) => (node.depth === 0 ? "#b3261e" : "#3b6ea5")}
        nodeLabel={(node) => `${node.label} (${node.typeLabel})`}
        nodeThreeObjectExtend={true}
        nodeThreeObject={(node) => label(node.label)}
        linkLabel={(link) => link.relationType}
        linkCurvature={(link) => link.curvature}
        linkColor={() => "#6f6f6f"}
        linkOpacity={0.9}
        linkDirectionalArrowLength={4}
        linkDirectionalArrowRelPos={1}
        onNodeClick={(node) => onCenter(String(node.id))}
        onEngineStop={frame}
      />
    </div>
  );
}
