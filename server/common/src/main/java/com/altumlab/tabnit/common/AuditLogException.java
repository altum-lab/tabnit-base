package com.altumlab.tabnit.common;

/** 감사 기록을 남기거나 읽지 못했다. 남기지 못했으면 그 요청도 하지 않는다 (D2 AD-05). 원인은 그대로 담는다. */
public class AuditLogException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AuditLogException(String message, Throwable cause) {
        super(message, cause);
    }
}
