package com.altumlab.tabnit.pack;

/**
 * 조건의 비교 (D8 3절). 낱말은 `tabnit:Equals` 처럼 적는다.
 * 선언한 차례가 조건의 정해진 차례에 쓰인다. 차례를 바꾸면 저장된 조건의 번호가 달라진다. 새 비교는 끝에 더한다.
 */
public enum RuleOperator {
    /** 같다. 값 하나. */
    EQUALS,
    /** 다르다. 값 하나. */
    NOT_EQUALS,
    /** 크다. 값 하나. 정수, 소수, 날짜에만. */
    GREATER_THAN,
    /** 크거나 같다. 값 하나. 정수, 소수, 날짜에만. */
    GREATER_THAN_OR_EQUAL,
    /** 작다. 값 하나. 정수, 소수, 날짜에만. */
    LESS_THAN,
    /** 작거나 같다. 값 하나. 정수, 소수, 날짜에만. */
    LESS_THAN_OR_EQUAL,
    /** 목록에 있다. 값 하나 이상. */
    IN,
    /** 값이 있다. 값을 적지 않는다. */
    HAS_VALUE,
    /** 값이 없다. 값을 적지 않는다. */
    HAS_NO_VALUE
}
