package com.altumlab.tabnit.api;

import org.springframework.http.HttpStatus;

/**
 * 요청이 틀려서 공통 오류 형식으로 답해야 할 때 던진다 (D7 2절 오류).
 *
 * <p>문구는 사용자에게 그대로 보인다. 한국어로, 무엇을 하면 되는지까지 적는다.
 */
class ApiProblemException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatus status;

	private final String code;

	private final String field;

	ApiProblemException(HttpStatus status, String code, String field, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
		this.field = field;
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}

	/** 틀린 항목의 이름. 입력 오류가 아니면 null 이다. */
	String field() {
		return field;
	}

}
