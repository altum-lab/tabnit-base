package com.altumlab.tabnit.pack;

/**
 * 행이 만드는 관계 하나 (D6 7절). 끝에 적힌 개체는 없으면 만든다 (D2 AD-59).
 *
 * @param relationType 관계 유형의 이름
 * @param source 출발 개체
 * @param target 도착 개체
 * @param validFromColumn 유효기간의 시작일을 읽을 열. 적지 않았으면 null 이고 시작일은 판의 적용일이다
 * @param validToColumn 유효기간의 종료일을 읽을 열. 적지 않았으면 null
 */
public record MappedRelation(String relationType, MappedEntityRef source, MappedEntityRef target,
        String validFromColumn, String validToColumn) {
}
