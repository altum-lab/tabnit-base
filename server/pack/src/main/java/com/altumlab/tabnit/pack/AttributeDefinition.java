package com.altumlab.tabnit.pack;

/**
 * 속성의 정의 (D6 5절).
 *
 * @param name 이름
 * @param label 화면에 보일 이름
 * @param entityType 이 속성을 갖는 개체 유형. 그 하위 유형도 갖는다
 * @param valueFormat 값의 형식
 */
public record AttributeDefinition(String name, String label, String entityType, ValueFormat valueFormat) {
}
