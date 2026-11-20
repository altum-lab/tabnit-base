package com.altumlab.tabnit.pack;

/** 저장소에서 읽거나 쓰지 못했다. Pack 이 틀린 것이 아니라 저장소 쪽의 문제다. 원인은 그대로 담는다. */
public class PackStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PackStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
