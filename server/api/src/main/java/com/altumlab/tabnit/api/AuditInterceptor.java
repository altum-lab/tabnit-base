package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLogException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 의 요청마다 감사 기록을 남긴다: 처리하기 전에 한 줄, 끝난 뒤에 한 줄 (D2 AD-273).
 *
 * <p>하기 전의 줄을 남기지 못하면 요청을 처리하지 않는다 (조회도 답하지 않는다, D2 AD-275). 결과의 줄을 남기지 못한 것은
 * 되돌릴 수 없다: 일은 끝났고 응답은 나갔다. 하기 전의 줄이 남아 있으므로 서버의 로그에 알리기만 한다.
 */
class AuditInterceptor implements HandlerInterceptor {

	private static final Logger log = LoggerFactory.getLogger(AuditInterceptor.class);

	private final AuditTrail trail;

	AuditInterceptor(AuditTrail trail) {
		this.trail = trail;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method) || !isOurs(method)) {
			// 없는 주소 같은 것. 프레임워크가 답한다.
			return true;
		}
		Audited audited = method.getMethodAnnotation(Audited.class);
		if (audited != null) {
			this.trail.start(request, audited);
			return true;
		}
		if (method.getMethodAnnotation(NotAudited.class) != null) {
			return true;
		}
		// 남길지 말지를 밝히지 않은 API 는 처리하지 않는다. 남기는 것을 빠뜨린 채 열리지 않게 한다.
		throw new IllegalStateException("감사 기록에 남길지 정하지 않은 API 입니다: " + method
				+ ". @Audited 나 @NotAudited 를 붙이세요.");
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (!AuditTrail.started(request)) {
			return;
		}
		try {
			// 처리되지 않은 예외로 끝났으면 응답의 상태가 아직 정해지지 않았을 수 있다. 서버 쪽의 실패로 남긴다.
			this.trail.finish(request, ex != null ? 500 : response.getStatus());
		}
		catch (AuditLogException e) {
			log.error("감사 기록에 결과를 남기지 못했습니다. 하기 전의 줄은 남아 있습니다: {} {}", request.getMethod(),
					request.getRequestURI(), e);
		}
	}

	private static boolean isOurs(HandlerMethod method) {
		return method.getBeanType().getPackageName().equals(AuditInterceptor.class.getPackageName());
	}

}
