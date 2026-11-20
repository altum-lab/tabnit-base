package com.altumlab.tabnit.link;

/**
 * 매핑을 실행해 저장하다가 실패했다. 그 실행에서 넣은 것은 하나도 남지 않는다.
 *
 * <p>파일이 틀린 것({@link TableFileRejectedException})이나 행이 틀린 것(알림)이 아니라, 저장소가 받지 않은 것이다.
 */
public class MappingExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MappingExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
