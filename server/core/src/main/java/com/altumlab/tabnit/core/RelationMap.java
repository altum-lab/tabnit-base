package com.altumlab.tabnit.core;

import java.util.List;

/**
 * 관계 지도: 개체 하나를 가운데 두고 기준 날짜에 유효한 관계로 이어진 이웃 (보드 CORE-05, D2 AD-352).
 *
 * @param nodes 노드. 깊이, 개체 유형, 식별자 순이다 (글자 코드 순). 가운데 개체가 맨 앞이다. 한도를 넘으면 앞에서부터 한도만큼이다
 * @param edges 간선. 담긴 노드끼리 이은, 기준 날짜에 유효한 관계다. 관계 유형, 출발 개체, 도착 개체, 관계의 식별자 순이다
 * @param totalNodes 자르기 전의 노드의 수. 가운데 개체를 넣어 센다
 * @param truncated 한도를 넘어 잘렸는가
 */
public record RelationMap(List<RelationMapNode> nodes, List<Relation> edges, int totalNodes, boolean truncated) {

    public RelationMap {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
