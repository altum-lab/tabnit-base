package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.AuditLogException;
import com.altumlab.tabnit.common.LoginAttempt;
import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 로그인하지 않으면 API 를 쓸 수 없게 한다 (E-02, D7 9절).
 *
 * <p>서버 세션과 쿠키로 로그인한 상태를 유지한다 (D2 AD-262). 세션, 쿠키, 위조 요청 방어는 Spring Security 의 것을 쓰고
 * 직접 짜지 않는다 (D2 AD-263). 아이디와 비밀번호가 맞는지, 막혀 있는지는 common 의 {@link UserAccounts} 가 정한다.
 *
 * <p>어느 API 를 어느 역할이 부를 수 있는지도 여기 한 곳에 적는다 (E-06, D7 2절). 역할은 층으로 쌓인다: 관리자는
 * 담당자의 일을 모두 한다 (D2 AD-270). 새 API 는 따로 적지 않으면 담당자와 관리자의 것이다. 관리자만 하는 일은
 * 여기에 적는다.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	static final String SESSION_PATH = "/api/v1/session";

	private static final String ADMIN = Role.ADMIN.name();

	private static final String OFFICER = Role.OFFICER.name();

	private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

	/** 로그인한 사람을 세션에 담고 읽는 곳. 로그인 API 와 필터가 같은 것을 쓴다. */
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/**
	 * 아이디와 비밀번호를 확인한다. 판단은 common 이 하고, 지금이 언제인지는 서버의 시계에서 읽어 넘긴다.
	 *
	 * <p>없는 아이디, 틀린 비밀번호, 막힌 계정을 가리지 않고 같은 실패로 낸다 (D7 9절).
	 */
	@Bean
	AuthenticationManager authenticationManager(UserAccounts accounts, Clock clock) {
		return authentication -> {
			LoginAttempt attempt = accounts.authenticate(authentication.getName(), (String) authentication.getCredentials(),
					clock.instant());
			if (attempt.outcome() != LoginAttempt.Outcome.SUCCEEDED) {
				throw new BadCredentialsException("로그인하지 못했습니다");
			}
			// 비밀번호는 담지 않는다.
			return UsernamePasswordAuthenticationToken.authenticated(attempt.account().loginId(), null,
					List.of(new SimpleGrantedAuthority(SessionController.ROLE_PREFIX + attempt.account().role().name())));
		};
	}

	/** HTTP 서버로 뜰 때만 있다. 사용자를 만드는 실행은 HTTP 없이 뜬다 (D7 9절). */
	@Bean
	@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
	SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver problems, AuditTrail audit) throws Exception {
		http.authorizeHttpRequests(requests -> requests
			// 오류를 그리는 내부 전달.
			.dispatcherTypeMatchers(DispatcherType.ERROR)
			.permitAll()
			.requestMatchers(HttpMethod.POST, SESSION_PATH)
			.permitAll()
			// 서버가 떠 있는지는 로그인 없이 볼 수 있다. 그 밖의 운영 주소는 로그인해야 한다.
			.requestMatchers("/actuator/health", "/actuator/health/**")
			.permitAll()
			// 로그아웃은 로그아웃 필터가 맡아 여기까지 오지 않는다: 역할이 무엇이든 로그아웃한다.
			// 관리자만 하는 일.
			.requestMatchers(HttpMethod.POST, PackLoadsController.PATH)
			.hasRole(ADMIN)
			.requestMatchers("/actuator/**")
			.hasRole(ADMIN)
			// 그 밖의 API 는 담당자와 관리자가 한다. 둘 다 아닌 역할은 아무것도 부르지 못한다.
			.requestMatchers("/api/**")
			.hasAnyRole(OFFICER, ADMIN)
			// 화면의 파일. 화면이 떠야 로그인할 수 있다.
			.requestMatchers(HttpMethod.GET, "/**")
			.permitAll()
			.requestMatchers(HttpMethod.HEAD, "/**")
			.permitAll()
			.anyRequest()
			.denyAll());
		http.securityContext(context -> context.securityContextRepository(contexts));
		// 위조 요청 방어: 서버가 XSRF-TOKEN 쿠키로 준 값을 화면이 X-XSRF-TOKEN 헤더에 실어 보낸다 (D2 AD-267).
		http.csrf(csrf -> csrf.spa());
		http.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
		http.logout(logout -> logout
			.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.DELETE, SESSION_PATH))
			// 로그아웃을 감사 기록에 남긴다. 세션을 끝내기 전에 남기고, 남기지 못하면 로그아웃하지 않는다.
			// 로그인하지 않은 채로 부른 로그아웃은 남기지 않는다 (누구인지 모른다).
			.addLogoutHandler((request, response, authentication) -> {
				if (authentication != null) {
					audit.logout(authentication.getName());
				}
			})
			.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)));
		// 로그아웃을 남기지 못했을 때 공통 오류 형식으로 답한다. 로그아웃 필터는 오류를 처리하는 곳보다 앞에 있다.
		http.addFilterBefore(new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
					throws ServletException, IOException {
				try {
					chain.doFilter(request, response);
				}
				catch (AuditLogException e) {
					if (response.isCommitted()) {
						throw e;
					}
					problems.resolveException(request, response, null, e);
				}
			}
		}, LogoutFilter.class);
		// 로그인 화면으로 보내지 않는다. 공통 오류 형식으로 답한다 (D7 2절).
		http.exceptionHandling(handling -> handling
			.authenticationEntryPoint((request, response, e) -> problems.resolveException(request, response, null,
					new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null,
							"로그인이 필요합니다. 로그인한 뒤 다시 시도하세요.")))
			.accessDeniedHandler((request, response, e) -> {
				if (e instanceof CsrfException) {
					// 위조 요청 방어의 토큰이 없거나 틀렸다.
					denied(audit, request, true);
					problems.resolveException(request, response, null, new ApiProblemException(HttpStatus.FORBIDDEN,
							"FORBIDDEN", null, "요청을 받을 수 없습니다. 화면을 새로 고친 뒤 다시 시도하세요."));
					return;
				}
				denied(audit, request, false);
				problems.resolveException(request, response, null, new ApiProblemException(HttpStatus.FORBIDDEN,
						"FORBIDDEN", null, "이 일을 할 권한이 없습니다. 필요하면 관리자에게 요청하세요."));
			}));
		// 로그인하지 않은 요청 때문에 세션을 만들지 않는다.
		http.requestCache(cache -> cache.disable());
		return http.build();
	}

	/**
	 * 거부한 요청을 감사 기록에 남긴다 (common 지침: 권한이 없는 요청은 거부하고 그 사실도 남긴다).
	 * 남기지 못해도 거부는 한다: 그 사실은 서버의 로그에 적는다.
	 */
	private static void denied(AuditTrail audit, HttpServletRequest request, boolean csrf) {
		try {
			audit.denied(request, csrf);
		}
		catch (AuditLogException e) {
			log.error("거부한 요청을 감사 기록에 남기지 못했습니다: {} {} (아이디 {})", request.getMethod(),
					request.getRequestURI(), request.getUserPrincipal() == null ? "-" : request.getUserPrincipal().getName(), e);
		}
	}

	/**
	 * 화면이 내 정보를 물을 때 위조 요청 방어의 토큰을 쿠키로 내보낸다.
	 *
	 * <p>토큰은 누가 읽을 때에야 만들어진다. 화면은 처음 열릴 때 내 정보를 묻고, 그 응답의 쿠키로 토큰을 받는다 (D7 9절).
	 */
	private static final class CsrfCookieFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (HttpMethod.GET.matches(request.getMethod()) && SESSION_PATH.equals(request.getRequestURI())
					&& request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
				token.getToken();
			}
			chain.doFilter(request, response);
		}

	}

}
