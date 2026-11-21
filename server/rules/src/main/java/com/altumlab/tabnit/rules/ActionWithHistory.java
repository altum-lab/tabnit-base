package com.altumlab.tabnit.rules;

import java.util.List;

/**
 * 조치 하나와 그 이력. 한 시점에 함께 읽은 것이다: 조치의 상태는 이력의 마지막 줄의 바뀐 뒤 상태와 같다
 * (이력이 없으면 대기다).
 *
 * @param history 상태를 바꾼 차례다. 아직 아무도 바꾸지 않았으면 비어 있다
 */
public record ActionWithHistory(Action action, List<ActionDecision> history) {
    public ActionWithHistory {
        history = List.copyOf(history);
    }
}
