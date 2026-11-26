package com.altumlab.tabnit.link;

import java.util.List;

/**
 * 나눠 준 변동 이벤트의 한 쪽 (D2 AD-417).
 *
 * @param events 이 쪽의 변동 이벤트. 순서는 나누기 전의 순서 그대로다
 * @param total 나누기 전의 전체 수
 */
public record ChangeEventPage(List<ChangeEventSummary> events, int total) {

    public ChangeEventPage {
        events = List.copyOf(events);
    }
}
