import type { EntityRef } from "../api/client";

// 개체 하나를 사람이 읽을 말로 적는다 (D7 2절 "개체", D2 AD-326, AD-349).
//
// 표시 이름, 유형의 이름표, 키는 서버가 준 값이다. 화면은 id 를 쪼개 유형이나 키를 만들지 않는다.
// 표시 이름이 없으면 (Pack 이 정하지 않았거나 그 날짜에 값이 없다) 키를 보여 준다.
// id 는 작게 함께 적는다: 관계 조회에 넣어 찾을 때 쓰는 값이다.
export function EntityName({ entity }: { entity: EntityRef }) {
  return (
    <span data-testid="entity" data-entity-id={entity.entityId}>
      <span data-testid="entity-name">{entity.displayName ?? entity.entityKey}</span>{" "}
      <small data-testid="entity-type">
        ({entity.entityTypeLabel} {entity.entityKey})
      </small>{" "}
      <small>
        <code>{entity.entityId}</code>
      </small>
    </span>
  );
}
