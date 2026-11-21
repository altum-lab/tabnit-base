package com.altumlab.tabnit.api;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.altumlab.tabnit.core.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 오류를 공통 오류 형식으로 낸다: RFC 9457 에 code 와 field 를 더한 것 (D2 AD-79, D7 2절).
 *
 * <p>내부 예외의 문구나 스택은 응답에 넣지 않는다. 서버 쪽의 문제는 기록에 남기고, 응답에는 정해진 문구만 담는다.
 *
 * <p>프레임워크가 내는 오류(없는 주소, 허용하지 않는 방식 등)는 부모 클래스가 제 상태(404, 405 등)와
 * RFC 9457 형식으로 낸다. 이 오류들에는 아직 code 가 없다 (D7 2절 오류).
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final DataSize maxFileSize;

	ApiExceptionHandler(@Value("${spring.servlet.multipart.max-file-size:20MB}") DataSize maxFileSize) {
		this.maxFileSize = maxFileSize;
	}

	@ExceptionHandler(ApiProblemException.class)
	ProblemDetail handle(ApiProblemException e) {
		return problem(e.status(), title(e.status()), e.code(), e.field(), e.getMessage());
	}

	/** 올린 파일을 받아들이지 않았다. 거부한 이유의 코드와 값을 함께 담는다 (D7 5절). */
	@ExceptionHandler(EditionRejectedException.class)
	ProblemDetail handle(EditionRejectedException e) {
		HttpStatus status = e.tooLarge() ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.UNPROCESSABLE_CONTENT;
		ProblemDetail problem = problem(status, title(status), e.tooLarge() ? "FILE_TOO_LARGE" : "EDITION_REJECTED",
				e.tooLarge() ? "file" : null, e.getMessage());
		if (!e.tooLarge()) {
			problem.setProperty("reasonCode", e.reasonCode());
			problem.setProperty("reasonDetail", e.reasonDetail());
		}
		return problem;
	}

	/** 지금의 상태에서는 할 수 없는 확정이나 보류. 조치의 지금 상태와 마지막 이력을 함께 담는다 (D7 7절). */
	@ExceptionHandler(ActionDecisionConflictException.class)
	ProblemDetail handle(ActionDecisionConflictException e) {
		ProblemDetail problem = problem(HttpStatus.CONFLICT, title(HttpStatus.CONFLICT), "ACTION_DECISION_CONFLICT", null,
				e.getMessage());
		problem.setProperty("currentStatus", e.currentStatus().name());
		problem.setProperty("lastDecision",
				e.lastDecision() == null ? null : ActionDetailResponse.Decision.of(e.lastDecision()));
		return problem;
	}

	/** 본문을 읽지 못한 요청 (JSON 이 아니다, 비었다). 프레임워크의 형식에 code 를 더한다. 내부의 까닭은 담지 않는다. */
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, title(HttpStatus.BAD_REQUEST), "INVALID_BODY", null,
				"요청의 본문을 읽지 못했습니다. 항목을 담은 JSON 객체로 적어 다시 보내세요.");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	/** 서버가 본문을 읽기 전에 크기 한도에 걸린 요청. 프레임워크의 형식에 code 를 더한다. */
	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.CONTENT_TOO_LARGE, title(HttpStatus.CONTENT_TOO_LARGE), "FILE_TOO_LARGE",
				"file", "파일이 너무 큽니다. 받는 크기의 한도는 " + this.maxFileSize.toBytes()
						+ " 바이트입니다. 파일을 나누거나 줄여 다시 올리세요.");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
	}

	@ExceptionHandler(EntityNotFoundException.class)
	ProblemDetail handle(EntityNotFoundException e) {
		// core 의 이 문구는 사용자에게 보여도 되는 한국어 문구다: 어느 개체인지와 무엇을 확인할지를 담는다.
		return problem(HttpStatus.NOT_FOUND, title(HttpStatus.NOT_FOUND), "ENTITY_NOT_FOUND", null, e.getMessage());
	}

	/** 위에도 부모 클래스에도 걸리지 않은 것은 서버 쪽의 문제다. 원인은 기록에 남기고 응답에는 담지 않는다. */
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
			case UNAUTHORIZED -> "로그인 필요";
			case FORBIDDEN -> "받을 수 없는 요청";
			case NOT_FOUND -> "대상을 찾을 수 없음";
			case CONFLICT -> "지금은 할 수 없는 요청";
			case CONTENT_TOO_LARGE -> "너무 큰 파일";
			case UNPROCESSABLE_CONTENT -> "받아들일 수 없는 파일";
			default -> "서버 오류";
		};
	}

}
