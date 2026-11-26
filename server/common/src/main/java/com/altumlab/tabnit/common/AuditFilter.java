package com.altumlab.tabnit.common;

import java.time.Instant;

/**
 * 감사 기록을 거르는 조건 (D2 AD-414). 없는 조건(null)은 거르지 않는다. 여러 조건을 주면 모두 맞는 줄이다.
 *
 * @param actor 누가. 아이디가 글자 그대로 같은 줄. 일부로 찾지 않는다
 * @param action 무엇을. 기능의 이름이 글자 그대로 같은 줄
 * @param outcome 결과
 * @param from 언제부터. 이 시각을 포함한다
 * @param to 언제까지. 이 시각은 제외한다
 */
public record AuditFilter(String actor, String action, AuditOutcome outcome, Instant from, Instant to) {

    /** 거르지 않는다. */
    public static final AuditFilter NONE = new AuditFilter(null, null, null, null, null);

    public AuditFilter {
        requireText(actor, "누가");
        requireText(action, "무엇을");
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("언제의 끝이 시작보다 앞섭니다. 시작: " + from + ", 끝: " + to + ".");
        }
    }

    /** 조건으로 준 글자는 비어 있을 수 없고 저장소가 담지 못하는 글자(NUL)를 담을 수 없다. 거르지 않으려면 null 을 준다. */
    private static void requireText(String value, String name) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + "의 조건이 비어 있습니다. 거르지 않으려면 조건을 주지 않습니다.");
        }
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException(name + "의 조건에 NUL 글자(\\u0000)가 들어 있습니다. 그 글자를 빼고 다시 보내세요.");
        }
    }
}
