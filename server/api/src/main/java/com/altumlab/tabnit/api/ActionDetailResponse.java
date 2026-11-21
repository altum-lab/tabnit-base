package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.ActionDecision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 조치 하나와 그 이력의 응답 (docs/api/openapi.yaml 의 ActionDetail, D7 7절). 이력의 순서는 rules 가 준 그대로다.
 */
record ActionDetailResponse(ActionListResponse.Item action, List<Decision> history) {

	record Decision(int seq, String fromStatus, String toStatus, String decidedBy, Instant decidedAt, String note) {

		static Decision of(ActionDecision decision) {
			return new Decision(decision.seq(), decision.fromStatus().name(), decision.toStatus().name(),
					decision.decidedBy(), decision.decidedAt(), decision.note());
		}
	}

	static ActionDetailResponse of(ActionListResponse.Item action, List<ActionDecision> history) {
		List<Decision> decisions = new ArrayList<>();
		for (ActionDecision decision : history) {
			decisions.add(Decision.of(decision));
		}
		return new ActionDetailResponse(action, decisions);
	}
}
