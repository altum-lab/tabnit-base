package com.altumlab.tabnit.pack;

/**
 * 관계 유형의 정의 (D6 5절).
 *
 * @param name 이름
 * @param label 화면에 보일 이름
 * @param sourceType 출발 개체의 유형. 그 하위 유형도 맞는 것으로 본다
 * @param targetType 도착 개체의 유형. 그 하위 유형도 맞는 것으로 본다
 * @param impactDirection 영향이 퍼지는 방향
 */
public record RelationTypeDefinition(String name, String label, String sourceType, String targetType,
        PackImpactDirection impactDirection) {
}
