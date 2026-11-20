package com.altumlab.tabnit.core;

import java.time.Instant;

/**
 * 저장된 관계.
 *
 * @param id 관계의 식별자
 * @param packId 어느 Pack 의 관계인가
 * @param sourceEntityId 출발 개체의 식별자
 * @param targetEntityId 도착 개체의 식별자
 * @param relationType 관계 유형
 * @param period 유효기간
 * @param provenance 출처
 * @param recordedAt 기록한 때. 저장할 때 밖에서 받은 시각이다
 */
public record Relation(String id, String packId, String sourceEntityId, String targetEntityId, String relationType,
        ValidityPeriod period, Provenance provenance, Instant recordedAt) {
}
