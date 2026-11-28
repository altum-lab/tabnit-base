package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * E-23: 적재된 Pack 의 검증 질문을 지금의 저장소에 묻는 API (docs/api/openapi.yaml 의 getVerificationResults, D7 10절
 * "검증 질문을 화면에서 돌린다", D2 AD-422).
 *
 * <p>서버의 Pack 폴더는 이 시험이 만든 임시 폴더다. 그 안에 시험용 Pack 의 사본을 두고, API 로 적재하고, 그 사본의
 * 시나리오의 판을 API 로 올린다. 시험용 Pack 의 질문은 명령줄 실행기(`bash verify/run.sh`)에서 모두 통과한다:
 * 판이 시나리오와 같으면 여기서도 모두 통과해야 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class VerificationResultsApiTests {

	private static final Path PACKS = createPacksDirectory();

	private static final Pattern STEP = Pattern
		.compile("inputSource:\\s*([^,\\s]+)\\s*,\\s*file:\\s*([^,\\s]+)\\s*,\\s*effectiveDate:\\s*(\\d{4}-\\d{2}-\\d{2})");

	private static final RequestPostProcessor ADMIN = user("vr-admin").roles("ADMIN");

	private static final RequestPostProcessor OFFICER = user("vr-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	AuditLog audit;

	MockMvc mvc;

	@DynamicPropertySource
	static void packsDirectory(DynamicPropertyRegistry registry) {
		registry.add("tabnit.packs-dir", PACKS::toString);
	}

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	@Test
	void 판이_시나리오와_같으면_모든_질문을_묻고_통과와_실패가_명령줄의_것과_같다() throws Exception {
		String packId = "vr-ok";
		Path pack = prepare(packId);
		uploadScenario(packId, pack, Integer.MAX_VALUE);
		int questions = countQuestions(pack);
		assertThat(questions).as("시험용 Pack 의 질문").isGreaterThan(10);

		String body = results(packId);

		assertThat(JsonPath.<Boolean>read(body, "$.available")).isTrue();
		assertThat(JsonPath.<List<Object>>read(body, "$.scenarios")).hasSize(1);
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).as(body).isTrue();
		assertThat(JsonPath.<String>read(body, "$.scenarios[0].scenarioId")).isEqualTo("S-01");
		assertThat(JsonPath.<Integer>read(body, "$.scenarios[0].questionCount")).isEqualTo(questions);
		// 시험용 Pack 의 질문은 명령줄 실행기에서 모두 통과한다. 여기서도 같다.
		List<Boolean> passed = JsonPath.read(body, "$.scenarios[0].results[*].passed");
		assertThat(passed).hasSize(questions);
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[?(@.passed == false)].id")).as(body).isEmpty();
		assertThat(JsonPath.<Integer>read(body, "$.scenarios[0].passedCount")).isEqualTo(questions);
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].repeatable")).isTrue();
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].differingQuestionIds")).isEmpty();
		// 질문은 번호 순이고, 종류의 이름표와 어느 날짜나 단계를 물었는지가 있다. 모든 종류를 물었다.
		List<String> ids = JsonPath.read(body, "$.scenarios[0].results[*].id");
		assertThat(ids).isSorted();
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[*].kind")).contains("relations-as-of", "changes",
				"impact", "actions", "attribute-values-as-of");
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[*].kindLabel")).contains("시점 조회", "속성 값");
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[*].label")).doesNotContain("");
		// 두 번 불러도 같다.
		assertThat(results(packId)).isEqualTo(body);
	}

	@Test
	void 예시_Pack_둘도_판이_시나리오와_같으면_모든_질문이_통과한다() throws Exception {
		// 두 예시 Pack 의 질문은 명령줄 실행기에서 모두 통과한다 (관계 지도의 질문도 들어 있다). 화면의 길에서도 같아야 한다.
		for (String source : List.of("manufacturing-safety", "retirement-pension")) {
			String packId = "vr-" + source;
			Path pack = copyExample(source, packId);
			this.mvc.perform(post(PackLoadsController.PATH).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"packId\":\"" + packId + "\"}"))
				.andExpect(status().isOk());
			uploadScenario(packId, pack, Integer.MAX_VALUE);
			int questions = countQuestions(pack);
			assertThat(questions).as(source).isGreaterThan(30);

			String body = results(packId);

			assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).as(source + " " + body).isTrue();
			assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[?(@.passed == false)].id")).as(source).isEmpty();
			assertThat(JsonPath.<Integer>read(body, "$.scenarios[0].passedCount")).as(source).isEqualTo(questions);
			assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].repeatable")).as(source).isTrue();
		}
	}

	@Test
	void 정답이_다른_질문은_실패이고_기대한_값과_실제_값을_함께_준다() throws Exception {
		String packId = "vr-wrong";
		Path pack = prepare(packId);
		// 질문 파일의 정답 하나를 틀리게 고친다: 운영지침 G-01 의 점검 주기 30 을 31 로.
		Path questions = pack.resolve("questions/S-01.yaml");
		String text = Files.readString(questions, StandardCharsets.UTF_8);
		String wrong = text.replace("{ attribute: 점검주기일, value: \"30\", validFrom: 2026-01-01, validTo: 2026-03-01 }",
				"{ attribute: 점검주기일, value: \"31\", validFrom: 2026-01-01, validTo: 2026-03-01 }");
		assertThat(wrong).isNotEqualTo(text);
		Files.writeString(questions, wrong, StandardCharsets.UTF_8);
		uploadScenario(packId, pack, Integer.MAX_VALUE);

		String body = results(packId);

		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).isTrue();
		List<String> failed = JsonPath.read(body, "$.scenarios[0].results[?(@.passed == false)].id");
		assertThat(failed).containsExactly("Q-15");
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[?(@.id == 'Q-15')].expected").get(0)).contains("31");
		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[0].results[?(@.id == 'Q-15')].actual").get(0)).contains("30");
		assertThat(JsonPath.<Integer>read(body, "$.scenarios[0].passedCount"))
			.isEqualTo(JsonPath.<Integer>read(body, "$.scenarios[0].questionCount") - 1);
	}

	@Test
	void 판이_시나리오와_다르면_묻지_않고_어느_단계가_다른지_알린다() throws Exception {
		// (가) 단계가 모자란다: 마지막 단계를 올리지 않았다.
		String shortId = "vr-short";
		Path shortPack = prepare(shortId);
		int steps = steps(shortPack).size();
		uploadScenario(shortId, shortPack, steps - 1);
		String shortBody = results(shortId);
		assertThat(JsonPath.<Boolean>read(shortBody, "$.available")).isTrue();
		assertThat(JsonPath.<Boolean>read(shortBody, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(shortBody, "$.scenarios[0].mismatch")).contains(steps + "단계");
		assertThat(JsonPath.<List<Object>>read(shortBody, "$.scenarios[0].results")).isEmpty();
		assertThat(JsonPath.<Integer>read(shortBody, "$.scenarios[0].passedCount")).isZero();

		// (나) 판이 더 있다: 시나리오를 다 올린 뒤 판 하나를 더 올렸다.
		String extraId = "vr-extra";
		Path extraPack = prepare(extraId);
		uploadScenario(extraId, extraPack, Integer.MAX_VALUE);
		upload(extraId, "team", "2026-09-01", "team-extra.csv",
				(Files.readString(extraPack.resolve("data/team-v1.csv"), StandardCharsets.UTF_8) + "T-99,지어낸 팀\n")
					.getBytes(StandardCharsets.UTF_8));
		String extraBody = results(extraId);
		assertThat(JsonPath.<Boolean>read(extraBody, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(extraBody, "$.scenarios[0].mismatch")).contains("team");

		// (다) 파일의 내용이 다르다: 한 단계를 다른 내용으로 올렸다 (적용일과 차례는 같다).
		String otherId = "vr-other";
		Path otherPack = prepare(otherId);
		List<String[]> all = steps(otherPack);
		for (int i = 0; i < all.size(); i++) {
			String[] step = all.get(i);
			byte[] content = Files.readAllBytes(otherPack.resolve(step[1]));
			if (i == all.size() - 1) {
				content = (new String(content, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
			}
			upload(otherId, step[0], step[2], Path.of(step[1]).getFileName().toString(), content);
		}
		String otherBody = results(otherId);
		assertThat(JsonPath.<Boolean>read(otherBody, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(otherBody, "$.scenarios[0].mismatch")).contains(all.size() + "단계").contains("내용");

		// (라) 판이 하나도 없다.
		String emptyId = "vr-empty";
		prepare(emptyId);
		String emptyBody = results(emptyId);
		assertThat(JsonPath.<Boolean>read(emptyBody, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(emptyBody, "$.scenarios[0].mismatch")).contains("1단계");
	}

	@Test
	void 판을_만들지_않는_단계가_든_시나리오는_돌릴_수_없다고_분명히_알린다() throws Exception {
		// 같은 파일을 한 번 더 올리는 단계: 올리면 "바뀐 것 없음"이라 판이 생기지 않는다 (D2 AD-164).
		// 명령줄 실행기는 그 단계까지 올려서 묻지만, 저장소의 판을 단계와 하나씩 맞추는 화면의 길은 맞출 수 없다.
		String packId = "vr-noedition";
		Path pack = prepare(packId);
		Path questions = pack.resolve("questions/S-01.yaml");
		String text = Files.readString(questions, StandardCharsets.UTF_8);
		String last = "    - { step: 10, inputSource: guideline,         file: data/guideline-v3.csv,         effectiveDate: 2026-06-01 }\n";
		assertThat(text).contains(last);
		Files.writeString(questions, text.replace(last,
				last + "    - { step: 11, inputSource: guideline, file: data/guideline-v3.csv, effectiveDate: 2026-07-01 }\n"),
				StandardCharsets.UTF_8);
		uploadScenario(packId, pack, 10);

		String body = results(packId);

		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).isFalse();
		assertThat(JsonPath.<String>read(body, "$.scenarios[0].mismatch")).contains("11단계").contains("판을 만들지 않는")
			.contains("verify/run.sh");
		assertThat(JsonPath.<List<Object>>read(body, "$.scenarios[0].results")).isEmpty();
	}

	@Test
	void 질문_파일이_여럿이면_맞지_않는_시나리오에_한_번에_하나만_맞을_수_있다고_알린다() throws Exception {
		String packId = "vr-two";
		Path pack = prepare(packId);
		// 둘째 질문 파일: 같은 시나리오에서 마지막 단계를 뺀 것. 두 시나리오가 같은 저장소에서 함께 맞을 수 없다.
		String text = Files.readString(pack.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8);
		String last = "    - { step: 10, inputSource: guideline,         file: data/guideline-v3.csv,         effectiveDate: 2026-06-01 }\n";
		Files.writeString(pack.resolve("questions/S-02.yaml"), text.replace(last, "").replace("id: S-01", "id: S-02"),
				StandardCharsets.UTF_8);
		uploadScenario(packId, pack, Integer.MAX_VALUE);

		String body = results(packId);

		assertThat(JsonPath.<List<String>>read(body, "$.scenarios[*].scenarioId")).containsExactly("S-01", "S-02");
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).isTrue();
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[1].matched")).isFalse();
		assertThat(JsonPath.<String>read(body, "$.scenarios[1].mismatch")).contains("질문 파일이 2개").contains("한 번에 하나");
		// 질문 파일이 하나뿐인 Pack 에는 그 말을 붙이지 않는다.
		String single = "vr-two-single";
		prepare(single);
		assertThat(JsonPath.<String>read(results(single), "$.scenarios[0].mismatch")).doesNotContain("질문 파일이");
	}

	@Test
	void 질문_파일을_읽지_못해도_서버의_경로를_답에_담지_않는다() throws Exception {
		String packId = "vr-unreadable";
		Path pack = prepare(packId);
		Path questions = pack.resolve("questions/S-01.yaml");
		java.util.Set<java.nio.file.attribute.PosixFilePermission> before = Files.getPosixFilePermissions(questions);
		Files.setPosixFilePermissions(questions, java.util.Set.of());
		try {
			org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(questions), "읽기를 막을 수 없는 환경이다 (관리자 권한)");

			String body = results(packId);

			assertThat(JsonPath.<Boolean>read(body, "$.available")).isFalse();
			String reason = JsonPath.read(body, "$.unavailableReason");
			assertThat(reason).contains("S-01.yaml");
			assertThat(reason).doesNotContain(PACKS.toString()).doesNotContain(pack.getParent().getFileName().toString());
		}
		finally {
			Files.setPosixFilePermissions(questions, before);
		}
	}

	@Test
	void 돌려도_자료가_바뀌지_않는다() throws Exception {
		String packId = "vr-readonly";
		Path pack = prepare(packId);
		uploadScenario(packId, pack, Integer.MAX_VALUE);
		List<Long> before = counts(packId);

		results(packId);
		results(packId);

		assertThat(counts(packId)).isEqualTo(before);
		assertThat(before).allMatch(count -> count > 0);
	}

	@Test
	void 질문을_읽을_수_없으면_그렇다고_알린다() throws Exception {
		// 서버의 Pack 폴더에 그 Pack 의 폴더가 없다 (다른 곳에서 적재한 Pack).
		String elsewhere = "vr-elsewhere";
		PackCopies.load(this.dataSource, Files.createTempDirectory("vr-elsewhere"), elsewhere, text -> text);
		String body = results(elsewhere);
		assertThat(JsonPath.<Boolean>read(body, "$.available")).isFalse();
		assertThat(JsonPath.<String>read(body, "$.unavailableReason")).contains("Pack 폴더");
		assertThat(JsonPath.<List<Object>>read(body, "$.scenarios")).isEmpty();

		// 질문 파일이 없다.
		String none = "vr-none";
		Path pack = prepare(none);
		Files.delete(pack.resolve("questions/S-01.yaml"));
		String noneBody = results(none);
		assertThat(JsonPath.<Boolean>read(noneBody, "$.available")).isFalse();
		assertThat(JsonPath.<String>read(noneBody, "$.unavailableReason")).contains("질문");

		// 질문 파일이 깨졌다: 어느 파일인지 알린다.
		String broken = "vr-broken";
		Path brokenPack = prepare(broken);
		Files.writeString(brokenPack.resolve("questions/S-01.yaml"), "scenario: [이것은 시나리오가 아니다\n", StandardCharsets.UTF_8);
		String brokenBody = results(broken);
		assertThat(JsonPath.<Boolean>read(brokenBody, "$.available")).isFalse();
		assertThat(JsonPath.<String>read(brokenBody, "$.unavailableReason")).contains("S-01.yaml");
	}

	@Test
	void 관리자만_부르고_틀린_입력은_거부하며_부른_것은_감사_기록에_한_번_남는다() throws Exception {
		String packId = "vr-roles";
		Path pack = prepare(packId);
		uploadScenario(packId, pack, Integer.MAX_VALUE);

		this.mvc.perform(get("/api/v1/verification-results").param("packId", packId)).andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/verification-results").with(OFFICER).param("packId", packId))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/v1/verification-results").with(ADMIN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("MISSING_PACK_ID")));
		this.mvc.perform(get("/api/v1/verification-results").with(ADMIN).param("packId", "vr-no-such-pack"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code", is("PACK_NOT_FOUND")));

		long mark = this.audit.lastId();
		results(packId);
		// 질문마다 남기지 않는다: 이 요청의 두 줄뿐이다.
		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("getVerificationResults", "getVerificationResults");
		assertThat(rows.get(1).target()).contains(packId);
	}

	// ── 도우미 ────────────────────────────────────────────────

	private String results(String packId) throws Exception {
		return this.mvc.perform(get("/api/v1/verification-results").with(ADMIN).param("packId", packId))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	@Test
	void 단계의_파일이_Pack_폴더_밖을_가리키는_바로가기이면_따라가지_않는다() throws Exception {
		// /security-review 가 짚은 것: 이름은 폴더 안인데 바로가기(심볼릭 링크)가 밖의 파일을 가리킨다.
		String packId = "vr-link";
		Path pack = prepare(packId);
		uploadScenario(packId, pack, Integer.MAX_VALUE);
		// 판이 시나리오와 같다: 여기까지는 돌릴 수 있다.
		assertThat(JsonPath.<Boolean>read(results(packId), "$.scenarios[0].matched")).isTrue();

		// 첫 단계의 파일을 Pack 폴더 밖의 같은 내용의 파일을 가리키는 바로가기로 바꾼다.
		String[] first = steps(pack).get(0);
		Path inside = pack.resolve(first[1]);
		Path outside = Files.createTempFile("tabnit-vr-outside", ".csv");
		Files.write(outside, Files.readAllBytes(inside));
		Files.delete(inside);
		Files.createSymbolicLink(inside, outside);

		String body = results(packId);

		// 내용이 같아도 밖의 파일은 읽지 않는다: 그 단계의 파일이 없는 것으로 보고 묻지 않는다.
		assertThat(JsonPath.<Boolean>read(body, "$.scenarios[0].matched")).as(body).isFalse();
		assertThat(JsonPath.<String>read(body, "$.scenarios[0].mismatch")).contains("서버의 Pack 폴더에 없습니다");
		assertThat(JsonPath.<List<Object>>read(body, "$.scenarios[0].results")).isEmpty();
		assertThat(body).doesNotContain(outside.toString());
	}

	/** 서버의 Pack 폴더에 사본을 두고 API 로 적재한다. */
	private Path prepare(String packId) throws Exception {
		Path pack = PackCopies.copyInto(PACKS, packId, text -> text);
		this.mvc.perform(post(PackLoadsController.PATH).with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"packId\":\"" + packId + "\"}"))
			.andExpect(status().isOk());
		return pack;
	}

	/** 시나리오의 단계: 입력 자료, 파일, 적용일. 파일에 적힌 차례다. */
	private static List<String[]> steps(Path pack) throws IOException {
		List<String[]> steps = new ArrayList<>();
		Matcher step = STEP.matcher(Files.readString(pack.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8));
		while (step.find()) {
			steps.add(new String[] { step.group(1).trim(), step.group(2).trim(), step.group(3) });
		}
		return steps;
	}

	private void uploadScenario(String packId, Path pack, int limit) throws Exception {
		List<String[]> steps = steps(pack);
		assertThat(steps).isNotEmpty();
		for (int i = 0; i < Math.min(limit, steps.size()); i++) {
			String[] step = steps.get(i);
			Path file = pack.resolve(step[1]);
			upload(packId, step[0], step[2], file.getFileName().toString(), Files.readAllBytes(file));
		}
	}

	private void upload(String packId, String inputSource, String effectiveDate, String fileName, byte[] content)
			throws Exception {
		this.mvc.perform(multipart("/api/v1/editions").with(ADMIN).with(csrf()).param("packId", packId)
				.param("inputSourceId", inputSource).param("effectiveDate", effectiveDate)
				.file(new MockMultipartFile("file", fileName, "text/csv", content)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome", is("ACCEPTED")));
	}

	/** 저장소의 예시 Pack 을 다른 식별자의 사본으로 서버의 Pack 폴더에 둔다. 다른 시험과 자료가 섞이지 않게 한다. */
	private static Path copyExample(String source, String packId) throws IOException {
		Path from = Path.of("../../packs", source);
		Path copy = PACKS.resolve(packId);
		try (java.util.stream.Stream<Path> files = Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = copy.resolve(from.relativize(file).toString());
				if (Files.isDirectory(file)) {
					Files.createDirectories(target);
				}
				else if (file.toString().endsWith(".ttl")) {
					Files.writeString(target, Files.readString(file, StandardCharsets.UTF_8).replace(source, packId),
							StandardCharsets.UTF_8);
				}
				else {
					Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		return copy;
	}

	private static int countQuestions(Path pack) throws IOException {
		int count = 0;
		for (String line : Files.readAllLines(pack.resolve("questions/S-01.yaml"), StandardCharsets.UTF_8)) {
			if (line.startsWith("  - id: ")) {
				count++;
			}
		}
		return count;
	}

	/** 개체, 관계, 속성 값, 판, 변동 이벤트, 조치, 확정 이력의 수. */
	private List<Long> counts(String packId) {
		JdbcTemplate jdbc = new JdbcTemplate(this.dataSource);
		List<Long> counts = new ArrayList<>();
		for (String table : List.of("entity", "relation", "attribute_value", "edition", "action")) {
			counts.add(jdbc.queryForObject("select count(*) from " + table + " where pack_id = ?", Long.class, packId));
		}
		counts.add(jdbc.queryForObject(
				"select count(*) from change_event c join edition e on e.id = c.edition_id where e.pack_id = ?", Long.class,
				packId));
		return counts;
	}

	private static Path createPacksDirectory() {
		try {
			return Files.createTempDirectory("tabnit-vr-packs-dir").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException("시험의 Pack 폴더를 만들지 못했습니다", e);
		}
	}
}
