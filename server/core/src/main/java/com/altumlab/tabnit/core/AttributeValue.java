package com.altumlab.tabnit.core;

import java.time.Instant;

/**
 * 저장된 속성 값.
 *
 * @param id 속성 값의 식별자
 * @param packId 어느 Pack 의 값인가
 * @param entityId 어느 개체의 값인가
 * @param attributeName 속성의 이름
 * @param value 값. 저장한 글자 그대로다
 * @param period 유효기간
 * @param provenance 출처
 * @param recordedAt 기록한 때. 저장할 때 밖에서 받은 시각이다
 */
public record AttributeValue(String id, String packId, String entityId, String attributeName, String value,
        ValidityPeriod period, Provenance provenance, Instant recordedAt) {
}
