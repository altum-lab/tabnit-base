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
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * C-16: 같은 입력 자료의 두 판을 견주는 API (docs/api/openapi.yaml 의 compareEditions, D7 5절 "두 판의 비교", D2 AD-418).
 *
 * <p>시험용 Pack 의 사본에 운영지침의 판 셋을 올린다. 이웃하지 않은 두 판(첫째와 셋째)의 정답은 이 시험이 예시 입력
 * 파일(CSV)에서 따로 센다: 제품의 비교를 쓰지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EditionComparisonApiTests {

	private static final String[][] EDITIONS = { { "program", "program-v1.csv", "2026-01-01" },
			{ "guideline", "guideline-v1.csv", "2026-01-01" }, { "guideline", "guideline-v2.csv", "2026-03-01" },
			{ "guideline", "guideline-v3.csv", "2026-05-01" } };

	/** 운영지침의 속성이 파일의 어느 칸인가 (시험용 Pack 의 mappings/guideline.ttl). 속성의 이름의 글자 코드 순이다. */
	private static final Map<String, Integer> COLUMN_OF = new TreeMap<>(Map.of("점검주기일", 2, "지침제목", 1));

	private static final RequestPostProcessor OFFICER = user("ec-officer").roles("OFFICER");

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
	void 바로_앞_판과의_비교는_올릴_때_저장한_것과_같다() throws Exception {
		String packId = "ec-adjacent";
		load(packId);
		for (int no = 2; no <= 3; no++) {
			String stored = body("/api/v1/edition?editionId=" + packId + "|guideline|" + no);
			String compared = body("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|" + (no - 1)
					+ "&toEditionId=" + packId + "|guideline|" + no);

			// 항목, 종류, 주 개체, 변동 대상, 바뀐 값(앞뒤, 보일 글자까지)이 저장된 판의 것과 같다.
			for (String field : List.of("itemKey", "kind", "primaryEntityId", "targetEntityIds", "changedValues", "primaryEntity",
					"targetEntities")) {
				assertThat(JsonPath.<List<Object>>read(compared, "$.changes[*]." + field)).as(no + "번째 판의 " + field)
					.isEqualTo(JsonPath.<List<Object>>read(stored, "$.changes[*]." + field));
			}
			assertThat(JsonPath.<List<Object>>read(compared, "$.changes")).isNotEmpty();
			assertThat(JsonPath.<Integer>read(compared, "$.from.editionNo")).isEqualTo(no - 1);
			assertThat(JsonPath.<Integer>read(compared, "$.to.editionNo")).isEqualTo(no);
			assertThat(JsonPath.<String>read(compared, "$.to.effectiveDate"))
				.isEqualTo(JsonPath.<String>read(stored, "$.effectiveDate"));
		}
	}

	@Test
	void 이웃하지_않은_두_판의_비교가_예시_입력_파일에서_따로_센_정답과_같다() throws Exception {
		String packId = "ec-far";
		Path pack = load(packId);
		// 정답: 첫째 판과 셋째 판의 파일을 행의 키(첫 칸)로 맞춰, 한쪽에만 있거나 글자가 다른 행.
		Map<String, String> first = rows(pack.resolve("data/guideline-v1.csv"));
		Map<String, String> third = rows(pack.resolve("data/guideline-v3.csv"));
		List<String> expectedKeys = new ArrayList<>();
		List<String> expectedKinds = new ArrayList<>();
		TreeMap<String, Boolean> keys = new TreeMap<>();
		first.keySet().forEach(key -> keys.put(key, true));
		third.keySet().forEach(key -> keys.put(key, true));
		for (String key : keys.keySet()) {
			if (!first.containsKey(key)) {
				expectedKeys.add(key);
				expectedKinds.add("ADDED");
			}
			else if (!third.containsKey(key)) {
				expectedKeys.add(key);
				expectedKinds.add("REMOVED");
			}
			else if (!first.get(key).equals(third.get(key))) {
				expectedKeys.add(key);
				expectedKinds.add("CHANGED");
			}
		}
		assertThat(expectedKinds).as("추가, 변경, 삭제가 섞여 있어야 이 시험에 뜻이 있다").hasSizeGreaterThan(1);

		String compared = body("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|1&toEditionId=" + packId
				+ "|guideline|3");

		assertThat(JsonPath.<List<String>>read(compared, "$.changes[*].itemKey")).containsExactlyElementsOf(expectedKeys);
		assertThat(JsonPath.<List<String>>read(compared, "$.changes[*].kind")).containsExactlyElementsOf(expectedKinds);
		assertThat(JsonPath.<String>read(compared, "$.packId")).isEqualTo(packId);
		assertThat(JsonPath.<String>read(compared, "$.from.inputSourceLabel")).isNotBlank();
		// 값의 앞뒤: 항목마다 바뀐 속성과 그 앞뒤의 값이, 두 파일의 그 행의 그 칸에서 따로 읽은 것과 꼭 같다.
		// 속성은 칸으로 맞춘다 (운영지침의 매핑: 지침제목은 둘째 칸, 점검주기일은 셋째 칸). 값이 같은 칸은 나오지 않는다:
		// 이 자료에는 값이 바뀌었다가 되돌아온 칸이 없다 (있으면 앞뒤가 같은 값으로 나온다, D2 AD-418).
		for (int i = 0; i < expectedKeys.size(); i++) {
			String key = expectedKeys.get(i);
			String[] firstCells = first.containsKey(key) ? first.get(key).split(",", -1) : null;
			String[] thirdCells = third.containsKey(key) ? third.get(key).split(",", -1) : null;
			List<String> expectedValues = new ArrayList<>();
			for (Map.Entry<String, Integer> column : COLUMN_OF.entrySet()) {
				String before = firstCells == null ? null : firstCells[column.getValue()];
				String after = thirdCells == null ? null : thirdCells[column.getValue()];
				if (!java.util.Objects.equals(before, after)) {
					expectedValues.add(column.getKey() + "|" + before + "|" + after);
				}
			}
			assertThat(expectedValues).as(key + " 의 따로 읽은 값").isNotEmpty();
			List<Map<String, Object>> values = JsonPath.read(compared, "$.changes[" + i + "].changedValues");
			List<String> actualValues = values.stream()
				.map(value -> value.get("attributeName") + "|" + value.get("before") + "|" + value.get("after"))
				.toList();
			// 순서는 속성의 이름의 글자 코드 순이다 (개체가 하나다).
			assertThat(actualValues).as(key + " 의 바뀐 값 (" + expectedKinds.get(i) + ")").containsExactlyElementsOf(expectedValues);
		}
		// 두 번 물어도 같다.
		assertThat(body("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|1&toEditionId=" + packId
				+ "|guideline|3")).isEqualTo(compared);
	}

	@Test
	void 견주는_것은_아무것도_쓰지_않는다() throws Exception {
		String packId = "ec-readonly";
		load(packId);
		String editions = body("/api/v1/editions?packId=" + packId);
		String changes = body("/api/v1/change-events?packId=" + packId);
		String actions = body("/api/v1/actions?packId=" + packId);

		body("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|1&toEditionId=" + packId + "|guideline|3");
		body("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|3&toEditionId=" + packId + "|guideline|1");

		// 판, 변동 이벤트, 조치가 그대로다 (수와 내용).
		assertThat(body("/api/v1/editions?packId=" + packId)).isEqualTo(editions);
		assertThat(body("/api/v1/change-events?packId=" + packId)).isEqualTo(changes);
		assertThat(body("/api/v1/actions?packId=" + packId)).isEqualTo(actions);
	}

	@Test
	void 같은_판_둘은_바뀐_것이_없고_뒤의_판을_앞에_적으면_거꾸로_견준다() throws Exception {
		String packId = "ec-direction";
		load(packId);
		String id2 = packId + "|guideline|2";
		String id3 = packId + "|guideline|3";

		this.mvc.perform(get0("/api/v1/edition-comparison").param("fromEditionId", id2).param("toEditionId", id2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes", hasSize(0)))
			.andExpect(jsonPath("$.from.editionId", is(id2)))
			.andExpect(jsonPath("$.to.editionId", is(id2)));
		String forward = body("/api/v1/edition-comparison?fromEditionId=" + id2 + "&toEditionId=" + id3);
		String backward = body("/api/v1/edition-comparison?fromEditionId=" + id3 + "&toEditionId=" + id2);
		// 같은 항목이 나오고 추가와 삭제가 뒤바뀐다.
		assertThat(JsonPath.<List<String>>read(backward, "$.changes[*].itemKey"))
			.isEqualTo(JsonPath.<List<String>>read(forward, "$.changes[*].itemKey"));
		List<String> kinds = JsonPath.read(forward, "$.changes[*].kind");
		List<String> reversed = JsonPath.read(backward, "$.changes[*].kind");
		for (int i = 0; i < kinds.size(); i++) {
			String expected = kinds.get(i).equals("ADDED") ? "REMOVED" : kinds.get(i).equals("REMOVED") ? "ADDED" : "CHANGED";
			assertThat(reversed.get(i)).isEqualTo(expected);
		}
		assertThat(JsonPath.<Integer>read(backward, "$.from.editionNo")).isEqualTo(3);
	}

	@Test
	void 틀린_입력은_어느_항목인지_알리며_거부한다() throws Exception {
		String packId = "ec-bad";
		load(packId);
		String guideline = packId + "|guideline|1";

		problem("/api/v1/edition-comparison?toEditionId=" + guideline, 400, "MISSING_FROM_EDITION_ID", "fromEditionId");
		problem("/api/v1/edition-comparison?fromEditionId=" + guideline, 400, "MISSING_TO_EDITION_ID", "toEditionId");
		problem("/api/v1/edition-comparison?fromEditionId=" + packId + "|guideline|9&toEditionId=" + guideline, 404,
				"EDITION_NOT_FOUND", "fromEditionId");
		problem("/api/v1/edition-comparison?fromEditionId=" + guideline + "&toEditionId=" + packId + "|guideline|9", 404,
				"EDITION_NOT_FOUND", "toEditionId");
		// 다른 입력 자료의 판.
		problem("/api/v1/edition-comparison?fromEditionId=" + guideline + "&toEditionId=" + packId + "|program|1", 400,
				"DIFFERENT_INPUT_SOURCES", "toEditionId");
	}

	@Test
	void 로그인해야_보고_본_것은_감사_기록에_남는다() throws Exception {
		String packId = "ec-audit";
		load(packId);
		String id1 = packId + "|guideline|1";
		String id2 = packId + "|guideline|2";

		this.mvc.perform(get("/api/v1/edition-comparison").param("fromEditionId", id1).param("toEditionId", id2))
			.andExpect(status().isUnauthorized());
		long mark = this.audit.lastId();
		body("/api/v1/edition-comparison?fromEditionId=" + id1 + "&toEditionId=" + id2);

		List<AuditRecord> rows = this.audit.after(mark);
		assertThat(rows).extracting(AuditRecord::action).containsExactly("compareEditions", "compareEditions");
		assertThat(rows.get(1).target()).contains(id1).contains(id2);
	}

	// ── 도우미 ────────────────────────────────────────────────

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder get0(String url) {
		return get(url).with(OFFICER);
	}

	private String body(String url) throws Exception {
		return this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(OFFICER))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	private void problem(String url, int status, String code, String field) throws Exception {
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).with(OFFICER))
			.andExpect(status().is(status))
			.andExpect(jsonPath("$.code", is(code)))
			.andExpect(jsonPath("$.field", is(field)));
	}

	/** 파일의 자료 행을 키(첫 칸)로 찾는 모음. 값은 그 행의 글자 전체다. */
	private static Map<String, String> rows(Path file) throws Exception {
		Map<String, String> rows = new TreeMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8).stream().skip(1).toList()) {
			if (!line.isBlank()) {
				rows.put(line.substring(0, line.indexOf(',')), line);
			}
		}
		return rows;
	}

	private Path load(String packId) throws Exception {
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		for (String[] edition : EDITIONS) {
			Path file = pack.resolve("data/" + edition[1]);
			this.mvc.perform(multipart("/api/v1/editions").with(OFFICER).with(csrf()).param("packId", packId)
					.param("inputSourceId", edition[0]).param("effectiveDate", edition[2])
					.file(new MockMultipartFile("file", edition[1], "text/csv", Files.readAllBytes(file))))
				.andExpect(status().isOk());
		}
		return pack;
	}
}
