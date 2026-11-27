package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.pack.PackLoader;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-17: 개체를 글자로 찾는 API (docs/api/openapi.yaml 의 searchEntities, D7 4절 "개체 찾기", D2 AD-411).
 *
 * <p>정답 집합은 Pack 의 예시 입력 파일(CSV)에서 이 시험이 따로 계산한다: 제품이 낸 답을 옮겨 적지 않는다.
 * 시험용 Pack 의 사본과 제조 안전 Pack 으로 본다. 모든 이름과 값은 지어낸 것이다.
 *
 * <p>이 계산의 한계: 파일의 첫 칸(키)과 둘째 칸(이름)만 본다. 그래서 검색어는 다른 칸의 값에 맞지 않는 것으로
 * 골랐다 (맞으면 과잉으로 실패한다). 속성 값 전체에서 찾는 것과 유효기간의 경계는 core 의 일반 시험
 * (EntitySearchQueryTest)이 지어낸 자료로 본다. 정답의 id 는 식별자의 규칙(D5 1절)대로 이 시험이 잇는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EntitySearchApiTests {

	/** 시험용 Pack 의 첫 판: 입력 자료, 파일, 그 파일의 행이 만드는 개체 유형. */
	private static final String[][] FIRST = { { "program", "program-v1.csv", "의료사업" },
			{ "guideline", "guideline-v1.csv", "운영지침" }, { "guideline-program", "guideline-program-v1.csv", null },
			{ "partner-health", "partner-health-v1.csv", "보건기관" },
			{ "partner-private", "partner-private-v1.csv", "민간기관" }, { "team", "team-v1.csv", "전담팀" } };

	private static final RequestPostProcessor OFFICER = user("es-officer").roles("OFFICER");

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	AuditLog audit;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).apply(springSecurity()).build();
	}

	// ── 수용 기준: 키, 표시 이름, 속성 값의 일부로 찾는다. 정답 집합과 같다 ─────────────

	@Test
	void 검색어가_키나_이름에_든_개체가_정답_집합과_같다() throws Exception {
		String packId = "es-answer";
		Path pack = load(packId);
		// 정답: 첫 판의 파일마다, 첫 칸(키)이나 둘째 칸(이름)에 검색어가 든 행의 개체. 엔진과 따로 센다.
		// 소문자로 적은 검색어(p-0, g-02)는 영어 대소문자를 가리지 않는 것을 본다.
		for (String q : List.of("방문", "지원", "P-0", "p-0", "G-02", "g-02", "없는 글자")) {
			TreeSet<String> expected = new TreeSet<>();
			for (String[] first : FIRST) {
				if (first[2] == null) {
					continue;
				}
				for (String[] cells : rows(pack.resolve("data/" + first[1]))) {
					if (lower(cells[0]).contains(lower(q)) || lower(cells[1]).contains(lower(q))) {
						expected.add(packId + "|" + first[2] + "|" + cells[0]);
					}
				}
			}

			String body = search(packId).param("q", q).param("asOf", "2026-01-01").andExpect(status().isOk())
				.andExpect(jsonPath("$.packId", is(packId)))
				.andExpect(jsonPath("$.asOf", is("2026-01-01")))
				.andExpect(jsonPath("$.q", is(q)))
				.andExpect(jsonPath("$.total", is(expected.size())))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

			// 누락과 과잉이 0 이고 순서는 id 의 글자 코드 순이다 (이 자료의 id 는 TreeSet 의 순서와 같다).
			List<String> ids = JsonPath.read(body, "$.entities[*].entityId");
			assertThat(ids).as("검색어 " + q).containsExactlyElementsOf(expected);
		}
		assertThat(rows(pack.resolve("data/program-v1.csv"))).as("정답을 세는 자료가 비어 있으면 이 시험에 뜻이 없다").isNotEmpty();
	}

	@Test
	void 찾은_개체는_유형과_이름표와_키와_조회_시점의_표시_이름과_함께_온다() throws Exception {
		String packId = "es-shape";
		Path pack = load(packId);
		String[] team = rows(pack.resolve("data/team-v1.csv")).get(0);

		search(packId).param("q", team[1]).param("asOf", "2026-01-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.entities", hasSize(1)))
			.andExpect(jsonPath("$.entities[0].entityId", is(packId + "|전담팀|" + team[0])))
			.andExpect(jsonPath("$.entities[0].entityType", is("전담팀")))
			.andExpect(jsonPath("$.entities[0].entityTypeLabel", is("전담팀")))
			.andExpect(jsonPath("$.entities[0].entityKey", is(team[0])))
			.andExpect(jsonPath("$.entities[0].displayName", is(team[1])));
		// 자료가 들어오기 전의 날짜: 이름으로는 찾지 못하고 키로는 찾는다. 표시 이름은 없다.
		search(packId).param("q", team[1]).param("asOf", "2025-12-31").andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(0)));
		search(packId).param("q", team[0]).param("entityType", "전담팀").param("asOf", "2025-12-31")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.entities", hasSize(1)))
			.andExpect(jsonPath("$.entities[0].displayName", nullValue()));
	}

	// ── 수용 기준: 유형으로 거른다 ──────────────────────────────────

	@Test
	void 유형으로_거르고_상위_유형을_주면_하위_유형의_개체도_나온다() throws Exception {
		String packId = "es-type";
		Path pack = load(packId);
		TreeSet<String> health = new TreeSet<>();
		rows(pack.resolve("data/partner-health-v1.csv")).forEach(cells -> health.add(packId + "|보건기관|" + cells[0]));
		TreeSet<String> partners = new TreeSet<>(health);
		rows(pack.resolve("data/partner-private-v1.csv")).forEach(cells -> partners.add(packId + "|민간기관|" + cells[0]));
		TreeSet<String> teamsAndHealth = new TreeSet<>(health);
		rows(pack.resolve("data/team-v1.csv")).forEach(cells -> teamsAndHealth.add(packId + "|전담팀|" + cells[0]));

		assertThat(ids(search(packId).param("entityType", "보건기관"))).containsExactlyElementsOf(health);
		// 협력기관은 보건기관과 민간기관의 상위 유형이다.
		assertThat(ids(search(packId).param("entityType", "협력기관"))).containsExactlyElementsOf(partners);
		assertThat(partners).as("상위 유형으로 거른 것이 더 많아야 이 시험에 뜻이 있다").hasSizeGreaterThan(health.size());
		assertThat(ids(search(packId).param("entityType", "전담팀").param("entityType", "보건기관")))
			.containsExactlyElementsOf(teamsAndHealth);
		// 검색어와 함께: 둘 다 맞아야 한다.
		String key = health.first().substring(health.first().lastIndexOf('|') + 1);
		assertThat(ids(search(packId).param("q", key).param("entityType", "전담팀"))).isEmpty();
		assertThat(ids(search(packId).param("q", key).param("entityType", "협력기관"))).containsExactly(health.first());
	}

	@Test
	void 제조_안전_Pack_에서도_유형과_속성_값으로_거른_것이_정답_집합과_같다() throws Exception {
		// 처음 보는 Pack 에서 한 번 더 본다. 설비의 파일 하나를 올린다 (소속 공정은 참조로 만들어진다).
		// 다른 식별자의 사본으로 적재한다: 같은 저장소를 쓰는 다른 시험이 이 Pack 을 건드려도 섞이지 않는다.
		String packId = "es-manufacturing";
		Path pack = copyExample("manufacturing-safety", packId);
		new PackLoader(this.dataSource).load(pack, Instant.parse("2026-07-01T00:00:00Z"));
		Path file = pack.resolve("data/equipment-v1.csv");
		upload(packId, "equipment", "2026-01-01", file).andExpect(status().isOk());
		List<String[]> rows = rows(file);
		// 검색어는 자료에서 고른다: 첫 행의 둘째 칸(설비의 종류)의 값.
		String q = rows.get(0)[1];
		TreeSet<String> expected = new TreeSet<>();
		for (String[] cells : rows) {
			if (cells[0].contains(q) || cells[1].contains(q) || cells[2].contains(q)) {
				expected.add(packId + "|공정설비|" + cells[0]);
			}
		}
		assertThat(expected).as("맞는 것과 맞지 않는 것이 함께 있어야 이 시험에 뜻이 있다").isNotEmpty()
			.hasSizeLessThan(rows.size());

		// 나눠 받아 잇는다.
		List<String> found = new ArrayList<>();
		for (int offset = 0; offset < expected.size(); offset += 200) {
			String body = search(packId).param("q", q).param("entityType", "공정설비")
				.param("asOf", "2026-01-01").param("offset", String.valueOf(offset)).param("limit", "200")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total", is(expected.size())))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			found.addAll(JsonPath.<List<String>>read(body, "$.entities[*].entityId"));
		}
		assertThat(new TreeSet<>(found)).isEqualTo(expected);
		assertThat(found).doesNotHaveDuplicates().hasSize(expected.size());
	}

	// ── 수용 기준: 나눠 주고 전체 수를 준다 ─────────────────────────────

	@Test
	void 검색어가_없으면_그_Pack_의_개체_전부를_나눠_준다() throws Exception {
		String packId = "es-all";
		load(packId);

		String whole = search(packId).param("asOf", "2026-01-01").param("limit", "200").andExpect(status().isOk())
			.andExpect(jsonPath("$.q", is("")))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		List<String> all = JsonPath.read(whole, "$.entities[*].entityId");
		int total = JsonPath.read(whole, "$.total");
		assertThat(all).hasSize(total).hasSizeGreaterThan(4).isSorted();

		List<String> joined = new ArrayList<>();
		for (int offset = 0; offset < total; offset += 3) {
			String body = search(packId).param("asOf", "2026-01-01").param("offset", String.valueOf(offset)).param("limit", "3")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total", is(total)))
				.andExpect(jsonPath("$.offset", is(offset)))
				.andExpect(jsonPath("$.limit", is(3)))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			joined.addAll(JsonPath.<List<String>>read(body, "$.entities[*].entityId"));
		}
		assertThat(joined).containsExactlyElementsOf(all);
		// 끝을 넘으면 빈 목록이고 전체 수는 그대로다. 빈 검색어도 없는 것과 같다.
		search(packId).param("asOf", "2026-01-01").param("offset", String.valueOf(total)).andExpect(status().isOk())
			.andExpect(jsonPath("$.entities", hasSize(0)))
			.andExpect(jsonPath("$.total", is(total)));
		search(packId).param("q", "").param("asOf", "2026-01-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(total)));
	}

	// ── 수용 기준: 틀린 입력 ────────────────────────────────────

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "es-bad";
		load(packId);

		problem(this.mvc.perform(get("/api/v1/entities").with(OFFICER)), 400, "MISSING_PACK_ID", "packId");
		problem(search("es-bad-없는-Pack"), 404, "PACK_NOT_FOUND", "packId");
		problem(search(packId).param("q", "가".repeat(201)), 400, "INVALID_QUERY", "q");
		problem(search(packId).param("q", "가\u0000"), 400, "INVALID_QUERY", "q");
		search(packId).param("q", "가".repeat(200)).andExpect(status().isOk());
		problem(search(packId).param("entityType", "없는유형"), 400, "UNKNOWN_ENTITY_TYPE", "entityType");
		problem(search(packId).param("entityType", ""), 400, "UNKNOWN_ENTITY_TYPE", "entityType");
		problem(search(packId).param("entityType", "전담팀").param("entityType", "없는유형"), 400, "UNKNOWN_ENTITY_TYPE",
				"entityType");
		problem(search(packId).param("asOf", "2026-13-01"), 400, "INVALID_AS_OF", "asOf");
		problem(search(packId).param("offset", "-1"), 400, "INVALID_OFFSET", "offset");
		problem(search(packId).param("limit", "201"), 400, "INVALID_LIMIT", "limit");
		// 특수 글자는 글자 그대로다: 오류가 아니고, 아무 글자나 뜻하지 않는다.
		search(packId).param("q", "%").param("asOf", "2026-01-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(0)));
		search(packId).param("q", "_").param("asOf", "2026-01-01").andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(0)));
	}

	// ── 수용 기준: 조회 시점이 없으면 서버가 정해 답에 적는다. 로그인해야 한다. 감사 기록에 남는다 ──────

	@Test
	void 조회_시점이_없으면_서버가_정한_날짜를_답에_적는다() throws Exception {
		String packId = "es-today";
		load(packId);

		String body = search(packId).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<String>read(body, "$.asOf")).matches("\\d{4}-\\d{2}-\\d{2}");
	}

	@Test
	void 로그인하지_않으면_찾을_수_없고_찾은_것은_감사_기록에_남는다() throws Exception {
		String packId = "es-audit";
		load(packId);

		this.mvc.perform(get("/api/v1/entities").param("packId", packId)).andExpect(status().isUnauthorized());

		long mark = this.audit.lastId();
		search(packId).param("q", "방문").param("asOf", "2026-01-01").andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(AuditRecord::action).containsOnly("searchEntities");
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("es-officer");
		assertThat(rows.get(1).target()).contains(packId).contains("방문");
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.SUCCESS);
	}

	@Test
	void 거부된_검색도_감사_기록에_조회_시점이_남는다() throws Exception {
		String packId = "es-audit-rejected";
		load(packId);

		// 없는 Pack (404) 과 없는 유형 (400): 날짜는 이미 읽었다. 거부의 줄에도 그 날짜가 있어야 한다.
		long mark = this.audit.lastId();
		search("es-no-such-pack").param("asOf", "2026-03-01").andExpect(status().isNotFound());
		search(packId).param("entityType", "없는유형").param("asOf", "2026-04-01").andExpect(status().isBadRequest());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).hasSize(4);
		assertThat(rows.get(1).outcome()).isNotEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(1).asOf()).isEqualTo(java.time.LocalDate.parse("2026-03-01"));
		assertThat(rows.get(3).outcome()).isNotEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(3).asOf()).isEqualTo(java.time.LocalDate.parse("2026-04-01"));
	}

	/** 저장소의 예시 Pack 을 다른 식별자의 사본으로 만든다. 폴더의 이름이 Pack 의 식별자와 같아야 한다. */
	private Path copyExample(String source, String packId) throws java.io.IOException {
		Path from = Path.of("../../packs", source);
		Path copy = this.temp.resolve(String.valueOf(System.nanoTime())).resolve(packId);
		try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(from)) {
			for (Path file : files.toList()) {
				Path target = copy.resolve(from.relativize(file).toString());
				if (java.nio.file.Files.isDirectory(file)) {
					java.nio.file.Files.createDirectories(target);
				}
				else if (file.toString().endsWith(".ttl")) {
					java.nio.file.Files.writeString(target,
							java.nio.file.Files.readString(file, StandardCharsets.UTF_8).replace(source, packId),
							StandardCharsets.UTF_8);
				}
				else {
					java.nio.file.Files.copy(file, target);
				}
			}
		}
		return copy;
	}

	private MockHttpServletRequestBuilderHolder search(String packId) {
		return new MockHttpServletRequestBuilderHolder(get("/api/v1/entities").with(OFFICER).param("packId", packId));
	}

	/** 요청을 이어 적다가 보내는 작은 도우미. */
	private final class MockHttpServletRequestBuilderHolder {

		private final MockHttpServletRequestBuilder request;

		MockHttpServletRequestBuilderHolder(MockHttpServletRequestBuilder request) {
			this.request = request;
		}

		MockHttpServletRequestBuilderHolder param(String name, String value) {
			this.request.param(name, value);
			return this;
		}

		ResultActions andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
			return EntitySearchApiTests.this.mvc.perform(this.request).andExpect(matcher);
		}
	}

	private List<String> ids(MockHttpServletRequestBuilderHolder request) throws Exception {
		String body = request.param("asOf", "2026-01-01").param("limit", "200").andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsString(StandardCharsets.UTF_8);
		return JsonPath.read(body, "$.entities[*].entityId");
	}

	private static void problem(ResultActions result, int status, String code, String field) throws Exception {
		result.andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code))).andExpect(jsonPath("$.field", is(field)));
	}

	private void problem(MockHttpServletRequestBuilderHolder request, int status, String code, String field)
			throws Exception {
		request.andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code))).andExpect(jsonPath("$.field", is(field)));
	}

	private Path load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		return pack;
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").with(OFFICER)
			.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
			.param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))));
	}

	/** 영어 대문자만 소문자로 바꾼다. 이 시험이 쓰는 자료의 영어 글자는 A ~ Z 뿐이다. */
	private static String lower(String text) {
		StringBuilder folded = new StringBuilder();
		text.chars().forEach(c -> folded.append((char) (c >= 'A' && c <= 'Z' ? c + 32 : c)));
		return folded.toString();
	}

	/** 파일의 자료 행을 쉼표로 나눈다. 첫 칸과 둘째 칸에는 따옴표와 쉼표가 없는 자료만 이렇게 읽는다. */
	private static List<String[]> rows(Path file) throws Exception {
		List<String[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8).stream().skip(1).toList()) {
			if (!line.isBlank()) {
				rows.add(line.split(",", -1));
			}
		}
		return rows;
	}
}
