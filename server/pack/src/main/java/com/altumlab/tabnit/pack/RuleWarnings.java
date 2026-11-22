package com.altumlab.tabnit.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 규칙에서 찾는 경고 (D8 2절, D2 AD-323).
 *
 * <p>한 행이 개체를 여럿 만들면 그 모두가 변동 대상이 된다 (D2 AD-59). 영향 대상에 이르는 근거 경로는 그 가운데 하나에서만
 * 시작하고, 조건이 보는 변동 대상은 그 하나다 (D2 AD-64). 규칙이 변동 대상의 속성을 보면서 그 유형을 정하지 않으면,
 * 경로가 그 속성이 없는 유형의 변동 대상에서 시작했을 때 값이 없어 조용히 걸리지 않는다.
 */
final class RuleWarnings {

    private RuleWarnings() {
    }

    /** 경고를 찾는다. 규칙 번호의 글자 코드 순이다. 넘겨받는 규칙은 규칙 번호 순이다. */
    static List<PackWarning> find(List<MappingDefinition> mappings, List<RuleDefinition> rules) {
        // 개체를 여럿 만드는 입력 자료와 그 행이 만드는 개체의 유형. 글자 코드 순.
        TreeMap<String, TreeSet<String>> manyEntities = new TreeMap<>(PackOrder.STRINGS);
        for (MappingDefinition mapping : mappings) {
            // (유형, 키 열)이 다르면 다른 개체다.
            TreeSet<String> made = new TreeSet<>(PackOrder.STRINGS);
            TreeSet<String> types = new TreeSet<>(PackOrder.STRINGS);
            add(made, types, mapping.primaryEntity().entityType(), mapping.primaryEntity().keyColumn());
            mapping.entities().forEach(entity -> add(made, types, entity.entityType(), entity.keyColumn()));
            for (MappedRelation relation : mapping.relations()) {
                add(made, types, relation.source().entityType(), relation.source().keyColumn());
                add(made, types, relation.target().entityType(), relation.target().keyColumn());
            }
            // 유형이 같은 개체만 여럿 만드는 행은 넣지 않는다: 모두 그 속성을 가진 유형이고,
            // 유형 조건으로 가를 수도 없다 (D2 AD-343).
            if (made.size() > 1 && types.size() > 1) {
                manyEntities.put(mapping.inputSourceId(), types);
            }
        }
        List<PackWarning> warnings = new ArrayList<>();
        for (RuleDefinition rule : rules) {
            if (rule.appliesTo() != RuleSubject.IMPACT_TARGET) {
                // 대상이 변동 대상 자신이면 조건이 보는 변동 대상도 그 개체다. 대상 유형이 이미 가린다.
                continue;
            }
            boolean typed = rule.conditions().stream()
                .anyMatch(condition -> condition.subject() == RuleSubject.CHANGE_TARGET && condition.typeIs() != null);
            if (typed) {
                continue;
            }
            // 이 규칙이 읽는 변동 대상의 속성: 변동 대상을 보는 조건의 속성과, 견주는 상대가 변동 대상인 조건의 속성.
            TreeSet<String> read = new TreeSet<>(PackOrder.STRINGS);
            for (RuleCondition condition : rule.conditions()) {
                if (condition.subject() == RuleSubject.CHANGE_TARGET && condition.attributeName() != null) {
                    read.add(condition.attributeName());
                }
                if (condition.compareSubject() == RuleSubject.CHANGE_TARGET && condition.compareAttributeName() != null) {
                    read.add(condition.compareAttributeName());
                }
            }
            if (read.isEmpty()) {
                continue;
            }
            // 이 규칙이 걸리는 입력 자료 가운데 개체를 여럿 만드는 것. 입력 자료를 적지 않은 규칙은 모두에 걸린다.
            List<String> sources = new ArrayList<>();
            for (String source : manyEntities.keySet()) {
                if (rule.inputSources().isEmpty() || rule.inputSources().contains(source)) {
                    sources.add(source + " (" + String.join(", ", manyEntities.get(source)) + ")");
                }
            }
            if (sources.isEmpty()) {
                continue;
            }
            warnings.add(new PackWarning(RuleReader.FOLDER, rule.ruleNo(), "규칙 " + rule.ruleNo() + " 은(는) 변동 대상의 속성 "
                    + String.join(", ", read) + " 을(를) 보는데 변동 대상의 유형을 정하지 않았습니다. 이 규칙이 걸리는 입력 자료 "
                    + String.join("; ", sources) + " 의 행은 개체를 여럿 만들고 그 모두가 변동 대상입니다. 영향 대상에 이르는 "
                    + "근거 경로가 그 속성이 없는 변동 대상에서 시작하면 값이 없어 조치가 만들어지지 않습니다. "
                    + "조건에 변동 대상의 유형을 더하세요 (tabnit:subject tabnit:ChangeTarget ; tabnit:typeIs 유형)."));
        }
        return List.copyOf(warnings);
    }

    private static void add(TreeSet<String> made, TreeSet<String> types, String entityType, String keyColumn) {
        made.add(entityType + "\u0000" + keyColumn);
        types.add(entityType);
    }
}
