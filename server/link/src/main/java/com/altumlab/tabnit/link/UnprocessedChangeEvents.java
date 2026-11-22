package com.altumlab.tabnit.link;

import java.util.List;
import java.util.Map;

/**
 * 한 시점에 함께 읽은, Pack 의 처리되지 않은 변동 이벤트와 그 실패의 까닭 (D2 AD-345).
 *
 * @param events 처리되지 않은 변동 이벤트 (대기, 실패). 처리하는 차례다 (D2 AD-207)
 * @param failureDetails 실패의 까닭이 남아 있는 것과 그 까닭. 변동 이벤트의 식별자 순이고, 까닭은 넘겨받은 차례다
 */
public record UnprocessedChangeEvents(List<ChangeEvent> events, Map<String, List<ChangeFailureDetail>> failureDetails) {

    public UnprocessedChangeEvents {
        events = List.copyOf(events);
    }

    /** 그 변동 이벤트의 실패의 까닭. 없으면 빈 목록이다. */
    public List<ChangeFailureDetail> failuresOf(String changeEventId) {
        return failureDetails.getOrDefault(changeEventId, List.of());
    }
}
