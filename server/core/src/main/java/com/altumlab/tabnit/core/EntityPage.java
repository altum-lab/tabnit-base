package com.altumlab.tabnit.core;

import java.util.List;

/**
 * 나눠 준 개체의 한 쪽 (D2 AD-411).
 *
 * @param entities 이 쪽의 개체. 순서는 나누기 전의 순서 그대로다
 * @param total 나누기 전의 전체 수
 */
public record EntityPage(List<EntityRef> entities, int total) {

    public EntityPage {
        entities = List.copyOf(entities);
    }
}
