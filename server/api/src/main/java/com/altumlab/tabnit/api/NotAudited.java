package com.altumlab.tabnit.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 이 API 의 요청은 감사 기록에 남기지 않는다. 왜 남기지 않는지를 함께 적는다 (D2 AD-274). */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface NotAudited {

	/** 남기지 않는 까닭. */
	String reason();

}
