package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.altumlab.tabnit.common.UserAccounts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SK-08: 서버가 화면의 파일을 함께 내준다 (D2 AD-277). 컨테이너로 띄우면 화면과 API 가 같은 주소에서 나간다.
 *
 * <p>화면의 주소(/changes 같은 것)는 파일이 아니다. 새로 고쳐도 화면이 뜨도록 화면의 첫 파일로 답한다 (D2 AD-257).
 * 화면의 파일은 이 시험이 임시 폴더에 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFilesHttpTests {

	private static final String INDEX = "<!doctype html><title>지어낸 화면</title><div id=\"root\"></div>";

	private static final Path WEB = createWebDirectory();

	@Value("${local.server.port}")
	int port;

	@Autowired
	UserAccounts accounts;

	@DynamicPropertySource
	static void webDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.web-dir", WEB::toString);
	}

	@Test
	void 화면의_주소는_로그인_없이_화면의_첫_파일로_답한다() throws Exception {
		HttpTestSession browser = new HttpTestSession(this.port);

		for (String path : new String[] { "/", "/changes", "/actions?packId=p&status=PENDING", "/relations", "/a/b/c",
				"/index.html" }) {
			HttpResponse<String> response = browser.send("GET", path, null, null);
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).as(path).startsWith("text/html");
			assertThat(response.body()).as(path).isEqualTo(INDEX);
		}
	}

	@Test
	void 화면의_파일은_그대로_내주고_없는_파일은_첫_파일이_아니라_404_다() throws Exception {
		HttpTestSession browser = new HttpTestSession(this.port);

		HttpResponse<String> script = browser.send("GET", "/assets/app-1a2b.js", null, null);
		assertThat(script.statusCode()).isEqualTo(200);
		assertThat(script.headers().firstValue("Content-Type").orElse("")).contains("javascript");
		assertThat(script.body()).isEqualTo("console.log('지어낸 화면');");
		// 파일의 이름으로 보이는 주소(마지막 마디에 점이 있다)는 없으면 없다고 답한다. 화면의 첫 파일을 스크립트로 내주지 않는다.
		assertThat(browser.send("GET", "/assets/missing.js", null, null).statusCode()).isEqualTo(404);
		assertThat(browser.send("GET", "/favicon.ico", null, null).statusCode()).isEqualTo(404);
		// 화면의 폴더 밖은 읽지 못한다.
		assertThat(browser.send("GET", "/assets/..%2f..%2fsecret.txt", null, null).statusCode()).isIn(400, 404);
		assertThat(browser.send("GET", "/%2e%2e/secret.txt", null, null).statusCode()).isIn(400, 404);
	}

	@Test
	void API_의_주소는_화면의_첫_파일로_답하지_않는다() throws Exception {
		HttpTestSession anonymous = new HttpTestSession(this.port);
		// 로그인하지 않으면 여전히 401 이다.
		assertThat(anonymous.send("GET", "/api/v1/packs", null, null).statusCode()).isEqualTo(401);
		assertThat(anonymous.send("GET", "/api/v1/no-such-api", null, null).statusCode()).isEqualTo(401);
		assertThat(anonymous.send("GET", "/api", null, null).statusCode()).isEqualTo(401);
		assertThat(anonymous.send("GET", "/actuator/env", null, null).statusCode()).isEqualTo(401);
		assertThat(anonymous.send("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
		// 바꾸는 요청은 화면의 주소에 받지 않는다.
		anonymous.send("GET", "/api/v1/session", null, null);
		assertThat(anonymous.send("POST", "/changes", "application/json", "{}".getBytes()).statusCode()).isIn(401, 403);

		// 로그인한 사람이 없는 API 를 부르면 화면이 아니라 404 다.
		HttpTestSession officer = HttpTestSession.loggedIn(this.port, this.accounts, "web-officer");
		HttpResponse<String> missing = officer.send("GET", "/api/v1/no-such-api", null, null);
		assertThat(missing.statusCode()).isEqualTo(404);
		assertThat(missing.body()).doesNotContain("지어낸 화면");
		assertThat(officer.send("GET", "/api/v1/packs", null, null).statusCode()).isEqualTo(200);
		// 대문자로 잘못 적은 API 와 운영 주소도 화면으로 답하지 않는다.
		for (String path : new String[] { "/API/v1/packs", "/Api", "/Actuator/env", "/ACTUATOR" }) {
			HttpResponse<String> response = officer.send("GET", path, null, null);
			assertThat(response.statusCode()).as(path).isEqualTo(404);
			assertThat(response.body()).as(path).doesNotContain("지어낸 화면");
		}
		// 이름이 비슷할 뿐인 화면의 주소는 화면이다.
		assertThat(officer.send("GET", "/apix", null, null).statusCode()).isEqualTo(200);
	}

	private static Path createWebDirectory() {
		try {
			Path parent = Files.createTempDirectory("tabnit-web-files").toRealPath();
			// 화면의 폴더 밖에 있는 파일. 읽히면 안 된다.
			Files.writeString(parent.resolve("secret.txt"), "화면의 폴더 밖");
			Path web = Files.createDirectories(parent.resolve("web"));
			Files.writeString(web.resolve("index.html"), INDEX);
			Files.createDirectories(web.resolve("assets"));
			Files.writeString(web.resolve("assets/app-1a2b.js"), "console.log('지어낸 화면');");
			return web;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
