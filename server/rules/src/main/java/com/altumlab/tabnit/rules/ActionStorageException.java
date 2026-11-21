package com.altumlab.tabnit.rules;

/** 영향 범위와 조치를 저장하거나 읽지 못했다. 저장은 전부 되거나 전부 안 된다. */
public class ActionStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ActionStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
