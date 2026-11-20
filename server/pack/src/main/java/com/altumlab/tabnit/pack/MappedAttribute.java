package com.altumlab.tabnit.pack;

/**
 * 매핑이 정한 속성 값 하나 (D6 7절). 없을 수 있는 항목은 적지 않았으면 null 이다.
 *
 * @param attributeName 속성의 이름
 * @param column 값을 읽을 열
 * @param validFromColumn 유효기간의 시작일을 읽을 열. 없으면 시작일은 판의 적용일이다
 * @param validToColumn 유효기간의 종료일을 읽을 열
 * @param datePattern 날짜 열의 모양. 적지 않았으면 null 이고 매핑의 기본 모양을 따른다
 * @param groupingSeparator 수 열의 자릿수 구분 기호
 * @param trueValue 참으로 볼 글자
 * @param falseValue 거짓으로 볼 글자
 */
public record MappedAttribute(String attributeName, String column, String validFromColumn, String validToColumn,
        String datePattern, String groupingSeparator, String trueValue, String falseValue) {
}
