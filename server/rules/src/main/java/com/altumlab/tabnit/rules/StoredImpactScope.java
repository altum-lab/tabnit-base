package com.altumlab.tabnit.rules;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 저장된 영향 범위 (D5 6절 impact_scope). 변동 이벤트마다 하나이고, 저장한 뒤에는 바뀌지 않는다 (D2 AD-119).
 *
 * @param asOfPrevious 계산에 쓴 기준 날짜 가운데 판의 적용일 전날
 * @param asOfEffective 판의 적용일 (D2 AD-100)
 * @param maxDepth 계산에 쓴 최대 깊이
 * @param truncated 최대 깊이에서 멈췄고 그 너머에 아직 찾지 못한 대상이 있는가
 * @param storedAt 저장한 때. 밖에서 받은 시각
 * @param targets 영향 대상. 깊이, 개체 유형, 개체 식별자 순이다 (D2 AD-61)
 */
public record StoredImpactScope(String changeEventId, String packId, LocalDate asOfPrevious, LocalDate asOfEffective,
        int maxDepth, boolean truncated, Instant storedAt, List<StoredImpactTarget> targets) {
    public StoredImpactScope {
        targets = List.copyOf(targets);
    }
}
