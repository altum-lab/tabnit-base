package com.altumlab.tabnit.common;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 감사 기록에 남길 한 줄.
 *
 * <p>비밀번호를 담지 않는다. 개인을 가리키는 값은 필요한 만큼만 담는다 (누가 했는지의 아이디).
 *
 * @param occurredAt 언제. 밖에서 받는다
 * @param actor 누가. 사용자의 아이디. 모르면 null
 * @param action 무엇을 했는가. 기능의 이름
 * @param target 무엇에 대해. 없으면 null
 * @param asOf 조회에 쓴 기준 날짜. 없으면 null
 * @param outcome 결과
 * @param httpStatus 응답의 HTTP 상태. 없으면 null
 * @param detail 그 밖의 내용. 없으면 null
 * @param startedId 이 줄이 결과일 때, 하기 전에 남긴 줄의 식별자. 없으면 null
 */
public record AuditEntry(Instant occurredAt, String actor, String action, String target, LocalDate asOf,
        AuditOutcome outcome, Integer httpStatus, String detail, Long startedId) {

    public AuditEntry {
        Objects.requireNonNull(occurredAt, "언제인지는 반드시 있어야 합니다");
        Objects.requireNonNull(action, "무엇을 했는지는 반드시 있어야 합니다");
        Objects.requireNonNull(outcome, "결과는 반드시 있어야 합니다");
        if (action.isBlank()) {
            throw new IllegalArgumentException("무엇을 했는지가 비어 있습니다. 기능의 이름을 넣으세요.");
        }
    }
}
