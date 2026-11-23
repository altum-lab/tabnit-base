import type { ChangeImpactScope, ImpactTarget } from "../api/client";
import { EntityName } from "./EntityName";

// 영향 대상 하나의 근거 경로 (D7 6절 "변동의 영향 범위"). 조치 화면과 영향 범위 화면이 함께 쓴다.
//
// 경로, 단계의 차례, 날짜는 서버가 준 값이다. 화면은 따라가거나 고르지 않는다.
export function ImpactPathView({ target, scope }: { target: ImpactTarget; scope: ChangeImpactScope }) {
  return (
    <>
      <p>
        시작한 변동 대상 <EntityName entity={target.path.start} /> 에서 관계 {target.depth}개를 건너 닿았습니다.{" "}
        <strong data-testid="path-basis">{pathBasis(target.path.asOf, scope)}</strong>: 관계는{" "}
        <time>{target.path.asOf}</time> 에 유효한 것입니다. 이 변동의 영향 범위는 판의 적용일 {scope.asOfEffective} 과 그
        전날 {scope.asOfPrevious}, 두 날짜의 관계로 보았습니다.
      </p>
      <ol>
        {target.path.steps.map((step) => (
          <li key={step.stepNo} data-relation-id={step.relationId}>
            {step.relationType}: <EntityName entity={step.source} /> → <EntityName entity={step.target} />
          </li>
        ))}
      </ol>
    </>
  );
}

// 근거 경로가 어느 날짜의 관계로 닿았는지를 말로 적는다 (D1 5절의 용어, D2 AD-327).
// 날짜는 서버가 준 값이다: 경로의 날짜가 판의 적용일이면 변동 후, 그 전날이면 변동 전이다.
function pathBasis(asOf: string, scope: ChangeImpactScope): string {
  if (asOf === scope.asOfEffective) {
    return "변동 후의 관계";
  }
  if (asOf === scope.asOfPrevious) {
    return "변동 전의 관계";
  }
  // 두 날짜 가운데 어느 것도 아니면 말을 붙이지 않는다. 날짜는 옆에 그대로 보인다.
  return "관계의 날짜";
}
