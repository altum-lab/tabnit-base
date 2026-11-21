package com.altumlab.tabnit.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인과 내 정보. 규격은 docs/api/openapi.yaml 의 login 과 getSession, 규칙은 D7 9절이다.
 * 로그아웃(logout)은 보안 설정이 맡는다.
 *
 * <p>받고, 확인을 맡기고, 세션에 담는다. 비밀번호를 기록이나 응답에 남기지 않는다.
 */
@RestController
class SessionController {

	static final String ROLE_PREFIX = "ROLE_";

	private final AuthenticationManager authentications;

	private final SecurityContextRepository contexts;

	SessionController(AuthenticationManager authentications, SecurityContextRepository contexts) {
		this.authentications = authentications;
		this.contexts = contexts;
	}

	@GetMapping(SecurityConfiguration.SESSION_PATH)
	SessionResponse get(Authentication authentication) {
		return response(authentication);
	}

	@PostMapping(SecurityConfiguration.SESSION_PATH)
	SessionResponse login(@RequestBody Map<String, Object> body, HttpServletRequest request,
			HttpServletResponse response) {
		// 글자가 아닌 값(수, 목록)을 글자로 바꿔 받지 않는다.
		if (!(body.get("loginId") instanceof String loginId) || loginId.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_LOGIN_ID", "loginId",
					"아이디가 없습니다. 아이디를 넣어 다시 시도하세요.");
		}
		if (!(body.get("password") instanceof String password) || password.isEmpty()) {
			throw new ApiProblemException(HttpStatus.BAD_REQUEST, "MISSING_PASSWORD", "password",
					"비밀번호가 없습니다. 비밀번호를 넣어 다시 시도하세요.");
		}
		Authentication authentication;
		try {
			authentication = this.authentications
				.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(loginId, password));
		}
		catch (AuthenticationException e) {
			// 없는 아이디, 틀린 비밀번호, 막힌 계정을 가리지 않는다. 계정이 있는지를 알려 주지 않는다.
			throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null,
					"로그인하지 못했습니다. 아이디와 비밀번호를 확인하세요. 여러 번 틀렸다면 잠시 막혀 있을 수 있으니 조금 뒤에 다시 시도하세요.");
		}
		// 로그인 전의 세션을 이어 쓰지 않는다. 남이 심어 둔 세션으로 로그인되는 일을 막는다.
		HttpSession old = request.getSession(false);
		if (old != null) {
			old.invalidate();
		}
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		this.contexts.saveContext(context, request, response);
		return response(authentication);
	}

	private static SessionResponse response(Authentication authentication) {
		String role = authentication.getAuthorities()
			.stream()
			.map(GrantedAuthority::getAuthority)
			.filter(authority -> authority.startsWith(ROLE_PREFIX))
			.map(authority -> authority.substring(ROLE_PREFIX.length()))
			.sorted()
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("로그인한 사람에게 역할이 없습니다"));
		return new SessionResponse(authentication.getName(), role);
	}

}
