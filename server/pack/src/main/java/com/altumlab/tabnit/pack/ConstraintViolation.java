package com.altumlab.tabnit.pack;

/**
 * 제약의 위반 하나 ({@link ConstraintChecker}, D6 6절).
 *
 * @param severity 심각도. 제약에 적지 않았으면 위반이다 (D2 AD-395)
 * @param entityId 제약을 어긴 개체의 식별자. 부르는 쪽이 넘긴 그대로다. 제약의 대상이 개체가 아니면 (값을 대상으로 삼은
 *        제약) null
 * @param pathKind 어긴 곳이 속성인가, 관계인가, 경로의 식인가, 개체 자신인가
 * @param pathName 속성이나 관계 유형의 이름. 경로의 식이면 그 식을 적은 글자, 개체 자신이면 null
 * @param value 어긴 값의 글자. 값 하나에 걸린 제약이 아니거나 (개수 따위) 값이 개체이면 null
 * @param valueEntityId 어긴 값이 개체일 때 그 개체의 식별자. 아니면 null
 * @param constraintKind 제약의 종류. SHACL 의 제약 구성요소의 이름에서 뒤의 "ConstraintComponent" 를 뗀 것이다
 *        (MinCount, Datatype, Class …)
 * @param constraintName 그 제약이 딸린, 이름 있는 제약의 이름. 없으면 null
 * @param message 알리는 문구. 제약에 sh:message 를 적었으면 그것이고, 아니면 제품이 만든 한국어 문구다 (D2 AD-392)
 */
public record ConstraintViolation(Severity severity, String entityId, PathKind pathKind, String pathName, String value,
        String valueEntityId, String constraintKind, String constraintName, String message) {

    /** 심각도. 심각한 것부터다. */
    public enum Severity {
        VIOLATION, WARNING, INFO
    }

    /** 어긴 곳의 종류. */
    public enum PathKind {
        /** 속성. */
        ATTRIBUTE,
        /** 관계 유형. */
        RELATION,
        /** 경로의 식 (거꾸로 따라가기, 이어 따라가기 따위). */
        EXPRESSION,
        /** 경로가 없다: 개체 자신에 걸린 제약이다. */
        NONE
    }
}
