package com.altumlab.tabnit.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영향 범위 (D3 7절).
 *
 * <p>두 수의 합계는 각각 영향 대상 목록의 길이와 같다 (D2 AD-63).
 *
 * @param targets 영향 대상. 깊이, 개체 유형, 개체 식별자 순이다 (D2 AD-61)
 * @param countsByType 개체 유형별 수. 유형 이름의 글자 코드 순이다
 * @param countsByDepth 깊이별 수. 깊이 순이다
 * @param truncated 최대 깊이에서 멈췄고 그 너머에 아직 찾지 못한 대상이 있는가 (D2 AD-62, AD-117)
 */
public record ImpactScope(List<ImpactTarget> targets, Map<String, Integer> countsByType,
        Map<Integer, Integer> countsByDepth, boolean truncated) {

    public ImpactScope {
        targets = List.copyOf(targets);
        // 넘겨받은 차례를 그대로 지킨다.
        countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(countsByType));
        countsByDepth = Collections.unmodifiableMap(new LinkedHashMap<>(countsByDepth));
    }
}
