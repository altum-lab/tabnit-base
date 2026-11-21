package com.altumlab.tabnit.rules;

/**
 * 평가에 실패한 규칙 하나와 그 까닭 (D8 7절). 문구가 아니라 코드와 값이다.
 * 실패한 규칙은 이 변동에서 해당하는 것을 하나도 내지 않는다 (D2 AD-221).
 *
 * @param ruleNo 규칙 번호
 * @param code 까닭의 코드. `VALUE_FORMAT_MISMATCH` (저장된 값이 속성의 형식과 맞지 않는다),
 *        `UNKNOWN_ATTRIBUTE` (규칙이 보는 속성이 Pack 의 정의에 없다), `RULE_VALUE_FORMAT_MISMATCH` (규칙에 적힌 값이
 *        속성의 형식과 맞지 않는다), `INVALID_RULE` (규칙의 정의가 D8 의 모양이 아니다), `DUE_DATE_OUT_OF_RANGE`
 *        (조치의 기한을 날짜로 담을 수 없다. 조치를 만들 때 나온다). 이 가운데 `RULE_VALUE_FORMAT_MISMATCH`,
 *        `INVALID_RULE`, `UNKNOWN_ATTRIBUTE` 는 Pack 을 읽을 때 걸러지므로 검증을 거친 정의에서는 생기지 않는다.
 *        `INPUT_SOURCE_UNKNOWN` (규칙이 입력 자료를 가리는데 변동이 어느 입력 자료에서 나왔는지 모른다),
 *        `CHANGED_ITEMS_UNKNOWN` (규칙이 바뀐 것을 가리는데 변동에 바뀐 것이 남아 있지 않다)
 * @param entityId 걸린 개체. 없으면 null
 * @param attributeName 걸린 속성
 * @param value 걸린 값. 없으면 null
 */
public record RuleFailure(String ruleNo, String code, String entityId, String attributeName, String value) {
}
