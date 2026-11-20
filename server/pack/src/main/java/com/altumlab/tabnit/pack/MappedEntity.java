package com.altumlab.tabnit.pack;

import java.util.List;

/**
 * 행이 만드는 개체 하나 (D6 7절).
 *
 * @param entityType 개체 유형의 이름
 * @param keyColumn 입력의 키를 읽을 열
 * @param attributes 이 개체의 속성 값. 속성 이름의 글자 코드 순
 */
public record MappedEntity(String entityType, String keyColumn, List<MappedAttribute> attributes) {

    public MappedEntity {
        attributes = List.copyOf(attributes);
    }
}
