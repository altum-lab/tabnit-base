package com.altumlab.tabnit.core;

import java.util.List;

/**
 * 나눠 준 관계의 한 쪽 (D2 AD-347).
 *
 * @param relations 이 쪽의 관계. 순서는 나누기 전의 순서 그대로다
 * @param total 나누기 전의 전체 수. 기준 날짜에 유효한 관계의 수다
 */
public record RelationPage(List<Relation> relations, int total) {

    public RelationPage {
        relations = List.copyOf(relations);
    }
}
