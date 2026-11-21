package com.altumlab.tabnit.rules;

import java.time.Instant;

/**
 * 조치의 이력 한 줄: 상태를 한 번 바꾼 것 (D5 6절 action_history).
 *
 * @param seq 그 조치 안에서의 차례. 1 부터
 * @param fromStatus 바뀌기 전의 상태
 * @param toStatus 바뀐 뒤의 상태
 * @param decidedBy 누가 했는가. 사용자의 아이디
 * @param decidedAt 언제 했는가. 밖에서 받은 시각
 * @param note 사유. 없으면 null. 보류에는 반드시 있다
 */
public record ActionDecision(int seq, ActionStatus fromStatus, ActionStatus toStatus, String decidedBy, Instant decidedAt,
        String note) {
}
