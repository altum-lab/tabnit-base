package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 규칙 하나 (D8 2절). 규칙을 실행하는 것은 rules 모듈이다. 여기에는 읽어서 검증한 정의만 있다.
 *
 * <p>Pack 의 정의에 담긴 규칙의 목록은 규칙 번호 순이다. 평가하는 차례(우선순위, 그다음 규칙 번호. D2 AD-103)가 아니다.
 * 평가하는 쪽이 그 차례로 다시 늘어놓는다.
 *
 * @param ruleNo 규칙 번호. 글자다. Pack 안에서 겹치지 않고 자릿수가 모두 같다
 * @param label 화면에 보일 규칙의 이름
 * @param priority 우선순위. 1 이상. 수가 작을수록 먼저다
 * @param changeKinds 어떤 종류의 변동에 걸리는가. 하나 이상. 규칙에 적지 않았으면 모든 종류다
 * @param appliesTo 조치의 대상이 영향 대상인가 변동 대상인가
 * @param targetType 조치를 만들 대상의 개체 유형. 하위 유형도 해당한다
 * @param conditions 조건. 모두 만족해야 한다. 정해진 차례다 (대상, 유형, 속성, 비교, 값). 없을 수 있다
 * @param actionText 조치에 담을 해야 할 일의 문구
 * @param dueInDays 기한까지의 일수. 0 이상
 * @param inputSources 어느 입력 자료에서 나온 변동에 걸리는가. 글자 코드 순. 비어 있으면 모든 입력 자료다 (D2 AD-290)
 * @param changedAttributes 그 행에서 이 속성 가운데 하나가 바뀌었을 때 걸린다. 글자 코드 순 (D2 AD-291)
 * @param changedRelationTypes 그 행에서 이 관계 유형 가운데 하나가 바뀌었을 때 걸린다. 글자 코드 순.
 *        속성과 관계 유형이 모두 비어 있으면 무엇이 바뀌든 걸린다. 하나라도 적었으면 적은 것 가운데 하나가 바뀌어야 한다
 */
public record RuleDefinition(String ruleNo, String label, int priority, List<RuleChangeKind> changeKinds,
        RuleSubject appliesTo, String targetType, List<RuleCondition> conditions, String actionText, int dueInDays,
        List<String> inputSources, List<String> changedAttributes, List<String> changedRelationTypes) {

    public RuleDefinition {
        changeKinds = List.copyOf(changeKinds);
        conditions = List.copyOf(conditions);
        inputSources = List.copyOf(inputSources);
        changedAttributes = List.copyOf(changedAttributes);
        changedRelationTypes = List.copyOf(changedRelationTypes);
    }

    /** 모든 입력 자료에, 무엇이 바뀌든 걸리는 규칙. */
    public RuleDefinition(String ruleNo, String label, int priority, List<RuleChangeKind> changeKinds, RuleSubject appliesTo,
            String targetType, List<RuleCondition> conditions, String actionText, int dueInDays) {
        this(ruleNo, label, priority, changeKinds, appliesTo, targetType, conditions, actionText, dueInDays, List.of(),
                List.of(), List.of());
    }

    /** 그 행에서 바뀐 것을 가리는 규칙인가. */
    public boolean limitsChangedItems() {
        return !changedAttributes.isEmpty() || !changedRelationTypes.isEmpty();
    }
}
