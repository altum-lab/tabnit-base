package com.altumlab.tabnit.link;

/**
 * 변동 이벤트가 실패한 까닭 한 줄: 평가에 실패한 규칙 하나 (D5 5절 change_event_failure, D2 AD-321, AD-345).
 * 뒤 단계(api)가 넘긴 것을 그대로 담는다. 이 모듈은 규칙을 실행하지 않고 코드의 뜻을 알지 않는다.
 *
 * @param ruleNo 규칙 번호. 비어 있을 수 없다
 * @param code 까닭의 코드. 문구가 아니다. 비어 있을 수 없다
 * @param entityId 걸린 개체. 없으면 null
 * @param attributeName 걸린 속성. 없으면 null
 * @param value 걸린 값. 없으면 null
 */
public record ChangeFailureDetail(String ruleNo, String code, String entityId, String attributeName, String value) {
}
