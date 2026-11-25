package com.altumlab.tabnit.pack;

import java.util.List;
import java.util.Objects;

/**
 * 제약을 걸 자료 ({@link ConstraintChecker}). 부르는 쪽이 한 날짜에 유효한 것을 읽어 넘긴다 (D2 AD-391).
 * 이 모듈은 저장소에서 자료를 읽지 않고, 개체의 식별자를 풀어 읽지 않는다.
 *
 * @param entities 개체. 관계도 값도 없는 개체도 검사한다 (D2 AD-395)
 * @param relations 관계. 같은 두 개체의 같은 유형의 관계가 여럿이어도 하나로 본다
 * @param values 속성 값. 글자는 저장된 표기 그대로다 (D6 5절)
 */
public record ConstraintData(List<Entity> entities, List<Link> relations, List<Value> values) {

    public ConstraintData {
        entities = List.copyOf(entities);
        relations = List.copyOf(relations);
        values = List.copyOf(values);
    }

    /** 개체 하나. */
    public record Entity(String id, String entityType) {

        public Entity {
            Objects.requireNonNull(id, "개체의 식별자는 반드시 있어야 합니다");
            Objects.requireNonNull(entityType, "개체 유형은 반드시 있어야 합니다");
        }
    }

    /** 관계 하나: 출발 개체에서 도착 개체로. */
    public record Link(String sourceId, String targetId, String relationType) {

        public Link {
            Objects.requireNonNull(sourceId, "출발 개체의 식별자는 반드시 있어야 합니다");
            Objects.requireNonNull(targetId, "도착 개체의 식별자는 반드시 있어야 합니다");
            Objects.requireNonNull(relationType, "관계 유형은 반드시 있어야 합니다");
        }
    }

    /** 속성 값 하나. */
    public record Value(String entityId, String attributeName, String value) {

        public Value {
            Objects.requireNonNull(entityId, "개체의 식별자는 반드시 있어야 합니다");
            Objects.requireNonNull(attributeName, "속성의 이름은 반드시 있어야 합니다");
            Objects.requireNonNull(value, "값은 반드시 있어야 합니다");
        }
    }
}
