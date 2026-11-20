package com.altumlab.tabnit.core;

import java.time.LocalDate;
import java.util.List;

/**
 * 근거 경로. 영향 대상에 어떻게 도달했는가 (D3 7절, 보드 CORE-04.1).
 *
 * @param startEntityId 경로가 시작한 출발 개체의 식별자
 * @param asOf 이 경로의 관계가 모두 유효한 기준 날짜 (D2 AD-116)
 * @param relations 따라간 관계. 출발 개체 쪽부터의 차례다. 하나 이상이다
 */
public record ImpactPath(String startEntityId, LocalDate asOf, List<Relation> relations) {

    public ImpactPath {
        relations = List.copyOf(relations);
    }
}
