package com.altumlab.tabnit.api;

import com.altumlab.tabnit.rules.ActionDecision;
import com.altumlab.tabnit.rules.ActionStatus;

/**
 * 지금의 상태에서는 그렇게 바꿀 수 없다 (D7 7절). 409 로 지금의 상태와 마지막 이력을 함께 알린다.
 */
class ActionDecisionConflictException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ActionStatus currentStatus;

	private final transient ActionDecision lastDecision;

	ActionDecisionConflictException(ActionStatus currentStatus, ActionDecision lastDecision, String message) {
		super(message);
		this.currentStatus = currentStatus;
		this.lastDecision = lastDecision;
	}

	ActionStatus currentStatus() {
		return this.currentStatus;
	}

	/** 그 조치의 마지막 이력. 없으면 null 이다. */
	ActionDecision lastDecision() {
		return this.lastDecision;
	}
}
