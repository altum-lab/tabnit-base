package com.altumlab.tabnit.rules;

import java.util.List;

/**
 * 규칙을 적용한 결과 (보드 B-05).
 *
 * @param matches 해당하는 (규칙, 대상). 우선순위(작은 수가 먼저), 규칙 번호, 대상 개체의 식별자 순이다 (D2 AD-103, AD-222)
 * @param failures 평가에 실패한 규칙. 규칙 번호 순이다
 */
public record RuleSelection(List<RuleMatch> matches, List<RuleFailure> failures) {

    public RuleSelection {
        matches = List.copyOf(matches);
        failures = List.copyOf(failures);
    }
}
