package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditOutcome;
import com.altumlab.tabnit.common.AuditRecord;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-21: 목록을 파일로 내보내는 API (docs/api/openapi.yaml 의 exportRelations, exportActions, exportImpactTargets,
 * D7 "목록 내보내기", D2 AD-412).
 *
 * <p>수용 기준의 가운데는 "파일의 행 수가 화면의 전체 건수와 같다"다: 전체 건수는 목록 API 가 준 total 이고,
 * 파일의 행은 이 시험이 따로 센다. 순서는 목록 API 를 나눠 받아 이은 것과 견준다. 시험용 Pack 의 사본을 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ListExportsApiTests {

	private static final String[][] EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" },
			{ "guideline-program", "guideline-program-v1.csv", "2026-01-01" },
			{ "partner-health", "partner-health-v1.csv", "2026-01-01" },
			{ "partner-private", "partner-private-v1.csv", "2026-01-01" }, { "team", "team-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline-program", "guideline-program-v2.csv", "2026-03-01" } };

	private static final RequestPostProcessor OFFICER = user("ex-officer").roles("OFFICER");

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

	// ── 관계 ─────────────────────────────────────────────────

	@Test
	void 관계의_CSV_는_행의_수가_목록의_전체_수와_같고_순서가_목록과_같다() throws Exception {
		String packId = "ex-relations";
		load(packId);
		String entityId = packId + "|의료사업|P-01";
		List<String> listed = listedIds("/api/v1/relations?entityId=" + entityId + "&asOf=2026-02-01", "relations");
		assertThat(listed).as("관계가 둘 이상이어야 이 시험에 뜻이 있다").hasSizeGreaterThan(1);

		MockHttpServletResponse response = this.mvc
			.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", entityId).param("asOf", "2026-02-01")
				.param("format", "csv"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"relations-2026-02-01.csv\""))
			.andReturn().getResponse();

		List<List<String>> rows = csv(response.getContentAsByteArray());
		List<String> header = rows.get(0);
		List<List<String>> data = rows.subList(1, rows.size());
		// 파일의 행 수 = 목록의 전체 수.
		assertThat(data).hasSize(listed.size());
		assertThat(data).extracting(row -> row.get(header.indexOf("관계 id"))).containsExactlyElementsOf(listed);
		// 조회 시점이 모든 행에 적힌다.
		assertThat(data).extracting(row -> row.get(header.indexOf("조회 시점"))).containsOnly("2026-02-01");
		assertThat(data).allSatisfy(row -> assertThat(row).hasSameSizeAs(header));
		// 개체는 유형의 이름표, 키, 이름으로도 적힌다.
		assertThat(data).extracting(row -> row.get(header.indexOf("출발 개체 id")) + row.get(header.indexOf("도착 개체 id")))
			.allSatisfy(ends -> assertThat(ends).contains(entityId));
		assertThat(header).contains("출발 유형", "출발 키", "출발 이름", "도착 유형", "도착 키", "도착 이름", "관계 유형", "시작일",
				"종료일", "입력 자료", "판");
	}

	@Test
	void 두_번_받은_파일은_글자까지_같다() throws Exception {
		String packId = "ex-twice";
		load(packId);
		String url = "/api/v1/relation-exports?entityId=" + packId + "|의료사업|P-01&asOf=2026-02-01&format=";
		for (String format : List.of("csv", "json")) {
			byte[] first = this.mvc.perform(get(url + format).with(OFFICER)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsByteArray();
			byte[] second = this.mvc.perform(get(url + format).with(OFFICER)).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsByteArray();
			assertThat(second).as(format).isEqualTo(first);
		}
	}

	@Test
	void 관계의_JSON_은_목록의_답과_같은_모양에_전체를_담는다() throws Exception {
		String packId = "ex-json";
		load(packId);
		String entityId = packId + "|의료사업|P-01";
		List<String> listed = listedIds("/api/v1/relations?entityId=" + entityId + "&asOf=2026-02-01", "relations");

		String body = this.mvc
			.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", entityId).param("asOf", "2026-02-01")
				.param("format", "json"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"relations-2026-02-01.json\""))
			.andExpect(jsonPath("$.asOf", is("2026-02-01")))
			.andExpect(jsonPath("$.entityId", is(entityId)))
			.andExpect(jsonPath("$.total", is(listed.size())))
			.andExpect(jsonPath("$.offset", is(0)))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		assertThat(JsonPath.<List<String>>read(body, "$.relations[*].id")).containsExactlyElementsOf(listed);
	}

	@Test
	void 관계가_없는_개체는_머리_행만_있는_파일이다() throws Exception {
		String packId = "ex-empty";
		load(packId);
		// 자료가 들어오기 전의 날짜에는 유효한 관계가 없다.
		byte[] file = this.mvc
			.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", packId + "|의료사업|P-01")
				.param("asOf", "2025-12-31").param("format", "csv"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();

		assertThat(csv(file)).hasSize(1);
	}

	@Test
	void 조회_시점을_비우면_서버가_정한_날짜가_파일과_이름에_적힌다() throws Exception {
		String packId = "ex-today";
		load(packId);

		MockHttpServletResponse response = this.mvc
			.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", packId + "|의료사업|P-01")
				.param("format", "json"))
			.andExpect(status().isOk()).andReturn().getResponse();

		String asOf = JsonPath.read(response.getContentAsString(StandardCharsets.UTF_8), "$.asOf");
		assertThat(asOf).matches("\\d{4}-\\d{2}-\\d{2}");
		assertThat(response.getHeader("Content-Disposition")).contains("relations-" + asOf + ".json");
	}

	// ── 조치 ─────────────────────────────────────────────────

	@Test
	void 조치의_파일은_행의_수가_목록의_전체_수와_같고_거르는_인자도_목록과_같다() throws Exception {
		String packId = "ex-actions";
		load(packId);
		List<String> listed = listedIds("/api/v1/actions?packId=" + packId, "actions");
		assertThat(listed).as("조치가 있어야 이 시험에 뜻이 있다").isNotEmpty();

		MockHttpServletResponse response = this.mvc
			.perform(get("/api/v1/action-exports").with(OFFICER).param("packId", packId).param("format", "csv"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", "attachment; filename=\"actions.csv\""))
			.andReturn().getResponse();
		List<List<String>> rows = csv(response.getContentAsByteArray());
		List<String> header = rows.get(0);
		List<List<String>> data = rows.subList(1, rows.size());

		assertThat(data).hasSize(listed.size());
		assertThat(data).extracting(row -> row.get(header.indexOf("조치 id"))).containsExactlyElementsOf(listed);
		assertThat(header).contains("규칙 번호", "할 일", "우선순위", "기한", "상태", "대상 개체 id", "대상 이름", "근거 개체 id",
				"근거 입력 자료", "근거 판", "근거 판의 적용일");
		assertThat(data).extracting(row -> row.get(header.indexOf("기한"))).allMatch(due -> due.matches("\\d{4}-\\d{2}-\\d{2}"));

		// 상태와 변동으로 거르면 목록도 파일도 같이 준다.
		String changeEventId = data.get(0).get(header.indexOf("변동 이벤트 id"));
		List<String> filtered = listedIds("/api/v1/actions?packId=" + packId + "&status=PENDING&changeEventId=" + changeEventId,
				"actions");
		String json = this.mvc
			.perform(get("/api/v1/action-exports").with(OFFICER).param("packId", packId).param("status", "PENDING")
				.param("changeEventId", changeEventId).param("format", "json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(filtered.size())))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(JsonPath.<List<String>>read(json, "$.actions[*].id")).containsExactlyElementsOf(filtered);
		// 맞는 것이 없으면 머리 행만 있다.
		byte[] none = this.mvc
			.perform(get("/api/v1/action-exports").with(OFFICER).param("packId", packId).param("status", "CONFIRMED")
				.param("format", "csv"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		assertThat(csv(none)).hasSize(1);
	}

	// ── 영향 대상 ───────────────────────────────────────────────

	@Test
	void 영향_대상의_파일은_행의_수가_영향_범위의_전체_수와_같고_적용일이_적힌다() throws Exception {
		String packId = "ex-impact";
		load(packId);
		String actions = this.mvc.perform(get("/api/v1/actions").with(OFFICER).param("packId", packId))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		String changeEventId = JsonPath.read(actions, "$.actions[0].changeEventId");
		String scope = this.mvc.perform(get("/api/v1/impact-scopes").with(OFFICER).param("changeEventId", changeEventId)
				.param("limit", "200"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		int total = JsonPath.read(scope, "$.total");
		List<String> listed = JsonPath.read(scope, "$.targets[*].entityId");
		String effective = JsonPath.read(scope, "$.asOfEffective");
		assertThat(listed).hasSize(total).isNotEmpty();

		MockHttpServletResponse response = this.mvc
			.perform(get("/api/v1/impact-target-exports").with(OFFICER).param("changeEventId", changeEventId)
				.param("format", "csv"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition",
					"attachment; filename=\"impact-targets-" + effective + ".csv\""))
			.andReturn().getResponse();
		List<List<String>> rows = csv(response.getContentAsByteArray());
		List<String> header = rows.get(0);
		List<List<String>> data = rows.subList(1, rows.size());

		assertThat(data).hasSize(total);
		assertThat(data).extracting(row -> row.get(header.indexOf("영향 대상 id"))).containsExactlyElementsOf(listed);
		assertThat(data).extracting(row -> row.get(header.indexOf("적용일"))).containsOnly(effective);
		assertThat(data).extracting(row -> row.get(header.indexOf("변동 이벤트 id"))).containsOnly(changeEventId);
		// 근거 경로가 행마다 적힌다: 근거 없는 영향 대상을 내보내지 않는다.
		assertThat(data).extracting(row -> row.get(header.indexOf("근거 경로"))).allMatch(path -> !path.isBlank());
		// 최대 깊이와, 거기서 잘렸는지가 행마다 적힌다 (D2 AD-425): 잘린 영향 범위의 파일이 전체 목록으로 읽히지 않는다.
		int maxDepth = JsonPath.read(scope, "$.maxDepth");
		boolean truncated = JsonPath.read(scope, "$.truncated");
		assertThat(header).endsWith("최대 깊이", "최대 깊이에서 잘림");
		assertThat(data).extracting(row -> row.get(header.indexOf("최대 깊이"))).containsOnly(String.valueOf(maxDepth));
		assertThat(data).extracting(row -> row.get(header.indexOf("최대 깊이에서 잘림"))).containsOnly(truncated ? "예" : "아니오");

		String json = this.mvc
			.perform(get("/api/v1/impact-target-exports").with(OFFICER).param("changeEventId", changeEventId)
				.param("format", "json"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total", is(total)))
			.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(JsonPath.<List<String>>read(json, "$.targets[*].entityId")).containsExactlyElementsOf(listed);
	}

	// ── 틀린 입력, 로그인, 감사 기록 ─────────────────────────────────────

	@Test
	void 틀린_입력은_목록의_API_와_같은_코드로_거부하고_형식이_틀리면_알린다() throws Exception {
		String packId = "ex-bad";
		load(packId);
		String entityId = packId + "|의료사업|P-01";

		problem("/api/v1/relation-exports?entityId=" + entityId, 400, "INVALID_FORMAT");
		problem("/api/v1/relation-exports?entityId=" + entityId + "&format=xlsx", 400, "INVALID_FORMAT");
		problem("/api/v1/relation-exports?format=csv", 400, "MISSING_ENTITY_ID");
		problem("/api/v1/relation-exports?entityId=" + packId + "|의료사업|없는-키&format=csv", 404, "ENTITY_NOT_FOUND");
		problem("/api/v1/relation-exports?entityId=" + entityId + "&asOf=2026-13-01&format=csv", 400, "INVALID_AS_OF");
		problem("/api/v1/action-exports?format=csv", 400, "MISSING_PACK_ID");
		problem("/api/v1/action-exports?packId=ex-bad-없는-Pack&format=csv", 404, "PACK_NOT_FOUND");
		problem("/api/v1/action-exports?packId=" + packId + "&status=DONE&format=csv", 400, "INVALID_STATUS");
		problem("/api/v1/action-exports?packId=" + packId, 400, "INVALID_FORMAT");
		problem("/api/v1/impact-target-exports?format=csv", 400, "MISSING_CHANGE_EVENT_ID");
		problem("/api/v1/impact-target-exports?changeEventId=없는-변동&format=csv", 404, "IMPACT_SCOPE_NOT_FOUND");
		problem("/api/v1/impact-target-exports?changeEventId=없는-변동", 400, "INVALID_FORMAT");
	}

	@Test
	void 로그인하지_않으면_받을_수_없고_받은_것은_감사_기록에_남는다() throws Exception {
		String packId = "ex-audit";
		load(packId);
		String entityId = packId + "|의료사업|P-01";

		this.mvc.perform(get("/api/v1/relation-exports").param("entityId", entityId).param("format", "csv"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(get("/api/v1/action-exports").param("packId", packId).param("format", "csv"))
			.andExpect(status().isUnauthorized());

		long mark = this.audit.lastId();
		this.mvc.perform(get("/api/v1/relation-exports").with(OFFICER).param("entityId", entityId)
				.param("asOf", "2026-02-01").param("format", "csv"))
			.andExpect(status().isOk());
		this.mvc.perform(get("/api/v1/action-exports").with(OFFICER).param("packId", packId).param("format", "json"))
			.andExpect(status().isOk());

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action)
			.containsExactly("exportRelations", "exportRelations", "exportActions", "exportActions");
		assertThat(rows.get(1).outcome()).isEqualTo(AuditOutcome.SUCCESS);
		assertThat(rows.get(1).target()).contains(entityId).contains("csv");
		assertThat(rows.get(1).asOf()).hasToString("2026-02-01");
		assertThat(rows.get(3).target()).contains(packId).contains("json");
		assertThat(rows).extracting(AuditRecord::actor).containsOnly("ex-officer");
	}

	// ── 도우미 ────────────────────────────────────────────────

	/** 목록 API 를 끝까지 나눠 받아 이은 id. 전체 수와 같은지도 본다. */
	private List<String> listedIds(String url, String items) throws Exception {
		List<String> ids = new ArrayList<>();
		int total = -1;
		for (int offset = 0; total < 0 || offset < total; offset += 2) {
			String body = this.mvc.perform(get(url + "&offset=" + offset + "&limit=2").with(OFFICER))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
			total = JsonPath.read(body, "$.total");
			ids.addAll(JsonPath.<List<String>>read(body, "$." + items + "[*].id"));
		}
		assertThat(ids).hasSize(total);
		return ids;
	}

	private void problem(String url, int status, String code) throws Exception {
		this.mvc.perform(get(url).with(OFFICER)).andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code)));
	}

	private void load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			Path file = pack.resolve("data/" + edition[1]);
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", edition[0]).param("effectiveDate", edition[2])
					.file(new MockMultipartFile("file", edition[1], "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
		}
	}

	/** CSV 를 읽는다 (RFC 4180: 따옴표로 싼 칸, 둘로 적은 따옴표, 칸 안의 줄바꿈). 맨 앞의 BOM 이 있어야 한다. */
	static List<List<String>> csv(byte[] file) {
		assertThat(file.length).isGreaterThan(3);
		assertThat(new byte[] { file[0], file[1], file[2] }).as("BOM").containsExactly(0xEF, 0xBB, 0xBF);
		String text = new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				}
				else if (c == '"') {
					quoted = false;
				}
				else {
					cell.append(c);
				}
			}
			else if (c == '"') {
				quoted = true;
			}
			else if (c == ',') {
				row.add(cell.toString());
				cell.setLength(0);
			}
			else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
				row.add(cell.toString());
				cell.setLength(0);
				rows.add(row);
				row = new ArrayList<>();
				i++;
			}
			else {
				cell.append(c);
			}
		}
		assertThat(quoted).as("따옴표가 닫혀 있어야 한다").isFalse();
		assertThat(cell).as("파일은 줄 끝으로 끝나야 한다").isEmpty();
		assertThat(row).isEmpty();
		return rows;
	}
}
