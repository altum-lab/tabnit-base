package com.altumlab.tabnit.rules;

/**
 * 지금의 상태에서는 그렇게 바꿀 수 없다 (D2 AD-47, AD-248). 아무것도 바뀌지 않았고 이력도 늘지 않았다.
 *
 * <p>확정된 조치는 다시 확정하거나 보류할 수 없고, 보류된 조치는 다시 보류할 수 없다.
 */
public class ActionDecisionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ActionStatus currentStatus;

    private final ActionStatus requestedStatus;

    public ActionDecisionRejectedException(ActionStatus currentStatus, ActionStatus requestedStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
    }

    /** 조치의 지금 상태. */
    public ActionStatus currentStatus() {
        return currentStatus;
    }

    /** 바꾸려던 상태. */
    public ActionStatus requestedStatus() {
        return requestedStatus;
    }
}
