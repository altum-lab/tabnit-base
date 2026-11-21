package com.altumlab.tabnit.rules;

import java.util.List;

/**
 * 조치 생성의 결과.
 *
 * @param outcome 어떻게 끝났는가
 * @param actions 그 변동의 조치. 우선순위, 규칙 번호, 대상 개체의 식별자 순 (D2 AD-222). 실패했으면 비어 있다
 * @param failures 평가에 실패한 규칙. 규칙 번호 순. 실패했을 때만 있다
 */
public record ActionGeneration(Outcome outcome, List<Action> actions, List<RuleFailure> failures) {

    public ActionGeneration {
        actions = List.copyOf(actions);
        failures = List.copyOf(failures);
    }

    public enum Outcome {
        /** 영향 범위와 조치를 저장했다. */
        STORED,
        /** 이미 저장된 변동이다. 다시 계산하지 않았다. 저장되어 있던 조치를 돌려준다 (D2 AD-228). */
        ALREADY_STORED,
        /** 실패한 규칙이 있다. 아무것도 저장하지 않았다 (D2 AD-227). */
        RULE_FAILED
    }
}
