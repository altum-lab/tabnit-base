package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 규칙의 조건 하나 (D8 3절). 유형을 보는 조건이거나 속성 값을 보는 조건이다.
 *
 * @param subject 무엇을 보는가: 변동 대상인가 영향 대상인가
 * @param typeIs 유형을 보는 조건이면 그 개체 유형의 이름. 하위 유형도 해당한다. 속성을 보는 조건이면 null
 * @param attributeName 속성을 보는 조건이면 그 속성의 이름. 유형을 보는 조건이면 null
 * @param operator 속성을 보는 조건의 비교. 유형을 보는 조건이면 null
 * @param values 견줄 값. 속성의 형식에 맞는 정해진 표기다 (수는 값이 같으면 같은 표기, 날짜는 2026-03-01 의 꼴).
 *        겹치지 않게 글자 코드 순으로 둔다. 값을 적지 않는 비교와 유형을 보는 조건, 고정 값이 아닌 것과 견주는 조건이면 비어 있다
 * @param compareTo 속성 값을 무엇과 견주는가 (D2 AD-292). 유형을 보는 조건과 값을 적지 않는 비교는 VALUE 다
 * @param compareSubject 다른 대상의 속성과 견줄 때, 그 대상. 아니면 null
 * @param compareAttributeName 다른 대상의 속성과 견줄 때, 그 속성의 이름. 아니면 null
 * @param linkedBy 이어진 대상을 보는 조건(subject 가 LINKED_TARGET)이면, 규칙의 대상과 그 대상을 잇는 관계 유형의 이름.
 *        아니면 null (D2 AD-338)
 */
public record RuleCondition(RuleSubject subject, String typeIs, String attributeName, RuleOperator operator,
        List<String> values, RuleCompareTo compareTo, RuleSubject compareSubject, String compareAttributeName,
        String linkedBy) {

    public RuleCondition {
        values = List.copyOf(values);
        compareTo = compareTo == null ? RuleCompareTo.VALUE : compareTo;
    }

    /** 변동 대상이나 영향 대상을 보는 조건. 이어진 대상을 보지 않는다. */
    public RuleCondition(RuleSubject subject, String typeIs, String attributeName, RuleOperator operator, List<String> values,
            RuleCompareTo compareTo, RuleSubject compareSubject, String compareAttributeName) {
        this(subject, typeIs, attributeName, operator, values, compareTo, compareSubject, compareAttributeName, null);
    }

    /** 유형을 보는 조건이나, 속성 값을 규칙에 적은 고정 값과 견주는 조건. */
    public RuleCondition(RuleSubject subject, String typeIs, String attributeName, RuleOperator operator,
            List<String> values) {
        this(subject, typeIs, attributeName, operator, values, RuleCompareTo.VALUE, null, null, null);
    }
}
