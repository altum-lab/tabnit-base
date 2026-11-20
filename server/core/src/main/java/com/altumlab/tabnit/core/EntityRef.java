package com.altumlab.tabnit.core;

/**
 * 개체를 가리키는 값. 개체의 식별자는 Pack, 개체 유형, 입력의 키에서 정해진다 (D2 AD-37).
 *
 * @param packId 어느 Pack 의 개체인가
 * @param entityType 개체 유형. 온톨로지가 정한 이름
 * @param entityKey 입력의 키
 */
public record EntityRef(String packId, String entityType, String entityKey) {

    public EntityRef {
        Identifiers.requireNotBlank(packId, "Pack 식별자");
        Identifiers.requireNotBlank(entityType, "개체 유형");
        Identifiers.requireNotBlank(entityKey, "개체의 키");
    }

    /** 개체의 식별자. 같은 값에서는 언제나 같다. */
    public String id() {
        return Identifiers.join(packId, entityType, entityKey);
    }
}
