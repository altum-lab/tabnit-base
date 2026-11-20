package com.altumlab.tabnit.link;

/**
 * 파일 수준의 문제로 파일 전체를 거부했다 (D6 7절 "입력 파일").
 *
 * <p>이유의 코드와 함께, 무엇이 왜 틀렸고 무엇을 하면 되는지를 문구에 담는다.
 */
public class TableFileRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    private final String detail;

    public TableFileRejectedException(String code, String message) {
        this(code, null, message);
    }

    public TableFileRejectedException(String code, String detail, String message) {
        super(message);
        this.code = code;
        this.detail = detail;
    }

    /** 걸린 값. 예: 없는 열의 이름들. 없으면 null. 문구가 아니라 값이다 (투입 기록에 남긴다). */
    public String detail() {
        return detail;
    }

    /** 이유의 코드. 예: `NOT_UTF8`, `NO_HEADER`, `BLANK_COLUMN_NAME`, `DUPLICATE_COLUMN_NAME`, `UNCLOSED_QUOTE`. */
    public String code() {
        return code;
    }
}
