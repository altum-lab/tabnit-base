package com.altumlab.tabnit.core;

/** 관계의 저장을 거부했다. 문구에 무엇이 왜 틀렸는지와 무엇을 하면 되는지를 담는다. */
public class RelationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RelationRejectedException(String message) {
        super(message);
    }

    public RelationRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
