package com.altumlab.tabnit.rules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 저장된 영향 범위에서 영향 대상을 나눠 준 한 쪽 (D2 AD-347).
 *
 * <p>수는 모두 나누기 전의, 거르지 않은 영향 범위 전체의 것이다. 이 쪽의 영향 대상에서 다시 세면 틀린다:
 * {@code scope} 의 유형별·깊이별 수가 아니라 여기의 것을 쓴다.
 *
 * @param scope 영향 범위의 머리와 이 쪽의 영향 대상. 순서는 나누기 전의 순서 그대로다 (D2 AD-61)
 * @param total 영향 대상의 전체 수
 * @param countsByType 개체 유형별 영향 대상의 수. 유형의 이름의 글자 코드 순이다
 * @param countsByDepth 깊이별 영향 대상의 수. 깊이 순이다
 */
public record StoredImpactScopePage(StoredImpactScope scope, int total, Map<String, Integer> countsByType,
        Map<Integer, Integer> countsByDepth) {

    public StoredImpactScopePage {
        countsByType = Collections.unmodifiableMap(new LinkedHashMap<>(countsByType));
        countsByDepth = Collections.unmodifiableMap(new LinkedHashMap<>(countsByDepth));
    }
}
