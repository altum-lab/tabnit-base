import { useCallback, useEffect, useMemo, useRef } from "react";
import ForceGraph3D, { type ForceGraphMethods, type LinkObject, type NodeObject } from "react-force-graph-3d";
import { CanvasTexture, Sprite, SpriteMaterial } from "three";
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

// Pack 의 전체 지도의 3D 그림 (카드 A-20, D2 AD-361). 2D 그림과 같은 답을 같은 규칙으로 그린다.
//
// 이 파일은 3D 를 고를 때만 내려받는다 (three.js 가 크다). 그래서 다른 화면 파일이 이 파일을 바로 들여오지 않는다.
// 마우스로 끌면 돌고, 굴리면 확대된다. 점을 누르는 것은 2D 와 같다.

// 카메라가 보는 각(라이브러리의 기본값, 도), 가장 가까이 서는 거리, 이름표가 잘리지 않게 두는 여유.
const VIEW_ANGLE = 50;
const MIN_DISTANCE = 120;
const NODE_MARGIN = 20;

type Node = NodeObject<PackGraphNode>;
type Link = LinkObject<PackGraphNode, PackGraphLink>;

// 그리는 라이브러리에 넘기는 함수는 다시 그릴 때마다 새로 만들지 않는다. 새 함수가 오면 라이브러리가 점을 다시 만들고
// 배치를 다시 끝낸 것으로 알려, 사람이 돌려 놓은 카메라가 처음 자리로 돌아간다.
const NODE_COLOR = () => "#3b6ea5";
const LINK_COLOR = () => "#6f6f6f";
const hoverOf = (item: { hover?: string }) => item.hover ?? "";
const curvatureOf = (link: Link) => link.curvature;
const NO_LABEL = () => new Sprite(new SpriteMaterial({ opacity: 0 }));

// 이름표: 글자를 작은 그림으로 만들어 점 아래에 띄운다. 어느 쪽에서 봐도 보는 쪽을 향한다.
function label(text: string, below: number): Sprite {
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
  sprite.position.set(0, -below, 0);
  return sprite;
}

export default function PackMapGraph3D({ map, onPick, onPickType }: PackGraphProps) {
  const graph = useRef<ForceGraphMethods<Node, Link> | undefined>(undefined);
  const data = useMemo(() => buildPackGraph(map, 3), [map]);
  const grouped = data.grouped;
  const labelled = grouped || data.nodes.length <= MAX_LABELLED;

  const nodeObject = useMemo(
    () => (labelled ? (node: Node) => label(node.label, grouped ? 14 : 9) : NO_LABEL),
    [labelled, grouped],
  );
  const press = useRef({ map, onPick, onPickType });
  press.current = { map, onPick, onPickType };
  const onNodeClick = useCallback(
    (node: Node) => pickNode(press.current.map, String(node.id), press.current.onPick, press.current.onPickType),
    [],
  );

  // 그림 전체가 들어오게 카메라를 놓는다. 배치가 끝난 점의 자리에서 셈한다: 점을 모두 담는 공의 가운데를 보고,
  // 그 공이 화면에 들어올 만큼 물러선다. 그릴 것이 바뀌었을 때 한 번만 놓는다 (framed).
  const framed = useRef<unknown>(null);
  const frame = useCallback(() => {
    const current = graph.current;
    const box = current?.getGraphBbox();
    if (!current || !box) {
      return;
    }
    const center = { x: (box.x[0] + box.x[1]) / 2, y: (box.y[0] + box.y[1]) / 2, z: (box.z[0] + box.z[1]) / 2 };
    const radius = Math.hypot(box.x[1] - box.x[0], box.y[1] - box.y[0], box.z[1] - box.z[0]) / 2;
    const distance = Math.max(MIN_DISTANCE, (radius + NODE_MARGIN) / Math.sin((VIEW_ANGLE / 2) * (Math.PI / 180)));
    current.cameraPosition({ x: center.x, y: center.y, z: center.z + distance }, center, 0);
  }, []);
  const frameOnce = useCallback(() => {
    if (framed.current !== graphData.current) {
      framed.current = graphData.current;
      frame();
    }
  }, [frame]);
  const graphData = useRef(data);
  graphData.current = data;

  useEffect(() => {
    // 배치가 끝났다는 알림(onEngineStop)이 먼저 오지만, 오지 않는 경우에도 한 번은 맞춘다.
    const timer = window.setTimeout(frameOnce, 300);
    return () => window.clearTimeout(timer);
  }, [data]);

  return (
    <div
      data-testid="pack-map-3d"
      role="img"
      aria-label="Pack 전체 지도 그림 (3D). 같은 내용이 아래의 표와 목록에 있습니다."
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
        nodeRelSize={grouped ? 8 : 4}
        nodeOpacity={1}
        nodeColor={NODE_COLOR}
        nodeLabel={hoverOf}
        nodeThreeObjectExtend={true}
        nodeThreeObject={nodeObject}
        linkLabel={hoverOf}
        linkCurvature={curvatureOf}
        linkColor={LINK_COLOR}
        linkOpacity={0.9}
        linkDirectionalArrowLength={4}
        linkDirectionalArrowRelPos={1}
        onNodeClick={onNodeClick}
        onEngineStop={frameOnce}
      />
    </div>
  );
}
