package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.altumlab.tabnit.common.UserAccounts;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * E-02: 로그인과 세션을 실제 HTTP 서버로 부른다. 쿠키와 위조 요청 방어의 토큰이 브라우저가 하는 대로 오가는지 본다.
 *
 * <p>서버 없이 부르는 시험은 쿠키의 속성과 토큰 쿠키를 거치지 않는다. 여기서 그 부분을 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionHttpTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	UserAccounts accounts;

	@Test
	void 브라우저가_하는_대로_토큰을_받고_로그인하고_쓰고_로그아웃한다() throws Exception {
		this.accounts.create("http-ses", HttpTestSession.PASSWORD, com.altumlab.tabnit.common.Role.OFFICER,
				java.time.Instant.parse("2026-01-01T00:00:00Z"));
		HttpTestSession browser = new HttpTestSession(this.port);
		byte[] credentials = ("{ \"loginId\": \"http-ses\", \"password\": \"" + HttpTestSession.PASSWORD + "\" }")
			.getBytes(StandardCharsets.UTF_8);

		// 화면이 처음 열릴 때: 로그인하지 않았다. 이때 토큰 쿠키가 온다. 세션은 아직 만들지 않는다.
		HttpResponse<String> first = browser.send("GET", "/api/v1/session", null, null);
		assertThat(first.statusCode()).isEqualTo(401);
		assertThat(first.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
		assertThat(first.body()).contains("\"code\":\"UNAUTHENTICATED\"");
		assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank();
		assertThat(browser.cookie("JSESSIONID")).isNull();
		// 토큰 쿠키는 화면의 스크립트가 읽어야 하므로 HttpOnly 가 아니다.
		assertThat(browser.setCookies.get(0)).startsWith("XSRF-TOKEN=").doesNotContainIgnoringCase("HttpOnly");

		// 토큰을 헤더에 싣지 않은 로그인은 막힌다.
		HttpResponse<String> blocked = browser.send("POST", "/api/v1/session", "application/json", credentials, false);
		assertThat(blocked.statusCode()).isEqualTo(403);
		assertThat(blocked.body()).contains("\"code\":\"FORBIDDEN\"");

		HttpResponse<String> login = browser.send("POST", "/api/v1/session", "application/json", credentials);
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(login.body()).contains("\"loginId\":\"http-ses\"").contains("\"role\":\"OFFICER\"");
		// 세션 쿠키: 스크립트가 읽지 못하고, 같은 사이트에서만 보내며, HTTPS 로만 보낸다.
		String sessionCookie = browser.setCookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
		assertThat(sessionCookie).containsIgnoringCase("HttpOnly")
			.containsIgnoringCase("SameSite=Strict")
			.containsIgnoringCase("Secure");
		assertThat(login.body()).doesNotContain(HttpTestSession.PASSWORD);

		assertThat(browser.send("GET", "/api/v1/session", null, null).body()).contains("\"loginId\":\"http-ses\"");
		assertThat(browser.send("GET", "/api/v1/packs", null, null).statusCode()).isEqualTo(200);
		// 로그인한 뒤에도 바꾸는 요청은 토큰이 있어야 한다.
		assertThat(browser.send("POST", "/api/v1/action-decisions", "application/json", "{}".getBytes(), false).statusCode())
			.isEqualTo(403);
		assertThat(browser.send("POST", "/api/v1/action-decisions", "application/json", "{}".getBytes()).statusCode())
			.isEqualTo(400);

		String oldSession = browser.cookie("JSESSIONID");
		assertThat(browser.send("DELETE", "/api/v1/session", null, null).statusCode()).isEqualTo(204);
		// 옛 세션 쿠키를 다시 보내도 로그인되어 있지 않다.
		HttpTestSession replay = new HttpTestSession(this.port);
		replay.send("GET", "/api/v1/session", null, null);
		assertThat(sendWithCookie(replay, oldSession)).isEqualTo(401);
		// 로그아웃한 뒤에 화면이 내 정보를 다시 물으면 새 토큰을 받아 다시 로그인할 수 있다.
		assertThat(browser.send("GET", "/api/v1/session", null, null).statusCode()).isEqualTo(401);
		assertThat(browser.cookie("XSRF-TOKEN")).isNotBlank();
		assertThat(browser.send("POST", "/api/v1/session", "application/json", credentials).statusCode()).isEqualTo(200);
	}

	@Test
	void 화면의_주소는_로그인_없이_열리고_API_가_아닌_곳에_바꾸는_요청은_받지_않는다() throws Exception {
		HttpTestSession browser = new HttpTestSession(this.port);

		// 화면의 파일이 아직 없어 404 다. 401 이 아니다: 화면이 떠야 로그인할 수 있다.
		assertThat(browser.send("GET", "/changes", null, null).statusCode()).isEqualTo(404);
		assertThat(browser.send("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
		assertThat(browser.send("GET", "/actuator/env", null, null).statusCode()).isEqualTo(401);
		browser.send("GET", "/api/v1/session", null, null);
		assertThat(browser.send("POST", "/changes", "application/json", "{}".getBytes()).statusCode()).isIn(401, 403);
	}

	private int sendWithCookie(HttpTestSession session, String sessionId) throws Exception {
		java.net.http.HttpRequest request = java.net.http.HttpRequest
			.newBuilder(java.net.URI.create("http://localhost:" + this.port + "/api/v1/session"))
			.header("Cookie", "JSESSIONID=" + sessionId)
			.GET()
			.build();
		return java.net.http.HttpClient.newHttpClient()
			.send(request, HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

}
