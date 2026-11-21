package com.altumlab.tabnit.link;

/**
 * 변동 이벤트의 상태를 표시하지 못했다. 그런 변동 이벤트가 없거나, 지금 상태에서는 그렇게 바꿀 수 없다 (D2 AD-206).
 */
public class ChangeEventRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ChangeEventRejectedException(String message) {
        super(message);
    }
}
