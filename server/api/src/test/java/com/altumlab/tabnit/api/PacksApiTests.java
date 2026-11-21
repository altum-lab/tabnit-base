package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * D-05 (서버 쪽): Pack 과 입력 자료의 목록을 주는 API 가 규격(docs/api/openapi.yaml 의 listPacks)대로 답한다.
 *
 * <p>D7 8절: 판을 올릴 때 고를 Pack 과 입력 자료, 그리고 입력 자료마다 지금의 가장 새 판을 준다.
 * 시험용 Pack 의 사본을 쓴다. 이 데이터베이스에는 다른 시험이 넣은 Pack 도 있어, 이 시험이 넣은 것만 골라 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PacksApiTests {

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@Autowired
	EditionProcessing processing;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void 적재된_Pack_과_입력_자료를_정해진_순서로_주고_입력_자료마다_가장_새_판을_담는다() throws Exception {
		// 넣는 차례는 일부러 섞는다.
		Path pack = PackCopies.load(this.dataSource, this.temp, "packs-b", text -> text);
		PackCopies.load(this.dataSource, this.temp, "packs-a", text -> text);
		// packs-b 의 guideline 에 판 둘, program 에 판 하나를 올린다. 나머지는 판이 없다.
		this.processing.submit("packs-b", "guideline", null, Files.readAllBytes(pack.resolve("data/guideline-v1.csv")),
				LocalDate.parse("2026-01-01"));
		this.processing.submit("packs-b", "guideline", null, Files.readAllBytes(pack.resolve("data/guideline-v2.csv")),
				LocalDate.parse("2026-03-01"));
		this.processing.submit("packs-b", "program", null, Files.readAllBytes(pack.resolve("data/program-v1.csv")),
				LocalDate.parse("2026-01-01"));

		String body = this.mvc.perform(get("/api/v1/packs"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("application/json"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		// Pack 의 id 의 글자 코드 순.
		List<String> packIds = JsonPath.read(body, "$.packs[*].packId");
		assertThat(packIds).doesNotHaveDuplicates().isSorted();
		assertThat(packIds.stream().filter(id -> id.startsWith("packs-"))).containsExactly("packs-a", "packs-b");
		List<Map<String, Object>> found = JsonPath.read(body, "$.packs[?(@.packId == 'packs-b')]");
		Map<String, Object> packB = found.get(0);
		assertThat(packB.get("label")).isInstanceOf(String.class).isNotEqualTo("");
		// 입력 자료의 id 의 글자 코드 순. 시험용 Pack 의 매핑 여섯이다.
		List<String> sources = JsonPath.read(body, "$.packs[?(@.packId == 'packs-b')].inputSources[*].inputSourceId");
		assertThat(sources).containsExactly("guideline", "guideline-program", "partner-health", "partner-private", "program",
				"team");
		List<String> formats = JsonPath.read(body, "$.packs[?(@.packId == 'packs-b')].inputSources[*].fileFormat");
		assertThat(formats).containsOnly("CSV");
		// 가장 새 판: 판이 둘인 것은 둘째 판, 하나인 것은 첫 판, 없는 것은 null (항목은 빼지 않는다).
		List<Map<String, Object>> guideline = JsonPath.read(body,
				"$.packs[?(@.packId == 'packs-b')].inputSources[?(@.inputSourceId == 'guideline')].latestEdition");
		assertThat(guideline).containsExactly(
				Map.of("editionId", "packs-b|guideline|2", "editionNo", 2, "effectiveDate", "2026-03-01"));
		List<Map<String, Object>> program = JsonPath.read(body,
				"$.packs[?(@.packId == 'packs-b')].inputSources[?(@.inputSourceId == 'program')].latestEdition");
		assertThat(program).containsExactly(
				Map.of("editionId", "packs-b|program|1", "editionNo", 1, "effectiveDate", "2026-01-01"));
		List<Map<String, Object>> team = JsonPath.read(body,
				"$.packs[?(@.packId == 'packs-b')].inputSources[?(@.inputSourceId == 'team')]");
		assertThat(team).hasSize(1);
		assertThat(team.get(0)).containsKey("latestEdition");
		assertThat(team.get(0).get("latestEdition")).isNull();
		// 판을 올리지 않은 Pack 은 모든 입력 자료에 판이 없다.
		List<Object> none = JsonPath.read(body, "$.packs[?(@.packId == 'packs-a')].inputSources[*].latestEdition");
		assertThat(none).hasSize(6).containsOnlyNulls();
		// 두 번 불러도 같다.
		assertThat(this.mvc.perform(get("/api/v1/packs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
			.isEqualTo(body);
	}
}
