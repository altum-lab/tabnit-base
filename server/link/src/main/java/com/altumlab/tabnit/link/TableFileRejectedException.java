package com.altumlab.tabnit.link;

/**
 * 파일 수준의 문제로 파일 전체를 거부했다 (D6 7절 "입력 파일").
 *
 * <p>이유의 코드와 함께, 무엇이 왜 틀렸고 무엇을 하면 되는지를 문구에 담는다.
 */
public class TableFileRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public TableFileRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 이유의 코드. 예: `NOT_UTF8`, `NO_HEADER`, `BLANK_COLUMN_NAME`, `DUPLICATE_COLUMN_NAME`, `UNCLOSED_QUOTE`. */
    public String code() {
        return code;
    }
}
