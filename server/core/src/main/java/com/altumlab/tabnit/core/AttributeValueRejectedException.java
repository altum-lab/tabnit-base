package com.altumlab.tabnit.core;

/** 속성 값의 저장이나 끝내기를 거부했다. 문구에 무엇이 왜 틀렸는지와 무엇을 하면 되는지를 담는다. */
public class AttributeValueRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AttributeValueRejectedException(String message) {
        super(message);
    }

    public AttributeValueRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
