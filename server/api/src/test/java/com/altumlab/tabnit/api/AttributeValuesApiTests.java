package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.altumlab.tabnit.common.AuditLog;
import com.altumlab.tabnit.common.AuditRecord;
import com.altumlab.tabnit.core.AttributeValueStore;
import com.altumlab.tabnit.core.EntityRef;
import com.altumlab.tabnit.core.NewAttributeValue;
import com.altumlab.tabnit.core.Provenance;
import com.altumlab.tabnit.core.RelationStore;
import com.altumlab.tabnit.core.ValidityPeriod;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A-19: 개체의 속성 값을 조회 시점으로 읽는 API (docs/api/openapi.yaml 의 getAttributeValuesAsOf, D7 4절 "속성 값의 시점
 * 조회", D2 AD-420).
 *
 * <p>정답은 이 시험이 예시 입력 파일(CSV)에서 따로 읽는다: 그 날짜에 적용 중인 판의 파일에서 그 개체의 행의 칸이다.
 * 제품의 조회를 쓰지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AttributeValuesApiTests {

	/** 운영지침의 판 셋. 적용일의 차례다. */
	private static final String[][] GUIDELINES = { { "guideline-v1.csv", "2026-01-01" }, { "guideline-v2.csv", "2026-03-01" },
			{ "guideline-v3.csv", "2026-05-01" } };

	private static final RequestPostProcessor OFFICER = user("av-officer").roles("OFFICER");

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

	@Test
	void 조회_시점마다의_값이_예시_입력_파일에서_따로_읽은_정답과_같다() throws Exception {
		String packId = "av-golden";
		Path pack = load(packId);
		// 파일마다: 키(첫 칸) → 나머지 칸의 값.
		List<Map<String, List<String>>> files = new ArrayList<>();
		for (String[] edition : GUIDELINES) {
			files.add(rows(pack.resolve("data/" + edition[0])));
		}
		TreeMap<String, Boolean> keys = new TreeMap<>();
		files.forEach(file -> file.keySet().forEach(key -> keys.put(key, true)));
		int differing = 0;
		for (String key : keys.keySet()) {
			String entityId = packId + "|운영지침|" + key;
			List<List<String>> seen = new ArrayList<>();
			// 판마다 적용일 당일, 그리고 다음 판의 적용일 전날 (경계).
			for (int i = 0; i < GUIDELINES.length; i++) {
				List<String> expected = files.get(i).getOrDefault(key, List.of()).stream().sorted().toList();
				List<String> days = new ArrayList<>(List.of(GUIDELINES[i][1]));
				if (i + 1 < GUIDELINES.length) {
					days.add(LocalDate.parse(GUIDELINES[i + 1][1]).minusDays(1).toString());
				}
				for (String day : days) {
					// 그 개체가 아직 들어오지 않은 날짜에도 개체는 있다 (개체에는 유효기간이 없다, D2 AD-218): 값이 없을 뿐이다.
					String body = body(entityId, day);
					assertThat(JsonPath.<List<String>>read(body, "$.values[*].value").stream().sorted().toList())
						.as(key + " " + day)
						.isEqualTo(expected);
					seen.add(expected);
				}
			}
			if (seen.stream().distinct().count() > 1) {
				differing++;
			}
		}
		assertThat(differing).as("두 조회 시점에서 값이 다른 개체가 있어야 이 시험에 뜻이 있다").isGreaterThan(0);
	}

	@Test
	void 값마다_이름표와_유효기간과_출처가_있고_속성의_이름_순이며_두_번_물은_답이_같다() throws Exception {
		String packId = "av-shape";
		load(packId);
		String entityId = packId + "|운영지침|G-01";

		String body = body(entityId, "2026-03-01");

		List<String> names = JsonPath.read(body, "$.values[*].attributeName");
		assertThat(names).hasSize(2).isSorted();
		assertThat(JsonPath.<String>read(body, "$.asOf")).isEqualTo("2026-03-01");
		assertThat(JsonPath.<String>read(body, "$.entityId")).isEqualTo(entityId);
		assertThat(JsonPath.<String>read(body, "$.entity.entityKey")).isEqualTo("G-01");
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].attributeLabel")).doesNotContainNull();
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].provenance.inputSourceId")).containsOnly("guideline");
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].provenance.inputSourceLabel")).doesNotContainNull();
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].recordedAt")).doesNotContainNull();
		// 둘째 판에서 바뀐 값은 그 판의 적용일에 시작하고, 그대로인 값은 첫 판의 적용일에 시작한 것이 이어진다.
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].validFrom")).containsExactlyInAnyOrder("2026-01-01", "2026-03-01");
		assertThat(body(entityId, "2026-03-01")).isEqualTo(body);
		// 참·거짓이 아닌 값의 보일 글자는 값과 같다.
		assertThat(JsonPath.<List<String>>read(body, "$.values[*].valueDisplay"))
			.isEqualTo(JsonPath.<List<String>>read(body, "$.values[*].value"));
	}

	@Test
	void 유효기간의_경계에서_시작일은_넣고_종료일은_빼며_값이_없으면_빈_목록이다() throws Exception {
		String packId = "av-boundary";
		load(packId);
		// 지어낸 개체에 값을 바로 넣는다: 3월 1일부터 6월 1일 전까지 "가", 6월 1일부터 "나". 다른 속성은 6월 1일에 끝난다.
		RelationStore store = new RelationStore(this.dataSource);
		Instant at = Instant.parse("2026-01-01T00:00:00Z");
		EntityRef made = new EntityRef(packId, "운영지침", "made-1");
		store.saveEntity(made, at);
		Provenance source = new Provenance("made", 1);
		AttributeValueStore values = new AttributeValueStore(this.dataSource);
		values.saveAttributeValue(new NewAttributeValue(made, "지침제목", "가",
				new ValidityPeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-06-01")), source), at);
		values.saveAttributeValue(new NewAttributeValue(made, "지침제목", "나",
				new ValidityPeriod(LocalDate.parse("2026-06-01"), null), source), at);
		values.saveAttributeValue(new NewAttributeValue(made, "점검주기일", "30",
				new ValidityPeriod(LocalDate.parse("2026-03-01"), LocalDate.parse("2026-06-01")), source), at);

		// 시작일 전날: 값이 없다. 개체는 있으므로 빈 목록이다.
		this.mvc.perform(get("/api/v1/attribute-values").with(OFFICER).param("entityId", made.id()).param("asOf", "2026-02-28"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.values", hasSize(0)))
			.andExpect(jsonPath("$.entity.entityKey", is("made-1")));
		// 시작일 당일과 종료일 전날: 앞의 값 둘.
		for (String day : List.of("2026-03-01", "2026-05-31")) {
			String body = body(made.id(), day);
			assertThat(JsonPath.<List<String>>read(body, "$.values[*].attributeName")).as(day)
				.containsExactly("점검주기일", "지침제목");
			assertThat(JsonPath.<List<String>>read(body, "$.values[*].value")).as(day).containsExactly("30", "가");
			assertThat(JsonPath.<List<String>>read(body, "$.values[*].validTo")).as(day).containsExactly("2026-06-01", "2026-06-01");
		}
		// 종료일 당일: 끝난 값은 없고 새 값만 있다. 끝난 속성은 목록에 없다.
		String endDay = body(made.id(), "2026-06-01");
		assertThat(JsonPath.<List<String>>read(endDay, "$.values[*].value")).containsExactly("나");
		assertThat(JsonPath.<List<Object>>read(endDay, "$.values[*].validTo")).containsExactly((Object) null);
		// 먼 뒷날: 종료일이 없는 값은 계속 유효하다.
		assertThat(JsonPath.<List<String>>read(body(made.id(), "2031-01-01"), "$.values[*].value")).containsExactly("나");
	}

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "av-bad";
		load(packId);
		String entityId = packId + "|운영지침|G-01";

		problem(get("/api/v1/attribute-values"), 400, "MISSING_ENTITY_ID");
		problem(get("/api/v1/attribute-values").param("entityId", "av\u0000bad"), 400, "INVALID_ENTITY_ID");
		problem(get("/api/v1/attribute-values").param("entityId", entityId).param("asOf", "2026-02-30"), 400, "INVALID_AS_OF");
		problem(get("/api/v1/attribute-values").param("entityId", entityId).param("asOf", "10000-01-01"), 400, "INVALID_AS_OF");
		problem(get("/api/v1/attribute-values").param("entityId", packId + "|운영지침|no-such"), 404, "ENTITY_NOT_FOUND");
		// 조회 시점을 비우면 오늘로 답하고 그 날짜를 알린다.
		this.mvc.perform(get("/api/v1/attribute-values").with(OFFICER).param("entityId", entityId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.asOf").isNotEmpty());
	}

	@Test
	void 로그인해야_보고_본_것은_감사_기록에_남는다() throws Exception {
		String packId = "av-audit";
		load(packId);
		String entityId = packId + "|운영지침|G-01";

		this.mvc.perform(get("/api/v1/attribute-values").param("entityId", entityId)).andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		body(entityId, "2026-03-01");

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("getAttributeValuesAsOf", "getAttributeValuesAsOf");
		assertThat(rows.get(1).target()).contains(entityId);
	}

	private String body(String entityId, String asOf) throws Exception {
		return this.mvc.perform(get("/api/v1/attribute-values").with(OFFICER).param("entityId", entityId).param("asOf", asOf))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private void problem(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status,
			String code) throws Exception {
		this.mvc.perform(request.with(OFFICER)).andExpect(status().is(status)).andExpect(jsonPath("$.code", is(code)));
	}

	/** 파일의 자료 행: 키(첫 칸) → 나머지 칸 가운데 비지 않은 것. */
	private static Map<String, List<String>> rows(Path file) throws Exception {
		Map<String, List<String>> rows = new TreeMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8).stream().skip(1).toList()) {
			if (!line.isBlank()) {
				List<String> cells = List.of(line.split(",", -1));
				rows.put(cells.get(0), cells.subList(1, cells.size()).stream().filter(cell -> !cell.isEmpty()).toList());
			}
		}
		return rows;
	}

	private Path load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : GUIDELINES) {
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", "guideline").param("effectiveDate", edition[1])
					.file(new MockMultipartFile("file", edition[0], "text/csv",
							Files.readAllBytes(pack.resolve("data/" + edition[0])))))
				.andExpect(status().isOk());
		}
		return pack;
	}
}
