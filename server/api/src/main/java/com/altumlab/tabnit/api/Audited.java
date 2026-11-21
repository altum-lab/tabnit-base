package com.altumlab.tabnit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 API 의 요청을 감사 기록에 남긴다 (D7 2절). 하기 전에 한 줄, 끝난 뒤에 한 줄이다.
 *
 * <p>모든 API 는 이것이나 {@link NotAudited} 를 붙인다. 둘 다 없는 API 는 요청을 받지 않는다: 남기는 것을 빠뜨리지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface Audited {

	/** 기능의 이름. 규격(docs/api/openapi.yaml)의 operationId 와 같게 적는다. */
	String action();

	/**
	 * 대상을 가리키는 요청의 항목(주소나 폼의 항목)의 이름. 적은 차례대로 남긴다. 본문(JSON)의 항목은 여기 적지 못한다:
	 * 그 API 가 읽은 뒤에 {@link AuditTrail#target} 로 알린다.
	 */
	String[] params() default {};

}
