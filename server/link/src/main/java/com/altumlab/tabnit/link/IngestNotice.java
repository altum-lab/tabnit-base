package com.altumlab.tabnit.link;

/**
 * 투입의 알림 하나. 문구가 아니라 이유의 코드와 값으로 남긴다 (D5 5절 edition_notice, D2 AD-153).
 *
 * @param kind 종류. `SKIPPED_ROW` (건너뛴 행), `REFERENCE_ONLY_ENTITY` (참조로만 만들어진 개체: 관계의 끝으로만 가리켰고
 *        속성 값이 하나도 없다. 코드 `NO_ATTRIBUTE_VALUES`, D2 AD-378), `ATTRIBUTE_CONFLICT` (서로 다른 값으로 부딪힌 속성. 저장소에 이미 있는
 *        값과 기간이 겹쳐 넣지 못한 값도 여기에 든다: 코드 `OVERLAPS_STORED_VALUE`, D2 AD-344),
 *        `RELATION_CONFLICT` (서로 겹치는 기간으로 부딪힌 관계), `PERIOD_ADJUSTED` (적힌 유효기간과 다르게 처리한 것)
 * @param rowNo 걸린 행의 번호
 * @param code 이유의 코드
 * @param subject 걸린 열의 이름이나 개체·속성의 식별자. 없으면 null
 * @param detail 걸린 값. 없으면 null
 */
public record IngestNotice(String kind, int rowNo, String code, String subject, String detail) {
}
