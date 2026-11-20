package com.altumlab.tabnit.pack;

/**
 * 매핑이 개체를 가리키는 법: 개체 유형과, 입력의 키가 되는 열 (D6 7절).
 *
 * @param entityType 개체 유형의 이름
 * @param keyColumn 입력의 키를 읽을 열
 */
public record MappedEntityRef(String entityType, String keyColumn) {
}
