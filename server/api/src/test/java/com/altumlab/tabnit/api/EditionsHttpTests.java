package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * D-05 (서버 쪽): 판을 올리는 API 를 실제 HTTP 서버로 부른다.
 *
 * <p>다른 시험은 서버 없이 부른다. 그 방식은 multipart 요청을 서버가 풀어 읽는 부분과 크기 한도의 설정을 거치지 않는다.
 * 여기서는 진짜 요청을 보내 그 부분을 확인한다. 파일 크기의 한도는 이 시험에서만 작게 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.servlet.multipart.max-file-size=4KB", "spring.servlet.multipart.max-request-size=64KB" })
class EditionsHttpTests {

	@Value("${local.server.port}")
	int port;

	@Autowired
	DataSource dataSource;

	@Autowired
	com.altumlab.tabnit.common.UserAccounts accounts;

	@TempDir
	Path temp;

	@Test
	void 진짜_요청으로_판을_올리면_받아들인다() throws Exception {
		String packId = "http-first";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		HttpResponse<String> response = post(Map.of("packId", packId, "inputSourceId", "guideline", "effectiveDate",
				"2026-01-01"), "지침 자료.csv", Files.readAllBytes(pack.resolve("data/guideline-v1.csv")));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		assertThat(response.body()).contains("\"outcome\":\"ACCEPTED\"")
			.contains("\"editionId\":\"" + packId + "|guideline|1\"")
			.contains("\"processingNotStarted\":false");
	}

	@Test
	void 크기_한도를_넘는_파일은_서버가_읽기_전에_413_으로_막는다() throws Exception {
		String packId = "http-large";
		PackCopies.load(this.dataSource, this.temp, packId, text -> text);

		HttpResponse<String> response = post(Map.of("packId", packId, "inputSourceId", "guideline", "effectiveDate",
				"2026-01-01"), "big.csv", "가".repeat(3000).getBytes(StandardCharsets.UTF_8));

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"code\":\"FILE_TOO_LARGE\"").contains("\"field\":\"file\"");
	}

	private HttpResponse<String> post(Map<String, String> fields, String fileName, byte[] content) throws Exception {
		String boundary = "----tabnit-test-boundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		// 항목의 차례는 결과에 닿지 않는다. 정해진 차례로 적는다.
		for (String name : new java.util.TreeSet<>(fields.keySet())) {
			body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + fields.get(name)
					+ "\r\n")
				.getBytes(StandardCharsets.UTF_8));
		}
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
				+ "\"\r\nContent-Type: text/csv\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8));
		body.write(content);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		// 로그인한 사람의 요청으로 보낸다. 아이디는 Pack 마다 달리 둔다.
		HttpTestSession session = HttpTestSession.loggedIn(this.port, this.accounts, "u-" + fields.get("packId"));
		return session.send("POST", "/api/v1/editions", "multipart/form-data; boundary=" + boundary, body.toByteArray());
	}
}
