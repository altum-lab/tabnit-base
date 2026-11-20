package com.altumlab.tabnit.core;

/**
 * 저장소에서 읽거나 쓰지 못했다. 입력이 틀린 것이 아니라 저장소 쪽의 문제다.
 *
 * <p>데이터 접근 라이브러리의 예외를 밖으로 드러내지 않으려고 감싼다 (D2 AD-76). 원인은 그대로 담는다.
 */
public class CoreStorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CoreStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
