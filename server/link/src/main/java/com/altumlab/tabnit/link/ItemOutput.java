package com.altumlab.tabnit.link;

/**
 * 항목(행) 하나가 만든 것 하나 (D5 5절 edition_item_output, D2 AD-151).
 *
 * @param kind 종류
 * @param objectId 그 개체, 관계, 속성 값의 식별자
 */
public record ItemOutput(Kind kind, String objectId) {

    /** 만든 것의 종류. 적힌 차례가 결과의 차례다. */
    public enum Kind {
        ENTITY, RELATION, ATTRIBUTE_VALUE
    }
}
