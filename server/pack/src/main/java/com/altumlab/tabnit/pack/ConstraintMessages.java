package com.altumlab.tabnit.pack;

import java.util.Map;

/**
 * 제약의 위반을 알리는 한국어 문구 (D2 AD-392). 제약의 종류마다 만든다. 제약에 sh:message 를 적었으면 이것을 쓰지 않는다.
 *
 * <p>문구는 어긴 곳, 기대하는 것, 지금의 값을 적고, 무엇을 하면 되는지로 끝난다. 종류를 모르면 종류의 이름을 그대로 적는다.
 */
final class ConstraintMessages {

    private static final String FIX = " 자료를 고쳐 새 판으로 올리거나, 제약이 틀렸으면 Pack 의 제약을 고쳐 다시 적재하세요.";

    private static final Map<String, String> FORMATS = Map.of("string", "글자", "integer", "정수", "decimal", "소수", "date", "날짜",
            "boolean", "참·거짓");

    private ConstraintMessages() {
    }

    /**
     * @param kind 제약의 종류
     * @param where 어긴 곳 ("속성 '제목'", "관계 '잇는다'", "경로 '…'", "이 개체"). 조사는 바로 붙여 적는다
     * @param pathKind 어긴 곳의 종류
     * @param value 어긴 값의 글자. 없으면 null
     * @param valueIsEntity 어긴 값이 개체인가
     * @param parameters 제약에 적은 값들
     */
    static String of(String kind, String where, ConstraintViolation.PathKind pathKind, String value, boolean valueIsEntity,
            Map<String, String> parameters) {
        String now = valueIsEntity ? " 가리키는 개체를 확인하세요." : value == null ? "" : " 지금 값: " + value + ".";
        String body = switch (kind) {
            case "MinCount" -> where + "은(는) " + parameter(parameters, "minCount") + "개 이상 있어야 합니다.";
            case "MaxCount" -> where + "은(는) " + parameter(parameters, "maxCount") + "개까지만 있을 수 있습니다.";
            case "Datatype" -> where + "의 값이 " + format(parameter(parameters, "datatype")) + " 꼴이 아닙니다." + now;
            case "Class" -> where + "이(가) 가리키는 것이 '" + parameter(parameters, "class") + "' 유형이어야 합니다.";
            case "NodeKind" -> where + "의 값의 종류가 정해진 것과 다릅니다." + now;
            case "MinInclusive" -> where + "의 값은 " + parameter(parameters, "minInclusive") + " 이상이어야 합니다." + now;
            case "MaxInclusive" -> where + "의 값은 " + parameter(parameters, "maxInclusive") + " 이하여야 합니다." + now;
            case "MinExclusive" -> where + "의 값은 " + parameter(parameters, "minExclusive") + " 보다 커야 합니다." + now;
            case "MaxExclusive" -> where + "의 값은 " + parameter(parameters, "maxExclusive") + " 보다 작아야 합니다." + now;
            case "MinLength" -> where + "의 값은 " + parameter(parameters, "minLength") + "글자 이상이어야 합니다." + now;
            case "MaxLength" -> where + "의 값은 " + parameter(parameters, "maxLength") + "글자까지 적습니다." + now;
            case "Pattern" -> where + "의 값이 정해진 꼴 (" + parameter(parameters, "pattern") + ") 에 맞지 않습니다." + now;
            case "In" -> where + "의 값이 정해진 값 가운데 하나가 아닙니다." + now;
            case "HasValue" -> where + "에 반드시 있어야 하는 값 (" + parameter(parameters, "hasValue") + ") 이 없습니다.";
            case "LanguageIn", "UniqueLang" -> where + "의 값의 언어 표시가 정해진 것과 다릅니다." + now;
            case "Equals" -> where + "의 값이 " + parameter(parameters, "equals") + " 의 값과 같아야 합니다." + now;
            case "Disjoint" -> where + "의 값이 " + parameter(parameters, "disjoint") + " 의 값과 겹치면 안 됩니다." + now;
            case "LessThan" -> where + "의 값이 " + parameter(parameters, "lessThan") + " 의 값보다 작아야 합니다." + now;
            case "LessThanOrEquals" -> where + "의 값이 " + parameter(parameters, "lessThanOrEquals") + " 의 값보다 크면 안 됩니다."
                    + now;
            case "QualifiedMinCount" -> where + " 가운데 조건에 맞는 것이 " + parameter(parameters, "qualifiedMinCount")
                    + "개 이상 있어야 합니다.";
            case "QualifiedMaxCount" -> where + " 가운데 조건에 맞는 것이 " + parameter(parameters, "qualifiedMaxCount")
                    + "개까지만 있을 수 있습니다.";
            case "Closed" -> where + "에 정해지지 않은 속성이나 관계가 있습니다." + now;
            case "Node" -> where + "이(가) 함께 지켜야 하는 다른 제약을 어겼습니다." + now;
            case "Not" -> where + "이(가) 맞으면 안 되는 조건에 맞습니다." + now;
            case "And" -> where + "이(가) 모두 지켜야 하는 조건 가운데 어긴 것이 있습니다." + now;
            case "Or" -> where + "이(가) 적어도 하나는 지켜야 하는 조건을 하나도 지키지 않았습니다." + now;
            case "Xone" -> where + "이(가) 꼭 하나만 지켜야 하는 조건을 하나도 지키지 않았거나 둘 이상 지켰습니다." + now;
            default -> where + "이(가) 제약 (" + kind + ") 을 어겼습니다." + now;
        };
        return body + FIX;
    }

    private static String parameter(Map<String, String> parameters, String name) {
        return parameters.getOrDefault(name, "정해진 값");
    }

    private static String format(String datatype) {
        return FORMATS.getOrDefault(datatype, datatype);
    }
}
