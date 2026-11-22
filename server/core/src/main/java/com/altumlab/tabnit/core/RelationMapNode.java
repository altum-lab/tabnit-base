package com.altumlab.tabnit.core;

/**
 * 관계 지도의 노드 하나 (D2 AD-352).
 *
 * @param entityId 개체의 식별자
 * @param entityType 개체 유형
 * @param depth 가운데 개체에서 건넌 관계의 수 가운데 가장 작은 것. 가운데 개체는 0 이다
 */
public record RelationMapNode(String entityId, String entityType, int depth) {
}
