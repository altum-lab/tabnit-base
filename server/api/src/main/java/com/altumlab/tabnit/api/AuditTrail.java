package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditEntry;
import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 요청을 감사 기록에 남긴다 (D2 AD-05, AD-273 ~ AD-275, D7 2절).
 *
 * <p>요청 하나는 두 줄이다. 하기 전에 "누가 무엇을 하려 한다"를 먼저 남긴다: 남기지 못하면 변경을 하지 않고 조회도 답하지
 * 않는다. 끝나면 결과를 한 줄 더 남긴다. 무엇을 남길지는 여기서 정하고, 저장은 common 의 {@link AuditLog} 가 한다.
 * 언제인지는 서버의 시계에서 읽어 넘긴다.
 *
 * <p>비밀번호, 요청의 본문 전체, 올린 파일의 이름과 내용, 확정과 보류의 사유는 남기지 않는다.
 */
class AuditTrail {

	private static final String PREFIX = AuditTrail.class.getName() + ".";

	private static final String STARTED_ID = PREFIX + "startedId";

	private static final String ACTION = PREFIX + "action";

	private static final String TARGET = PREFIX + "target";

	private static final String DETAIL = PREFIX + "detail";

	private static final String AS_OF = PREFIX + "asOf";

	/** 항목 하나의 값으로 남기는 길이의 한도. 글자(코드 포인트)의 수다. */
	private static final int MAX_VALUE_LENGTH = 200;

	/** 기준 날짜로 남기는 해의 범위. */
	private static final int MIN_YEAR = 1;

	private static final int MAX_YEAR = 9999;

	private final AuditLog log;

	private final Clock clock;

	AuditTrail(AuditLog log, Clock clock) {
		this.log = log;
		this.clock = clock;
	}

	// ── API 가 알리는 것 ────────────────────────────────────────────

	/** 지금 처리하는 요청의 대상을 알린다. 본문에서 읽은 항목에 쓴다. 여러 번 부르면 차례대로 이어 남긴다. */
	static void target(String name, String value) {
		append(TARGET, name, value);
	}

	/** 지금 처리하는 요청에 대해 더 남길 것을 알린다 (무엇을 했는지, 결과가 무엇인지). */
	static void detail(String name, String value) {
		append(DETAIL, name, value);
	}

	/** 지금 처리하는 조회가 답에 쓴 기준 날짜를 알린다. */
	static void asOf(LocalDate date) {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		// 저장소가 담지 못하는 먼 해의 날짜는 남기지 않는다. 그 때문에 결과의 줄을 통째로 잃지 않는다.
		if (request != null && date != null && date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR) {
			request.setAttribute(AS_OF, date, RequestAttributes.SCOPE_REQUEST);
		}
	}

	private static void append(String attribute, String name, String value) {
		RequestAttributes request = RequestContextHolder.getRequestAttributes();
		if (request == null || value == null) {
			// 요청 밖에서 불렸다 (시험이 직접 부른 경우). 남길 곳이 없다.
			return;
		}
		String before = (String) request.getAttribute(attribute, RequestAttributes.SCOPE_REQUEST);
		String pair = name + "=" + shorten(value);
		request.setAttribute(attribute, before == null ? pair : before + "; " + pair, RequestAttributes.SCOPE_REQUEST);
	}

	// ── 요청의 앞과 뒤 ──────────────────────────────────────────────

	/**
	 * 하기 전의 줄을 남긴다. 남기지 못하면 예외가 나가고, 그 요청은 처리하지 않는다.
	 *
	 * @throws com.altumlab.tabnit.common.AuditLogException 남기지 못했을 때
	 */
	void start(HttpServletRequest request, Audited audited) {
		String target = null;
		for (String name : audited.params()) {
			// 같은 항목이 여러 번 오면 서버는 쉼표로 이어 하나의 값으로 받는다. 처리하는 값 그대로 남긴다.
			String[] values = request.getParameterValues(name);
			String value = values == null ? null : String.join(",", values);
			if (value != null && !value.isEmpty()) {
				String pair = name + "=" + shorten(value);
				target = target == null ? pair : target + "; " + pair;
			}
		}
		long id = this.log.record(new AuditEntry(this.clock.instant(), actor(request), audited.action(), target, null,
				AuditOutcome.STARTED, null, null, null));
		request.setAttribute(STARTED_ID, id);
		request.setAttribute(ACTION, audited.action());
		if (target != null) {
			request.setAttribute(TARGET, target);
		}
	}

	/** 하기 전의 줄을 남긴 요청인가. */
	static boolean started(HttpServletRequest request) {
		return request.getAttribute(STARTED_ID) != null;
	}

	/**
	 * 결과의 줄을 남긴다.
	 *
	 * @param status 응답의 HTTP 상태
	 * @throws com.altumlab.tabnit.common.AuditLogException 남기지 못했을 때
	 */
	void finish(HttpServletRequest request, int status) {
		Long startedId = (Long) request.getAttribute(STARTED_ID);
		if (startedId == null) {
			return;
		}
		this.log.record(new AuditEntry(this.clock.instant(), actor(request), (String) request.getAttribute(ACTION),
				(String) request.getAttribute(TARGET), (LocalDate) request.getAttribute(AS_OF), outcomeOf(status), status,
				(String) request.getAttribute(DETAIL), startedId));
	}

	/**
	 * 권한이 없어 거부한 요청을 한 줄로 남긴다. 누구인지 아는 요청만 남긴다 (D2 AD-274).
	 *
	 * @param csrf 위조 요청 방어에 걸린 것인가. 아니면 역할이 할 수 없는 일이다
	 */
	void denied(HttpServletRequest request, boolean csrf) {
		String actor = actor(request);
		if (actor == null) {
			return;
		}
		// 본문은 읽지 않았다. 무엇을 부르려 했는지는 방식과 주소로 남긴다. 주소의 물음표 뒤는 남기지 않는다.
		this.log.record(new AuditEntry(this.clock.instant(), actor, "accessDenied",
				request.getMethod() + " " + request.getRequestURI(), null, AuditOutcome.DENIED, 403,
				csrf ? "reason=CSRF" : "reason=ROLE", null));
	}

	/** 로그아웃을 한 줄로 남긴다. 남기지 못하면 예외가 나가고 로그아웃하지 않는다. */
	void logout(String actor) {
		this.log.record(
				new AuditEntry(this.clock.instant(), actor, "logout", null, null, AuditOutcome.SUCCESS, 204, null, null));
	}

	/** 로그인한 사람의 아이디. 로그인하지 않았으면 null. */
	private static String actor(HttpServletRequest request) {
		Principal principal = request.getUserPrincipal();
		if (principal != null) {
			return principal.getName();
		}
		// 위조 요청 방어처럼 앞쪽에서 막힌 요청은 요청에 아직 사람이 실려 있지 않다. 보안 문맥에서 읽는다.
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return null;
		}
		return authentication.getName();
	}

	/** 2xx 는 성공, 401 과 403 은 거부, 그 밖은 실패. */
	private static AuditOutcome outcomeOf(int status) {
		if (status >= 200 && status < 300) {
			return AuditOutcome.SUCCESS;
		}
		if (status == 401 || status == 403) {
			return AuditOutcome.DENIED;
		}
		return AuditOutcome.FAILED;
	}

	private static String shorten(String value) {
		if (value.codePointCount(0, value.length()) <= MAX_VALUE_LENGTH) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, MAX_VALUE_LENGTH - 1)) + "…";
	}

}
