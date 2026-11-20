package com.altumlab.tabnit.core;

import java.util.Objects;

/**
 * 저장할 속성 값 (D5 3절).
 *
 * @param entity 어느 개체의 값인가
 * @param attributeName 속성. 온톨로지가 정한 이름
 * @param value 값. 속성의 형식에 맞는 정해진 표기의 글자다. 이 모듈은 받은 글자를 그대로 저장한다 (D2 AD-71).
 *        빈 글자일 수 없다. 값이 없는 것은 행으로 저장하지 않는다 (D2 AD-55)
 * @param period 유효기간. 시작일 포함, 종료일 제외
 * @param provenance 출처
 */
public record NewAttributeValue(EntityRef entity, String attributeName, String value, ValidityPeriod period,
        Provenance provenance) {

    public NewAttributeValue {
        Objects.requireNonNull(entity, "개체는 반드시 있어야 합니다");
        Identifiers.requireNotBlank(attributeName, "속성의 이름");
        Identifiers.requireNotBlank(value, "속성 값");
        Objects.requireNonNull(period, "유효기간은 반드시 있어야 합니다");
        Objects.requireNonNull(provenance, "출처는 반드시 있어야 합니다");
    }

    /** 속성 값의 식별자. 개체, 속성의 이름, 시작일에서 정해진다 (D5 1절). */
    public String id() {
        return Identifiers.join(entity.packId(), entity.entityType(), entity.entityKey(), attributeName,
                period.from().toString());
    }
}
