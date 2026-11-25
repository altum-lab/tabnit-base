package com.altumlab.tabnit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * B-15: 입력 자료가 나오는 답마다 그 이름표가 함께 온다 (docs/api/openapi.yaml 의 inputSourceLabel, D7 2절, D2 AD-384).
 *
 * <p>항목이 아예 없는 답이 통과하지 않게, 이름표의 항목이 있는지도 본다 (없는 항목을 모은 빈 목록은 "모두 같다"를 통과한다).
 * 이름표는 Pack 의 매핑에 적은 것이다. 적지 않았으면 입력 자료의 id 와 같다. 시험용 Pack 의 사본을 쓴다.
 * 모든 이름과 값은 지어낸 것이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InputSourceLabelApiTests {

	/** 시험용 Pack 의 처음 자료. 입력 자료와 파일. 시나리오의 앞 여섯 단계와 같다. */
	private static final String[][] FIRST = { { "program", "program-v1.csv" }, { "guideline", "guideline-v1.csv" },
			{ "guideline-program", "guideline-program-v1.csv" }, { "partner-health", "partner-health-v1.csv" },
			{ "partner-private", "partner-private-v1.csv" }, { "team", "team-v1.csv" } };

	@Autowired
	WebApplicationContext context;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path temp;

	MockMvc mvc;

	@BeforeEach
	void setUp() {
		this.mvc = MockMvcBuilders.webAppContextSetup(this.context).build();
	}

	@Test
	void 입력_자료가_나오는_답마다_매핑에_적은_이름표가_함께_온다() throws Exception {
		String packId = "isl-labelled";
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, text -> text);
		// 시험용 Pack 의 매핑에 이름표가 적혀 있어야 이 시험에 뜻이 있다.
		String guidelineLabel = labelIn(pack.resolve("mappings/guideline.ttl"));
		String linkLabel = labelIn(pack.resolve("mappings/guideline-program.ttl"));
		assertThat(guidelineLabel).isNotEqualTo("guideline").isNotBlank();
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}

		// 판을 올린 답. 식별자는 그대로 있다.
		upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv")).andExpect(status().isOk())
			.andExpect(jsonPath("$.inputSourceId").value("guideline"))
			.andExpect(jsonPath("$.inputSourceLabel").value(guidelineLabel));

		// Pack 의 목록.
		String packs = this.mvc.perform(get("/api/v1/packs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		List<String> listed = JsonPath.read(packs,
				"$.packs[?(@.packId == '" + packId + "')].inputSources[?(@.inputSourceId == 'guideline')].inputSourceLabel");
		assertThat(listed).containsExactly(guidelineLabel);

		// 지난 판의 목록과 지난 판 하나.
		this.mvc.perform(get("/api/v1/editions").param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.editions[0].inputSourceId").value("guideline"))
			.andExpect(jsonPath("$.editions[0].inputSourceLabel").value(guidelineLabel));
		this.mvc.perform(get("/api/v1/edition").param("editionId", packId + "|guideline|2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.inputSourceLabel").value(guidelineLabel));

		// 조치의 근거 조항의 판.
		this.mvc.perform(get("/api/v1/actions").param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.actions[*].basis.inputSourceId", everyItem(is("guideline"))))
			.andExpect(jsonPath("$.actions[*].basis.inputSourceLabel", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.actions[*].basis.inputSourceLabel", everyItem(is(guidelineLabel))));

		// 관계의 출처: 관계 조회와 관계 지도.
		String entityId = packId + "|운영지침|G-01";
		this.mvc.perform(get("/api/v1/relations").param("entityId", entityId).param("asOf", "2026-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.relations", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.relations[*].provenance.inputSourceId", everyItem(is("guideline-program"))))
			.andExpect(jsonPath("$.relations[*].provenance.inputSourceLabel", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.relations[*].provenance.inputSourceLabel", everyItem(is(linkLabel))));
		this.mvc.perform(get("/api/v1/relation-map").param("entityId", entityId).param("asOf", "2026-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.edges", hasSize(greaterThan(0))))
			// 지도에는 다른 입력 자료에서 온 관계도 있다. 이 입력 자료에서 온 것을 골라 본다.
			.andExpect(jsonPath("$.edges[?(@.provenance.inputSourceId == 'guideline-program')].provenance.inputSourceLabel",
					hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.edges[?(@.provenance.inputSourceId == 'guideline-program')].provenance.inputSourceLabel",
					everyItem(is(linkLabel))))
			// 어느 간선에도 이름표가 있다.
			.andExpect(jsonPath("$.edges[?(!@.provenance.inputSourceLabel)]", hasSize(0)));
	}

	@Test
	void 이름표를_적지_않은_입력_자료는_이름표가_식별자와_같다() throws Exception {
		String packId = "isl-plain";
		// 매핑에서 이름표의 줄을 뺀 사본.
		Path pack = PackCopies.load(this.dataSource, this.temp, packId,
				text -> text.contains("a tabnit:Mapping") ? text.replaceAll("(?m)^\\s*rdfs:label \"[^\"]*\" ;\\R", "") : text);
		assertThat(Files.readString(pack.resolve("mappings/guideline.ttl"))).doesNotContain("rdfs:label");

		upload(packId, "guideline", "2026-01-01", pack.resolve("data/guideline-v1.csv")).andExpect(status().isOk())
			.andExpect(jsonPath("$.inputSourceLabel").value("guideline"));
		String packs = this.mvc.perform(get("/api/v1/packs")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		List<String> labels = JsonPath.read(packs, "$.packs[?(@.packId == '" + packId + "')].inputSources[*].inputSourceLabel");
		List<String> ids = JsonPath.read(packs, "$.packs[?(@.packId == '" + packId + "')].inputSources[*].inputSourceId");
		assertThat(labels).isEqualTo(ids).isNotEmpty();
	}

	@Test
	void 처리되지_않은_변동에도_입력_자료의_이름표가_함께_온다() throws Exception {
		String packId = "isl-failed";
		PackCopies.requireUnstorableDueDate();
		Path pack = PackCopies.load(this.dataSource, this.temp, packId, PackCopies::unstorableDueDate);
		for (String[] first : FIRST) {
			upload(packId, first[0], "2026-01-01", pack.resolve("data/" + first[1])).andExpect(status().isOk());
		}
		upload(packId, "guideline", "2026-03-01", pack.resolve("data/guideline-v2.csv")).andExpect(status().isOk());

		this.mvc.perform(get("/api/v1/unprocessed-changes").param("packId", packId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.changes", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.changes[*].inputSourceLabel", hasSize(greaterThan(0))))
			.andExpect(jsonPath("$.changes[*].inputSourceLabel", everyItem(is(labelIn(pack.resolve("mappings/guideline.ttl"))))));
	}

	@Test
	void 식별자가_같은_입력_자료라도_Pack_마다_제_이름표로_온다() throws Exception {
		// 같은 정의의 사본 둘. 하나는 이름표의 끝에 글자를 덧붙였다.
		String plain = "isl-two-a";
		String marked = "isl-two-b";
		Path first = PackCopies.load(this.dataSource, this.temp, plain, text -> text);
		PackCopies.load(this.dataSource, this.temp, marked,
				text -> text.contains("a tabnit:Mapping") ? text.replaceAll("(rdfs:label \"[^\"]*)\"", "$1 (둘째)\"") : text);
		String label = labelIn(first.resolve("mappings/guideline.ttl"));
		for (String packId : List.of(plain, marked)) {
			for (String[] source : FIRST) {
				upload(packId, source[0], "2026-01-01", first.resolve("data/" + source[1])).andExpect(status().isOk());
			}
			upload(packId, "guideline", "2026-03-01", first.resolve("data/guideline-v2.csv")).andExpect(status().isOk());
		}

		for (String packId : List.of(marked, plain, marked)) {
			String expected = packId.equals(marked) ? label + " (둘째)" : label;
			this.mvc.perform(get("/api/v1/editions").param("packId", packId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.editions[0].inputSourceLabel").value(expected));
			this.mvc.perform(get("/api/v1/actions").param("packId", packId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.actions[*].basis.inputSourceLabel", hasSize(greaterThan(0))))
				.andExpect(jsonPath("$.actions[*].basis.inputSourceLabel", everyItem(is(expected))));
			this.mvc.perform(get("/api/v1/relations").param("entityId", packId + "|운영지침|G-01").param("asOf", "2026-01-01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.relations[*].provenance.inputSourceLabel", hasSize(greaterThan(0))))
				.andExpect(jsonPath("$.relations[*].provenance.inputSourceLabel", everyItem(org.hamcrest.Matchers.endsWith(
						packId.equals(marked) ? " (둘째)" : labelIn(first.resolve("mappings/guideline-program.ttl"))))));
		}
	}

	/** 매핑 파일에 적힌 이름표. 시험에 이름표의 글자를 옮겨 적지 않는다. */
	private static String labelIn(Path mapping) throws Exception {
		java.util.regex.Matcher found = java.util.regex.Pattern.compile("rdfs:label \"([^\"]+)\"")
			.matcher(Files.readString(mapping));
		if (!found.find()) {
			throw new IllegalStateException(mapping + " 에 이름표가 없습니다. 시험용 Pack 의 매핑에 rdfs:label 이 있어야 합니다.");
		}
		return found.group(1);
	}

	private ResultActions upload(String packId, String inputSourceId, String effectiveDate, Path file) throws Exception {
		return this.mvc.perform(multipart("/api/v1/editions").param("packId", packId)
			.param("inputSourceId", inputSourceId)
			.param("effectiveDate", effectiveDate)
			.file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv", Files.readAllBytes(file))));
	}
}
