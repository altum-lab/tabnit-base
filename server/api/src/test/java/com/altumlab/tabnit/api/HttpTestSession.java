package com.altumlab.tabnit.api;

import com.altumlab.tabnit.common.Role;
import com.altumlab.tabnit.common.UserAccounts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실제 HTTP 서버를 부르는 시험이 쓰는 브라우저 흉내: 쿠키를 들고 다니고, 바꾸는 요청에 위조 요청 방어의 토큰을 싣는다.
 *
 * <p>쿠키의 Secure 는 따지지 않고 다시 보낸다 (시험의 서버는 HTTP 다). 응답의 Set-Cookie 는 그대로 남겨 시험이 본다.
 */
final class HttpTestSession {

	/** 시험용으로 지어낸 비밀번호. 실제 계정의 것이 아니다. */
	static final String PASSWORD = "지어낸-시험용-비밀번호-3";

	private final HttpClient client = HttpClient.newHttpClient();

	private final String base;

	private final Map<String, String> cookies = new LinkedHashMap<>();

	/** 받은 Set-Cookie 를 온 차례대로. */
	final List<String> setCookies = new java.util.ArrayList<>();

	HttpTestSession(int port) {
		this.base = "http://localhost:" + port;
	}

	/** 사용자를 만들고 로그인한 세션을 돌려준다. */
	static HttpTestSession loggedIn(int port, UserAccounts accounts, String loginId) throws Exception {
		accounts.create(loginId, PASSWORD, Role.OFFICER, Instant.parse("2026-01-01T00:00:00Z"));
		HttpTestSession session = new HttpTestSession(port);
		session.send("GET", "/api/v1/session", null, null);
		HttpResponse<String> response = session.send("POST", "/api/v1/session", "application/json",
				("{ \"loginId\": \"" + loginId + "\", \"password\": \"" + PASSWORD + "\" }").getBytes(StandardCharsets.UTF_8));
		if (response.statusCode() != 200) {
			throw new IllegalStateException("시험의 로그인이 실패했습니다: " + response.statusCode() + " " + response.body());
		}
		return session;
	}

	String cookie(String name) {
		return this.cookies.get(name);
	}

	void forgetCookie(String name) {
		this.cookies.remove(name);
	}

	HttpResponse<String> send(String method, String path, String contentType, byte[] body) throws Exception {
		return send(method, path, contentType, body, true);
	}

	HttpResponse<String> send(String method, String path, String contentType, byte[] body, boolean withToken)
			throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.base + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
		if (contentType != null) {
			request.header("Content-Type", contentType);
		}
		if (!this.cookies.isEmpty()) {
			StringBuilder header = new StringBuilder();
			this.cookies.forEach((name, value) -> header.append(header.isEmpty() ? "" : "; ").append(name).append('=').append(value));
			request.header("Cookie", header.toString());
		}
		if (withToken && !method.equals("GET") && this.cookies.containsKey("XSRF-TOKEN")) {
			request.header("X-XSRF-TOKEN", this.cookies.get("XSRF-TOKEN"));
		}
		HttpResponse<String> response = this.client.send(request.build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		for (String setCookie : response.headers().allValues("Set-Cookie")) {
			this.setCookies.add(setCookie);
			String pair = setCookie.split(";", 2)[0];
			String name = pair.substring(0, pair.indexOf('='));
			String value = pair.substring(pair.indexOf('=') + 1);
			if (value.isEmpty()) {
				this.cookies.remove(name);
			}
			else {
				this.cookies.put(name, value);
			}
		}
		return response;
	}

}
