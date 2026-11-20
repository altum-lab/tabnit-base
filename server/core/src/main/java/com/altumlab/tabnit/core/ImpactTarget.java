package com.altumlab.tabnit.core;

/**
 * 영향 대상 하나 (D3 7절).
 *
 * @param entityId 개체의 식별자
 * @param entityType 개체 유형
 * @param depth 깊이. 가장 가까운 출발 개체에서 건넌 관계의 수다
 * @param path 근거 경로. 반드시 있다
 */
public record ImpactTarget(String entityId, String entityType, int depth, ImpactPath path) {
}
