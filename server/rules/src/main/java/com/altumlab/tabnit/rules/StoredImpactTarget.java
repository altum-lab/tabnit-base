package com.altumlab.tabnit.rules;

import java.time.LocalDate;
import java.util.List;

/**
 * 저장된 영향 대상 하나 (D5 6절 impact_target).
 *
 * @param depth 깊이. 근거 경로의 단계의 수와 같다
 * @param pathStartEntityId 근거 경로가 시작한 변동 대상
 * @param pathAsOf 그 경로의 관계가 모두 유효한 기준 날짜. 영향 범위의 두 날짜 가운데 하나다 (D2 AD-116)
 * @param steps 근거 경로. 변동 대상 쪽부터의 차례다. 하나 이상이다
 */
public record StoredImpactTarget(String entityId, String entityType, int depth, String pathStartEntityId,
        LocalDate pathAsOf, List<StoredPathStep> steps) {
    public StoredImpactTarget {
        steps = List.copyOf(steps);
    }
}
