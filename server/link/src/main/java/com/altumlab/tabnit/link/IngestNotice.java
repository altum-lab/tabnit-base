package com.altumlab.tabnit.link;

/**
 * 투입의 알림 하나. 문구가 아니라 이유의 코드와 값으로 남긴다 (D5 5절 edition_notice, D2 AD-153).
 *
 * @param kind 종류. `SKIPPED_ROW` (건너뛴 행), `ATTRIBUTE_CONFLICT` (서로 다른 값으로 부딪힌 속성),
 *        `RELATION_CONFLICT` (서로 겹치는 기간으로 부딪힌 관계), `PERIOD_ADJUSTED` (적힌 유효기간과 다르게 처리한 것)
 * @param rowNo 걸린 행의 번호
 * @param code 이유의 코드
 * @param subject 걸린 열의 이름이나 개체·속성의 식별자. 없으면 null
 * @param detail 걸린 값. 없으면 null
 */
public record IngestNotice(String kind, int rowNo, String code, String subject, String detail) {
}
