package com.altumlab.tabnit.core;

import java.util.List;

/**
 * 두 개체 사이의 가장 짧은 경로 (D2 AD-413, D5 3절).
 *
 * @param found 경로를 찾았는가
 * @param limitReached 한도(최대 길이, 훑는 개체의 수) 때문에 멈췄는가. 참이면 "없다"가 아니라 "한도 안에서 찾지 못했다"다.
 *        찾았으면 거짓이다
 * @param relations 경로의 관계. 출발 개체 쪽부터 차례대로다. 찾지 못했거나 두 개체가 같으면 비어 있다.
 *        관계에 적힌 방향(출발, 도착)은 저장된 그대로다: 걸어간 방향과 다를 수 있다
 * @param entityIds 경로가 지나는 개체. 출발 개체에서 도착 개체까지 차례대로다 (관계의 수보다 하나 많다).
 *        찾지 못했으면 비어 있다
 */
public record RelationPath(boolean found, boolean limitReached, List<Relation> relations, List<String> entityIds) {

    public RelationPath {
        relations = List.copyOf(relations);
        entityIds = List.copyOf(entityIds);
    }
}
