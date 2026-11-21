package com.altumlab.tabnit.common;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 저장된 감사 기록 한 줄.
 *
 * @param id 식별자. 남긴 차례대로 커지는 일련번호다
 * @param occurredAt 언제
 * @param actor 누가. 모르면 null
 * @param action 무엇을 했는가
 * @param target 무엇에 대해. 없으면 null
 * @param asOf 조회에 쓴 기준 날짜. 없으면 null
 * @param outcome 결과
 * @param httpStatus 응답의 HTTP 상태. 없으면 null
 * @param detail 그 밖의 내용. 없으면 null
 * @param startedId 이 줄이 결과일 때, 하기 전에 남긴 줄의 식별자. 없으면 null
 */
public record AuditRecord(long id, Instant occurredAt, String actor, String action, String target, LocalDate asOf,
        AuditOutcome outcome, Integer httpStatus, String detail, Long startedId) {
}
