package com.altumlab.tabnit.rules;

import java.util.List;

/**
 * 나눠 준 조치의 한 쪽 (D2 AD-347).
 *
 * @param actions 이 쪽의 조치. 순서는 나누기 전의 순서 그대로다 (D2 AD-243)
 * @param total 나누기 전의 전체 수. 거른 조치의 수다
 */
public record ActionPage(List<Action> actions, int total) {

    public ActionPage {
        actions = List.copyOf(actions);
    }
}
