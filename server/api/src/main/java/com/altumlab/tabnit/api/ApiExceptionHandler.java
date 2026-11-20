package com.altumlab.tabnit.api;

import com.altumlab.tabnit.core.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 오류를 공통 오류 형식으로 낸다: RFC 9457 에 code 와 field 를 더한 것 (D2 AD-79, D7 2절).
 *
 * <p>내부 예외의 문구나 스택은 응답에 넣지 않는다. 서버 쪽의 문제는 기록에 남기고, 응답에는 정해진 문구만 담는다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiProblemException.class)
	ProblemDetail handle(ApiProblemException e) {
		return problem(e.status(), title(e.status()), e.code(), e.field(), e.getMessage());
	}

	@ExceptionHandler(EntityNotFoundException.class)
	ProblemDetail handle(EntityNotFoundException e) {
		// core 의 이 문구는 사용자에게 보여도 되는 한국어 문구다: 어느 개체인지와 무엇을 확인할지를 담는다.
		return problem(HttpStatus.NOT_FOUND, title(HttpStatus.NOT_FOUND), "ENTITY_NOT_FOUND", null, e.getMessage());
	}

	/** 그 밖의 모든 것은 서버 쪽의 문제다. 원인은 기록에 남기고 응답에는 담지 않는다. */
	@ExceptionHandler(Exception.class)
	ProblemDetail handle(Exception e) {
		log.error("요청을 처리하지 못했습니다", e);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, title(HttpStatus.INTERNAL_SERVER_ERROR), "INTERNAL", null,
				"요청을 처리하지 못했습니다. 잠시 뒤 다시 시도하고, 계속되면 관리자에게 알려 주세요.");
	}

	private static ProblemDetail problem(HttpStatus status, String title, String code, String field, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		problem.setProperty("code", code);
		if (field != null) {
			problem.setProperty("field", field);
		}
		return problem;
	}

	private static String title(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "잘못된 요청";
			case NOT_FOUND -> "대상을 찾을 수 없음";
			default -> "서버 오류";
		};
	}

}
