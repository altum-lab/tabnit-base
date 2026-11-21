package com.altumlab.tabnit.rules;

import com.altumlab.tabnit.pack.RuleSubject;

/**
 * 해당하는 (규칙, 대상) 하나. 조치 하나가 될 것이다 (D8 4절).
 *
 * @param ruleNo 규칙 번호
 * @param priority 그 규칙의 우선순위
 * @param appliesTo 대상이 영향 대상인가 변동 대상인가
 * @param targetEntityId 대상 개체의 식별자
 * @param targetEntityType 대상 개체의 유형
 */
public record RuleMatch(String ruleNo, int priority, RuleSubject appliesTo, String targetEntityId,
        String targetEntityType) {
}
