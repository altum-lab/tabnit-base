package com.altumlab.tabnit.core;

import java.util.Objects;

/**
 * 저장할 관계. 출발 개체에서 도착 개체로 향한다 (D2 AD-29).
 *
 * @param source 출발 개체
 * @param target 도착 개체
 * @param relationType 관계 유형. 온톨로지가 정한 이름
 * @param period 유효기간. 시작일 포함, 종료일 제외
 * @param provenance 출처
 */
public record NewRelation(EntityRef source, EntityRef target, String relationType, ValidityPeriod period,
        Provenance provenance) {

    public NewRelation {
        Objects.requireNonNull(source, "출발 개체는 반드시 있어야 합니다");
        Objects.requireNonNull(target, "도착 개체는 반드시 있어야 합니다");
        Identifiers.requireNotBlank(relationType, "관계 유형");
        Objects.requireNonNull(period, "유효기간은 반드시 있어야 합니다");
        Objects.requireNonNull(provenance, "출처는 반드시 있어야 합니다");
    }

    /** 관계의 식별자. 출발, 도착, 유형, 출처, 시작일에서 정해진다 (D5 1절). */
    public String id() {
        return Identifiers.join(source.packId(), source.entityType(), source.entityKey(), target.entityType(),
                target.entityKey(), relationType, provenance.inputSourceId(), String.valueOf(provenance.editionNo()),
                period.from().toString());
    }
}
