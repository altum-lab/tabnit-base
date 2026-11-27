package com.altumlab.tabnit.core;

import java.util.List;

/**
 * Pack 의 전체 지도 (카드 A-20, D2 AD-419). 가운데 개체가 없다: 기준 날짜에 유효한 관계에 닿은 개체 모두다.
 *
 * <p>유형별 묶음은 언제나 있다. 개체 하나하나(노드와 간선)는 이어진 개체의 수가 한도 이하일 때만 있다.
 *
 * @param connectedEntities 기준 날짜에 유효한 관계에 닿은 개체의 수. 유형별 묶음의 개체 수를 더한 것과 같다
 * @param isolatedEntities 그 Pack 의 개체 가운데 기준 날짜에 유효한 관계가 하나도 없는 것의 수. 지도에 넣지 않는다
 * @param relations 기준 날짜에 유효한 관계의 수. 관계의 묶음의 관계 수를 더한 것과 같다
 * @param grouped 이어진 개체가 한도를 넘어 개체 하나하나를 담지 않았는가. 참이면 nodes 와 edges 가 비어 있다
 * @param typeGroups 유형마다의 이어진 개체의 수. 유형의 이름의 글자 코드 순이다
 * @param relationGroups (출발 개체의 유형, 관계 유형, 도착 개체의 유형)마다의 관계의 수. 그 셋의 글자 코드 순이다
 * @param nodes 이어진 개체. 유형, 식별자의 글자 코드 순이다
 * @param edges 유효한 관계. 관계 유형, 출발 개체, 도착 개체, 관계의 식별자의 글자 코드 순이다
 */
public record PackRelationMap(int connectedEntities, int isolatedEntities, int relations, boolean grouped,
        List<TypeGroup> typeGroups, List<RelationGroup> relationGroups, List<Node> nodes, List<Relation> edges) {

    public PackRelationMap {
        typeGroups = List.copyOf(typeGroups);
        relationGroups = List.copyOf(relationGroups);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** 유형 하나의 묶음. 개체에 적힌 유형 그대로다: 상위 유형으로 다시 묶지 않는다. */
    public record TypeGroup(String entityType, int entityCount) {
    }

    /** 두 유형 사이의 관계 유형 하나의 묶음. 방향은 관계에 적힌 그대로다. */
    public record RelationGroup(String sourceEntityType, String relationType, String targetEntityType, int relationCount) {
    }

    /** 이어진 개체 하나. */
    public record Node(String entityId, String entityType) {
    }
}
